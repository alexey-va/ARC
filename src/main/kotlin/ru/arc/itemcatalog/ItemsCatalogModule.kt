package ru.arc.itemcatalog

import org.bukkit.entity.Player
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.UnavailableOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.paper.api.ArcItemMaterializationReference
import ru.arc.paper.api.ArcItemMaterializationRequest
import ru.arc.paper.api.ArcItemMaterializerCapabilitySnapshot
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ItemsCatalogModule : PluginModule {
    override val name = "ItemsCatalog"
    override val priority = 84

    @Volatile private var settings: ItemsCatalogSettings? = null
    @Volatile private var service: ItemsCatalogService? = null
    @Volatile private var controller: ItemsCatalogGuiController? = null
    @Volatile private var rewardController: RewardCatalogGuiController? = null
    @Volatile private var collectionSeals: CollectionSealController? = null
    @Volatile private var physicalRewards: PhysicalRewardController? = null
    private var rewardLedger: OneTimeUseLedger? = null
    @Volatile private var physicalArchiveRepository: SharedPhysicalRewardArchiveRepository? = null
    private val archivePublishResult = AtomicReference(PhysicalRewardArchivePublishResult(0, 0, 0, "not_started"))
    @Volatile private var pendingProviderArchiveSources = 0
    private var personalMaps: PersonalTreasureMapController? = null
    private val interactiveWarmupEpoch = AtomicInteger()
    private val archiveWarmupMonitor = Any()
    private var activeArchiveBackfill: CompletableFuture<Unit>? = null

    override fun init() {
        start(
            ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot(),
            loadRewardCatalogOrDefault(),
        )
    }

    override fun reload() {
        val loaded = ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot()
        val rewards = loadRewardCatalogOrDefault()
        shutdownRuntime()
        start(loaded, rewards)
    }

    override fun shutdown() {
        shutdownRuntime()
        settings = null
    }

    fun isAvailable(): Boolean = controller != null || rewardController?.isAvailable() == true

    fun open(player: Player) {
        val activeController = controller
        if (activeController != null) {
            activeController.openRoot(player)
            return
        }
        player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог предметов сейчас недоступен.", true))
    }

    fun openRewards(player: Player) {
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог наград сейчас недоступен.", true))
    }

    internal fun currentSnapshot(): ItemsCatalogSnapshot? = service?.currentSnapshot()

    internal fun activeRewardSettings(): RewardCatalogSettings? = rewardController?.acquisitionSettings()

    fun rewardHealthSnapshot(): Map<String, Any?> {
        val repository = physicalArchiveRepository
        val sharedStatus = repository?.status()
        val publish = archivePublishResult.get()
        val archiveReady = sharedStatus?.ready == true && sharedStatus.failureCause == null && publish.failureCause == null
        return (rewardController?.healthSnapshot() ?: mapOf("enabled" to false)) + mapOf(
            "physicalRedemptionAvailable" to (physicalRewards?.available == true && archiveReady),
            "physicalRewardIssuanceAvailable" to (physicalRewards?.available == true && archiveReady),
            "physicalArchiveSharedReady" to (sharedStatus?.ready == true),
            "physicalArchiveBackfillReady" to archiveReady,
            "physicalArchiveBackfillScanned" to publish.scanned,
            "physicalArchiveBackfillPublished" to publish.published,
            "physicalArchiveBackfillBatches" to publish.batches,
            "physicalArchivePendingProviderSources" to pendingProviderArchiveSources,
            "physicalArchiveFailureCause" to (publish.failureCause ?: sharedStatus?.failureCause),
        )
    }

    fun issueCaseReward(player: Player, categoryId: String, entryId: String): CaseRewardIssueResult =
        rewardController?.issueCaseReward(player, categoryId, entryId) ?: CaseRewardIssueResult.UNAVAILABLE

    internal fun itemMaterializerCapability(): ArcItemMaterializerCapabilitySnapshot =
        rewardController?.materializerCapability()
            ?: ArcItemMaterializerCapabilitySnapshot(available = false, catalogFingerprint = null)

    internal fun prepareItemMaterialization(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? =
        rewardController?.prepareCaseReward(request.categoryId, request.entryId)

    internal fun materializeItem(reference: ArcItemMaterializationReference): List<org.bukkit.inventory.ItemStack>? =
        rewardController?.materializeCaseReward(reference)

    private fun loadRewardCatalogOrDefault(): RewardCatalogSettings =
        rewardCatalogOrDefault(
            load = { RewardCatalogModuleConfig.load(ARC.instance.dataPath).snapshot() },
            onFailure = { failure ->
                warn(
                    "Reward catalogue configuration is invalid; reward tab disabled while the item catalogue stays available: {}",
                    failure.message ?: failure.javaClass.simpleName,
                )
            },
        )

    private fun start(loaded: ItemsCatalogSettings, rewards: RewardCatalogSettings) {
        settings = loaded
        val frozenRewards = FrozenPhysicalRewards(ARC.instance.dataPath)
        lateinit var nativeRewards: CatalogPhysicalRewards
        val seals = CollectionSealController(ARC.instance, rewards) { categoryId -> nativeRewards.archivedSeal(categoryId) }
        nativeRewards = CatalogPhysicalRewards(rewards, frozen = frozenRewards, sealStack = seals::createStack)
        if (rewards.enabled) {
            seals.register()
            collectionSeals = seals
        }
        val storage = PhysicalRewardStorageConfig.load(ARC.instance.dataPath)
        val archiveRepository = if (rewards.enabled && storage.enabled) runCatching {
            MySqlSharedPhysicalRewardArchiveRepository.open(storage.sql())
        }.getOrElse {
            warn("Shared physical reward archive unavailable: cause={}", it.javaClass.simpleName)
            null
        } else null
        physicalArchiveRepository = archiveRepository
        archivePublishResult.set(PhysicalRewardArchivePublishResult(0, 0, 0, "not_started"))
        pendingProviderArchiveSources = 0
        val ledger = if (rewards.enabled && storage.enabled) runCatching {
            MySqlOneTimeUseLedger.open(
                storage.sql(),
                "ARC-catalog-rewards", "arc_catalog_rewards",
                listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                MySqlOneTimeUsePartition("arc.catalog-reward"),
            )
        }.getOrElse {
            warn("Physical reward storage unavailable: {}", it.javaClass.simpleName)
            UnavailableOneTimeUseLedger
        } else UnavailableOneTimeUseLedger
        rewardLedger = ledger
        val maps = if (rewards.enabled) PersonalTreasureMapController(
            ARC.instance, nativeRewards::resolve, nativeRewards::personalMapDefinition,
        ).also { personalMaps = it } else null
        val choiceDialogs = RewardChoiceController()
        val physical = PhysicalRewardController(
            ARC.instance, ledger, nativeRewards::resolve,
            { player, spec ->
                val mapFailure = if (maps != null && nativeRewards.personalMapDefinition(spec) != null) {
                    val voucher = PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)
                    if (voucher == null) PersonalTreasureMapFailure.INVALID_OR_STALE
                    else maps.preflight(player, voucher, spec)
                } else null
                mapFailure?.let(::mapFailureMessage) ?: nativeRewards.canRedeem(player, spec)
            },
            nativeRewards::redeem, ARC.serverName ?: "arc", choiceDialogs::open,
            beforeUse = { player, spec, voucher ->
                when (maps?.beforeUse(player, player.inventory.itemInMainHand)) {
                    PersonalTreasureMapUseDecision.OPEN_MAP -> {
                        showMapGuidance(player, maps)
                        false
                    }
                    PersonalTreasureMapUseDecision.REJECT -> {
                        player.sendActionBar(TextUtil.mm(mapFailureMessage(
                            maps.preflight(player, voucher, spec) ?: PersonalTreasureMapFailure.INVALID_OR_STALE,
                        ), true))
                        false
                    }
                    else -> true
                }
            },
            resolveArchiveAsync = { key ->
                archiveRepository?.let { nativeRewards.fetchSharedArchive(key, it) }
                    ?: CompletableFuture.failedFuture(IllegalStateException("shared archive unavailable"))
            },
            importArchiveLookup = nativeRewards::importSharedArchive,
        )
        if (rewards.enabled) {
            physical.register()
            physicalRewards = physical
        }
        val createPhysical: (String) -> org.bukkit.inventory.ItemStack? = { key ->
            if (!nativeRewards.canMaterialize(key)) null
            else if (key.startsWith("set_frozen_")) nativeRewards.createSealStack(key)
            else physical.createStack(key)
        }
        rewardController = RewardCatalogGuiController(
            rewards, loaded.givePermission, seals::createStack,
            { entry ->
                if (entry.previewItemsAdder != null) nativeRewards.visualPreview(entry)
                else {
                    val key = if (entry.source is RewardCatalogSource.Choice || entry.source is RewardCatalogSource.PersonalMap) {
                        nativeRewards.materialization(entry)?.sourceKey
                    } else nativeRewards.key(entry)
                    key?.let(physical::previewStack)
                }
            },
            { entry -> nativeRewards.materialization(entry)
                ?.let { createPhysical(it.sourceKey) } },
            nativeRewards::isVoucherSource,
            nativeRewards::materialization,
            createPhysical,
            { entry ->
                entry.source !is RewardCatalogSource.PersonalMap ||
                    nativeRewards.materialization(entry)?.let { nativeRewards.canMaterialize(it.sourceKey) } == true
            },
        ).takeIf { rewards.enabled }
        val warmupEpoch = synchronized(archiveWarmupMonitor) { interactiveWarmupEpoch.incrementAndGet() }
        if (rewards.enabled) warmPhysicalArchives(nativeRewards, archiveRepository, warmupEpoch, attempt = 1)
        info(
            "Reward catalogue loaded: enabled={} categories={} entries={}",
            rewards.enabled,
            rewards.categories.size,
            rewards.entryCount,
        )
        if (!loaded.enabled) {
            info("Items catalog module disabled by configuration")
            return
        }
        val itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder")
        if (itemsAdder == null || !itemsAdder.isEnabled) {
            warn("Items catalog module disabled because ItemsAdder is unavailable")
            return
        }
        val gateway = BukkitItemsAdderCatalogGateway(itemsAdder)
        val activeService = ItemsCatalogService(ARC.instance, loaded, gateway)
        service = activeService
        controller = ItemsCatalogGuiController(loaded, activeService)
        activeService.start()
        info("Items catalog module initialized and is waiting for the ItemsAdder index")
    }

    private fun shutdownRuntime() {
        val closingBackfill = invalidateArchiveWarmup()
        controller?.shutdown()
        controller = null
        rewardController?.shutdown()
        rewardController = null
        personalMaps?.close()
        personalMaps = null
        val closingRewards = physicalRewards
        val closingLedger = rewardLedger
        val closingArchiveRepository = physicalArchiveRepository
        physicalRewards = null
        rewardLedger = null
        physicalArchiveRepository = null
        val drained = closingRewards?.closeAndDrain() ?: CompletableFuture.completedFuture(null)
        val drainedWithinBound = drained.orTimeout(5, TimeUnit.SECONDS).handle { _, failure ->
            if (failure != null) warn("Physical reward shutdown retained unfinished claims for recovery")
            null
        }
        val backfillFinished = closingBackfill?.handle { _, _ -> null } ?: CompletableFuture.completedFuture(null)
        CompletableFuture.allOf(drainedWithinBound, backfillFinished).whenCompleteAsync { _, _ ->
            closingLedger?.close()
            closingArchiveRepository?.close()
        }
        collectionSeals?.close()
        collectionSeals = null
        service?.shutdown()
        service = null
    }

    /** Archived sources appear only after local persistence and shared publication succeed. */
    private fun warmPhysicalArchives(
        nativeRewards: CatalogPhysicalRewards,
        repository: SharedPhysicalRewardArchiveRepository?,
        epoch: Int,
        attempt: Int,
        captured: PhysicalRewardArchiveCapture? = null,
    ) {
        if (interactiveWarmupEpoch.get() != epoch) return
        if (repository == null) {
            if (setArchivePublishResult(epoch, PhysicalRewardArchivePublishResult(0, 0, 0, "shared_repository_unavailable"))) {
                warn("Shared physical reward archive unavailable: cause=repository_not_configured")
            }
            return
        }
        val capture = captured ?: nativeRewards.capturePhysicalArchives()
        pendingProviderArchiveSources = nativeRewards.pendingArchiveSourceCount()
        if (capture == null) {
            if (!setArchivePublishResult(epoch, PhysicalRewardArchivePublishResult(0, 0, 0, "capture_unavailable"))) return
            if (attempt >= INTERACTIVE_WARMUP_ATTEMPTS) {
                warn("Physical reward archive capture unavailable after {} attempts", attempt)
                return
            }
            scheduleArchiveRetry(epoch, INTERACTIVE_WARMUP_TICKS) {
                warmPhysicalArchives(nativeRewards, repository, epoch, attempt + 1)
            }
            return
        }
        val completion = CompletableFuture<Unit>()
        synchronized(archiveWarmupMonitor) {
            if (interactiveWarmupEpoch.get() != epoch) return
            activeArchiveBackfill = completion
            try {
                Tasks.scheduler.runAsync(Runnable {
                    try {
                        if (interactiveWarmupEpoch.get() != epoch) return@Runnable
                        val result = nativeRewards.persistPhysicalArchives(capture, repository)
                        if (!setArchivePublishResult(epoch, result)) return@Runnable
                        if (result.failureCause == null) {
                            val pendingSources = nativeRewards.pendingArchiveSourceCount()
                            pendingProviderArchiveSources = pendingSources
                            info(
                                "Physical reward archive backfill complete: scanned={} published={} batches={} pendingSources={}",
                                result.scanned,
                                result.published,
                                result.batches,
                                pendingSources,
                            )
                            if (pendingSources > 0) {
                                scheduleArchiveRetry(epoch, OPTIONAL_PROVIDER_RETRY_TICKS) {
                                    warmPhysicalArchives(nativeRewards, repository, epoch, attempt = 1)
                                }
                            }
                            return@Runnable
                        }
                        if (result.failureCause.startsWith("shared_publish_")) {
                            if (attempt == 1 || attempt % ARCHIVE_RETRY_LOG_INTERVAL == 0) {
                                warn(
                                    "Physical reward archive publish retry: attempt={} scanned={} published={} batches={} cause={}",
                                    attempt,
                                    result.scanned,
                                    result.published,
                                    result.batches,
                                    result.failureCause,
                                )
                            }
                            val nextCapture = capture.takeIf { capture.complete }
                            scheduleArchiveRetry(epoch, archiveRetryDelay(attempt)) {
                                warmPhysicalArchives(nativeRewards, repository, epoch, nextAttempt(attempt), nextCapture)
                            }
                        } else if (attempt >= INTERACTIVE_WARMUP_ATTEMPTS) {
                            warn(
                                "Physical reward archive backfill unavailable after {} attempts: scanned={} published={} batches={} cause={}",
                                attempt,
                                result.scanned,
                                result.published,
                                result.batches,
                                result.failureCause,
                            )
                        } else {
                            scheduleArchiveRetry(epoch, INTERACTIVE_WARMUP_TICKS) {
                                val nextCapture = capture.takeIf { capture.complete }
                                warmPhysicalArchives(nativeRewards, repository, epoch, nextAttempt(attempt), nextCapture)
                            }
                        }
                    } finally {
                        completion.complete(Unit)
                        synchronized(archiveWarmupMonitor) {
                            if (activeArchiveBackfill === completion) activeArchiveBackfill = null
                        }
                    }
                })
            } catch (failure: Throwable) {
                completion.complete(Unit)
                if (activeArchiveBackfill === completion) activeArchiveBackfill = null
                if (interactiveWarmupEpoch.get() == epoch) {
                    archivePublishResult.set(
                        PhysicalRewardArchivePublishResult(0, 0, 0, "backfill_scheduler_${failure.javaClass.simpleName}"),
                    )
                    warn("Physical reward archive backfill could not be scheduled: cause={}", failure.javaClass.simpleName)
                }
            }
        }
    }

    private fun setArchivePublishResult(epoch: Int, result: PhysicalRewardArchivePublishResult): Boolean =
        synchronized(archiveWarmupMonitor) {
            if (interactiveWarmupEpoch.get() != epoch) return@synchronized false
            archivePublishResult.set(result)
            true
        }

    private fun scheduleArchiveRetry(epoch: Int, delayTicks: Long, retry: () -> Unit) {
        synchronized(archiveWarmupMonitor) {
            if (interactiveWarmupEpoch.get() != epoch) return
            Tasks.scheduler.runLater(delayTicks, Runnable {
                if (interactiveWarmupEpoch.get() == epoch) retry()
            })
        }
    }

    private fun archiveRetryDelay(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 6)
        return (20L * (1 shl exponent).toLong()).coerceAtMost(MAX_ARCHIVE_RETRY_TICKS)
    }

    private fun nextAttempt(attempt: Int): Int = if (attempt == Int.MAX_VALUE) attempt else attempt + 1

    private const val ARCHIVE_RETRY_LOG_INTERVAL = 60
    private const val MAX_ARCHIVE_RETRY_TICKS = 1_200L
    private const val OPTIONAL_PROVIDER_RETRY_TICKS = 1_200L

    private fun invalidateArchiveWarmup(): CompletableFuture<Unit>? = synchronized(archiveWarmupMonitor) {
        interactiveWarmupEpoch.incrementAndGet()
        activeArchiveBackfill.also {
            activeArchiveBackfill = null
            archivePublishResult.set(PhysicalRewardArchivePublishResult(0, 0, 0, "not_started"))
            pendingProviderArchiveSources = 0
        }
    }

    private fun showMapGuidance(player: Player, maps: PersonalTreasureMapController) {
        val guidance = maps.guidance(player) ?: return
        val message = when {
            !guidance.onDestinationServer -> "<#e8dfd2>Возьмите карту на сервер выживания."
            !guidance.onDestinationWorld -> guidance.targetWorld?.let { world ->
                val name = if (world == "vanilla") "Ванильный мир" else "Новые биомы"
                "<#e8dfd2>Ваш тайник в мире «$name». Возьмите карту туда."
            } ?: "<#e8dfd2>Активируйте карту в мире выживания."
            guidance.safetyUnavailable -> "<#e9c46a>Не удалось проверить безопасность этой точки. Карта сохранена — попробуйте позже."
            !guidance.targetSelected && guidance.searching -> "<#e8dfd2>Ищем безопасный тайник в исследованной части мира…"
            !guidance.targetSelected && guidance.ownerBound -> "<#e9c46a>Безопасная точка пока не найдена. Нажмите ПКМ позже, чтобы повторить поиск."
            !guidance.targetSelected -> "<#e8dfd2>ПКМ — активировать карту и найти сокровище."
            guidance.withinClaimRadius -> "<#9bd48d>Тайник здесь · ПКМ — забрать находку"
            else -> "<#e8dfd2>До тайника <#e9c46a>${kotlin.math.ceil(guidance.distance ?: 0.0).toInt()} м <#e8dfd2>· ${guidance.hint}"
        }
        player.sendActionBar(TextUtil.mm(message, true))
    }

    private fun mapFailureMessage(failure: PersonalTreasureMapFailure): String = when (failure) {
        PersonalTreasureMapFailure.WRONG_OWNER -> "<#e9c46a>Эту карту уже активировал другой игрок."
        PersonalTreasureMapFailure.WRONG_SERVER -> "<#e8dfd2>Возьмите карту на сервер выживания."
        PersonalTreasureMapFailure.WRONG_WORLD -> "<#e8dfd2>Вернитесь в мир, где вы активировали карту."
        PersonalTreasureMapFailure.TOO_FAR -> "<#e9c46a>Подойдите к тайнику ближе и нажмите ПКМ."
        PersonalTreasureMapFailure.TARGET_CHANGED -> "<#e9c46a>Точка тайника стала защищённой. Карта ищет новое безопасное место."
        PersonalTreasureMapFailure.NO_SAFE_TARGET -> "<#e9c46a>Не удалось найти безопасное место для тайника. Попробуйте позже."
        PersonalTreasureMapFailure.SAFETY_UNAVAILABLE -> "<#e9c46a>Не удалось проверить безопасность точки. Попробуйте позже; карта не потрачена."
        PersonalTreasureMapFailure.INVALID_OR_STALE -> "<#e9c46a>Эта карта сейчас недоступна."
    }

    private const val INTERACTIVE_WARMUP_TICKS = 20L
    private const val INTERACTIVE_WARMUP_ATTEMPTS = 60
}

internal fun rewardCatalogOrDefault(
    load: () -> RewardCatalogSettings,
    onFailure: (Throwable) -> Unit = {},
): RewardCatalogSettings =
    runCatching(load).getOrElse { failure ->
        onFailure(failure)
        RewardCatalogSettings(
            enabled = false,
            title = "<gold><bold>Сокровищница наград",
            categories = emptyList(),
            messages = RewardCatalogMessages.DEFAULT,
        )
    }
