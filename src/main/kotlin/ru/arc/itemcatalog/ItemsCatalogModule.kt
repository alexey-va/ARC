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
    private var personalMaps: PersonalTreasureMapController? = null
    private val interactiveWarmupEpoch = AtomicInteger()

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
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог предметов сейчас недоступен.", true))
    }

    fun openRewards(player: Player) {
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог наград сейчас недоступен.", true))
    }

    internal fun currentSnapshot(): ItemsCatalogSnapshot? = service?.currentSnapshot()

    fun rewardHealthSnapshot(): Map<String, Any?> =
        (rewardController?.healthSnapshot() ?: mapOf("enabled" to false)) +
            ("physicalRedemptionAvailable" to (physicalRewards?.available == true))

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
        ).takeIf { rewards.enabled }
        val warmupEpoch = interactiveWarmupEpoch.incrementAndGet()
        if (rewards.enabled) warmInteractiveArchives(nativeRewards, warmupEpoch, attempt = 1)
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
        controller = ItemsCatalogGuiController(loaded, activeService, rewardController)
        activeService.start()
        info("Items catalog module initialized and is waiting for the ItemsAdder index")
    }

    private fun shutdownRuntime() {
        interactiveWarmupEpoch.incrementAndGet()
        controller?.shutdown()
        controller = null
        rewardController?.shutdown()
        rewardController = null
        personalMaps?.close()
        personalMaps = null
        val closingRewards = physicalRewards
        val closingLedger = rewardLedger
        physicalRewards = null
        rewardLedger = null
        val drained = closingRewards?.closeAndDrain() ?: CompletableFuture.completedFuture(null)
        drained.orTimeout(5, TimeUnit.SECONDS).whenCompleteAsync { _, failure ->
            if (failure != null) warn("Physical reward shutdown retained unfinished claims for recovery")
            closingLedger?.close()
        }
        collectionSeals?.close()
        collectionSeals = null
        service?.shutdown()
        service = null
    }

    /** Provider-backed choice/map sources appear only after their frozen records commit successfully. */
    private fun warmInteractiveArchives(
        nativeRewards: CatalogPhysicalRewards,
        epoch: Int,
        attempt: Int,
        captured: List<FrozenPhysicalPrepared>? = null,
    ) {
        if (interactiveWarmupEpoch.get() != epoch) return
        val snapshots = captured ?: nativeRewards.captureInteractiveArchives()
        if (snapshots == null) {
            if (attempt >= INTERACTIVE_WARMUP_ATTEMPTS) {
                warn("Choice/map reward archive capture stayed unavailable after {} attempts", attempt)
                return
            }
            Tasks.scheduler.runLater(INTERACTIVE_WARMUP_TICKS, Runnable {
                warmInteractiveArchives(nativeRewards, epoch, attempt + 1)
            })
            return
        }
        Tasks.scheduler.runAsync(Runnable {
            if (interactiveWarmupEpoch.get() != epoch) return@Runnable
            if (nativeRewards.persistInteractiveArchives(snapshots)) return@Runnable
            if (attempt >= INTERACTIVE_WARMUP_ATTEMPTS) {
                warn("Choice/map reward archive persistence stayed unavailable after {} attempts", attempt)
            } else {
                Tasks.scheduler.runLater(INTERACTIVE_WARMUP_TICKS, Runnable {
                    warmInteractiveArchives(nativeRewards, epoch, attempt + 1, snapshots)
                })
            }
        })
    }

    private fun showMapGuidance(player: Player, maps: PersonalTreasureMapController) {
        val guidance = maps.guidance(player) ?: return
        val message = when {
            !guidance.onDestinationServer || !guidance.onDestinationWorld -> "<#e8dfd2>Тайник ищется в обычном мире на Survival. Возьмите карту туда."
            guidance.safetyUnavailable -> "<#e9c46a>Не удалось проверить безопасность этой точки. Карта сохранена — попробуйте позже."
            !guidance.targetSelected && guidance.ownerBound -> "<#e9c46a>Безопасная точка пока не найдена. Нажмите ПКМ позже, чтобы повторить поиск."
            !guidance.targetSelected -> "<#e8dfd2>ПКМ в обычном мире на Survival — закрепить карту и найти безопасное место."
            guidance.withinClaimRadius -> "<#9bd48d>Тайник здесь · ПКМ — забрать находку"
            else -> "<#e8dfd2>До тайника <#e9c46a>${kotlin.math.ceil(guidance.distance ?: 0.0).toInt()} м <#e8dfd2>· ${guidance.hint}"
        }
        player.sendActionBar(TextUtil.mm(message, true))
    }

    private fun mapFailureMessage(failure: PersonalTreasureMapFailure): String = when (failure) {
        PersonalTreasureMapFailure.WRONG_OWNER -> "<#e9c46a>Эту карту уже активировал другой игрок."
        PersonalTreasureMapFailure.WRONG_SERVER,
        PersonalTreasureMapFailure.WRONG_WORLD -> "<#e8dfd2>Ваш тайник находится в обычном мире на Survival. Возьмите карту туда."
        PersonalTreasureMapFailure.TOO_FAR -> "<#e9c46a>Подойдите к тайнику ближе и нажмите ПКМ."
        PersonalTreasureMapFailure.TARGET_CHANGED -> "<#e9c46a>Точка тайника стала защищённой. Карта выбрала новое безопасное место."
        PersonalTreasureMapFailure.NO_SAFE_TARGET -> "<#e9c46a>Поблизости не найдено безопасного места. Попробуйте ещё раз позже."
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
