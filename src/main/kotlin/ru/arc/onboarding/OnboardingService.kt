package ru.arc.onboarding

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import ru.ruscrafting.onboarding.api.OnboardingBuildProgressService
import java.util.concurrent.CompletionStage
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.helpcenter.HelpCenterPage
import ru.arc.economy.RedisEconomyWallet
import ru.arc.itemcatalog.PhysicalRewardStorageConfig
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.ProductEntryPoint
import ru.arc.metrics.ProductFeature
import ru.arc.metrics.ProductOutcome
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Main-thread quest controller. Redis owns durable state; callbacks are lifecycle-fenced. */
object OnboardingService : OnboardingBuildProgressService {
    private class Runtime(val config: OnboardingConfig, val store: OnboardingStore?) {
        val tasks = LifecycleTaskScope()
        val starterInventory = OnboardingStarterInventory(ARC.instance.dataPath, ARC.instance.backgroundOwner(), tasks)
        val ready = CompletableFuture<Void>()
        val players = mutableMapOf<UUID, OnboardingProgress>()
        val tails = mutableMapOf<UUID, CompletableFuture<OnboardingProgress?>>()
        val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
        val requests = mutableMapOf<UUID, Long>()
        val playtime = ARC.redisManager?.let(::OnboardingPlaytimeStore)
        val played = mutableMapOf<UUID, Pair<String, OnboardingPlaytime>>()
        val playtimeTails = ConcurrentHashMap<String, CompletableFuture<OnboardingPlaytime>>()
        val playtimeFailures = mutableSetOf<UUID>()
        val invalidPlaytimes = mutableMapOf<UUID, String>()
        val expiring = mutableSetOf<String>()
        var rewardLedger: MySqlOneTimeUseLedger? = null
        var rewards: OnboardingRewards? = null
        lateinit var menu: OnboardingQuestDialog
        var npc: OnboardingQuestNpc? = null
    }

    @Volatile private var runtime: Runtime? = null
    private var drained = CompletableFuture.completedFuture(Unit)

    internal fun closeCompletion(): CompletableFuture<Unit> = drained.copy()
    internal fun readiness(): CompletableFuture<Void> = runtime?.ready?.copy()
        ?: CompletableFuture.failedFuture(IllegalStateException("Onboarding is unavailable"))
    internal fun guideConfig(): OnboardingConfig? = runtime?.config
    fun isEnabled(): Boolean = runtime != null
    fun currentRunId(playerId: UUID): String? = runtime?.players?.get(playerId)?.takeIf { it.status == OnboardingStatus.ACTIVE }?.runId
    fun hasActiveQuest(playerId: UUID): Boolean = runtime?.players?.get(playerId)?.status == OnboardingStatus.ACTIVE

    internal fun init(onReady: (Set<Int>) -> Unit) {
        shutdown()
        drained.handle { _, _ -> Unit }.join() // Maintenance only: previous writes must finish before reopening.
        // Plugin startup/manual reload is a maintenance boundary; no gameplay file IO.
        val config = OnboardingConfig.load(Config(ARC.instance.dataPath,
            ConfigManager.moduleYamlRelative(ARC.instance.dataPath, "onboarding.yml")))
        config.validate()
        val current = Runtime(config, ARC.redisManager?.let(::OnboardingStore))
        runtime = current
        runCatching {
            val storage = PhysicalRewardStorageConfig.load(ARC.instance.dataPath)
            check(storage.enabled) { "Network reward storage is disabled" }
            val ledger = MySqlOneTimeUseLedger.open(
                storage.sql(), "ARC-onboarding-rewards", "arc_onboarding_rewards",
                listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                MySqlOneTimeUsePartition("arc.onboarding-reward"),
            )
            current.rewardLedger = ledger
            current.rewards = OnboardingRewards(ledger, RedisEconomyWallet(), current.tasks, valid = { player ->
                runtime === current && player.isOnline && Bukkit.getPlayer(player.uniqueId) === player
            })
        }.onFailure { failure ->
            current.ready.completeExceptionally(failure)
            error("Initial quest reward storage is unavailable", failure)
        }
        Bukkit.getServicesManager().register(OnboardingBuildProgressService::class.java, this, ARC.instance, ServicePriority.Normal)
        current.menu = OnboardingQuestDialog(
            view = ::questView,
            accept = ::accept,
            openGoal = ::openGoal,
            text = config::questText,
        )
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            current.npc = OnboardingQuestNpc(
                plugin = ARC.instance,
                npcId = config.npcId,
                visible = { player -> canAccess(player) && current.players[player.uniqueId]?.status != OnboardingStatus.ACTIVE &&
                    current.players[player.uniqueId]?.status != OnboardingStatus.COMPLETED },
                eligible = ::canAccess,
                open = ::open,
            ).also { it.start() }
        }
        val landsFile = Bukkit.getPluginManager().getPlugin("Lands")?.dataFolder?.let { File(it, "config.yml") }
        val colors = landsFile?.let { claimGuideParticleColors(YamlConfiguration.loadConfiguration(it)) }.orEmpty()
        current.tasks.runTimer(20L, 100L) {
            Bukkit.getOnlinePlayers().forEach { player ->
                samplePlaytime(current, player)
                current.players[player.uniqueId]?.let { reconcileRewards(current, player, it) }
            }
        }
        current.ready.complete(null)
        onReady(colors)
        info("Initial quest ready; public-enabled={}, npc={}", config.publicEnabled, config.npcId)
    }

    fun shutdown() {
        val previous = runtime ?: return
        Bukkit.getOnlinePlayers().forEach { samplePlaytime(previous, it, force = true) }
        runtime = null
        Bukkit.getServicesManager().unregister(OnboardingBuildProgressService::class.java, this)
        previous.menu.close()
        previous.npc?.close()
        val rewardsDrained = previous.rewards?.closeAsync() ?: CompletableFuture.completedFuture(null)
        previous.tasks.close()
        drained = CompletableFuture.allOf(*previous.pending.toTypedArray(), rewardsDrained).handle { _, failure ->
            if (failure != null) error("Could not drain initial quest operations", failure)
            Unit
        }.thenCompose { previous.rewardLedger?.closeAsync() ?: CompletableFuture.completedFuture(Unit) }
        drained.whenComplete { _, failure -> if (failure != null) error("Could not drain initial quest operations", failure) }
    }

    fun canAccess(player: Player): Boolean {
        val current = runtime ?: return false
        val status = current.players[player.uniqueId]?.status
        if (status == OnboardingStatus.ACTIVE && current.invalidPlaytimes[player.uniqueId] == current.players[player.uniqueId]?.runId) return false
        return current.store != null && status != OnboardingStatus.STOPPED && (current.config.publicEnabled || status != null)
    }

    internal fun questView(player: Player): OnboardingQuestView? {
        if (!canAccess(player)) return null
        val progress = runtime?.players?.get(player.uniqueId)
        if (progress?.status == OnboardingStatus.STOPPED) return null
        return OnboardingQuestView(
            runId = progress?.runId ?: "new",
            accepted = progress?.status == OnboardingStatus.ACTIVE || progress?.status == OnboardingStatus.COMPLETED,
            milestones = progress?.milestones.orEmpty(),
        )
    }

    internal fun guideView(player: Player): OnboardingGuideView? {
        val current = runtime ?: return null
        val progress = current.players[player.uniqueId]?.takeIf { it.status == OnboardingStatus.ACTIVE } ?: return null
        if (current.invalidPlaytimes[player.uniqueId] == progress.runId) return null
        val clock = current.played[player.uniqueId]?.takeIf { it.first == progress.runId }?.second
        if (clock?.expired == true) return null
        return OnboardingGuideView(
            OnboardingQuestView(progress.runId, true, progress.milestones),
            clock?.remainingTicks ?: OnboardingPlaytime.LIMIT_TICKS,
            OnboardingGuideReward(current.config.coinsPerStep, current.config.tokensPerStep),
        )
    }

    /** No join-time enrollment. Only an existing record is loaded here. */
    fun resume(player: Player) {
        request(player, { store, id -> store.load(id) }) { current, progress ->
            current.npc?.refresh(player)
            confirmArrival(player, progress)
        }
    }

    fun leave(playerId: UUID) {
        val current = runtime ?: return
        Bukkit.getPlayer(playerId)?.let { samplePlaytime(current, it, force = true) }
        current.players.remove(playerId)
        current.played.remove(playerId)
        current.playtimeFailures.remove(playerId)
        current.invalidPlaytimes.remove(playerId)
        current.menu.forget(playerId)
        current.requests.remove(playerId)
        current.npc?.clear(playerId)
        // Keep an accepted operation's tail until its Redis write has completed.
        val tail = current.tails[playerId]
        tail?.whenComplete { _, _ -> current.tasks.runSync {
            if (Bukkit.getPlayer(playerId) == null && current.tails[playerId] === tail) current.tails.remove(playerId)
        } }
    }

    fun open(player: Player) {
        val active = runtime ?: return
        ArcMenus.beginDialogFlow(player)
        val visit = active.menu.beginPending(player)
        request(player, { store, id -> store.load(id) }, pendingToken = visit) { current, _ ->
            current.menu.finish(player, visit, if (canAccess(player)) null else "Начальный квест пока проходит закрытый тест. Для администратора: /arc onboarding test")
        }
    }

    /** Enrollment only: the administrator's inventory stays intact. */
    fun enrollSelf(player: Player) {
        if (!isTestAdmin(player)) { message(player, "<red>Повторный запуск теста доступен администратору."); return }
        val active = runtime ?: return
        ArcMenus.beginDialogFlow(player)
        val visit = active.menu.beginPending(player)
        request(player, { store, id -> store.enroll(id).thenApply { state -> state } }, refreshOnReject = true, pendingToken = visit) { current, _ ->
            current.npc?.refresh(player)
            current.menu.finish(player, visit)
        }
    }

    /** Explicit self-only reset with a durable inventory preimage before any item is replaced. */
    fun restartSelf(player: Player) {
        if (!isTestAdmin(player)) { message(player, "<red>Повторный запуск теста доступен администратору."); return }
        val current = runtime ?: return
        val store = current.store ?: run {
            message(player, "<red>Хранилище начального квеста недоступно. Попробуй позже."); return
        }
        val playerId = player.uniqueId
        if (current.starterInventory.isPending(playerId)) {
            message(player, "<yellow>Повторный запуск уже готовится. Дождись завершения."); return
        }
        ArcMenus.beginDialogFlow(player)
        val visit = current.menu.beginPending(player)
        val sequence = (current.requests[playerId] ?: 0L) + 1L
        current.requests[playerId] = sequence
        var expectedRunId = current.players[playerId]?.runId
        fun valid(): Boolean = runtime === current && current.requests[playerId] == sequence &&
            current.players[playerId]?.runId == expectedRunId

        val operation = current.starterInventory.replace(player, ::valid) {
            enqueue(current, playerId) { store.enroll(playerId).thenApply { it } }.thenCompose { enrolled ->
                current.tasks.callSync {
                    if (!valid() || !player.isOnline || Bukkit.getPlayer(playerId) !== player) {
                        throw CancellationException("Onboarding restart was superseded")
                    }
                    current.players[playerId] = requireNotNull(enrolled)
                    expectedRunId = enrolled.runId
                    Unit
                }
            }
        }
        current.pending += operation
        operation.whenComplete { _, failure ->
            current.pending.remove(operation)
            if (failure != null && generateSequence(failure) { it.cause }.none {
                it is CancellationException || it is OnboardingStarterInventory.InventoryChanged ||
                    it is OnboardingStarterInventory.CraftingItemsPresent || it is OnboardingStarterInventory.AlreadyReplacing
            }) error("Could not replace initial quest inventory for {}", playerId, failure)
        }
        operation.whenCompleteSync(current.tasks) { backup, failure ->
            if (!valid() || !player.isOnline || Bukkit.getPlayer(playerId) !== player) return@whenCompleteSync
            current.npc?.refresh(player)
            val notice = when {
                failure == null -> {
                    info("Initial quest starter inventory applied for {}; backup={}", playerId, backup)
                    null
                }
                generateSequence(failure) { it.cause }.any { it is OnboardingStarterInventory.CraftingItemsPresent } ->
                    "Освободи ячейки создания предметов и повтори /arc onboarding restart."
                generateSequence(failure) { it.cause }.any { it is OnboardingStarterInventory.InventoryChanged } ->
                    "Инвентарь изменился во время подготовки. Предметы сохранены; повтори /arc onboarding restart."
                else -> "Не удалось заменить инвентарь. Подробности сохранены в журнале сервера; попробуй ещё раз."
            }
            current.menu.finish(player, visit, notice)
        }
    }

    fun stopSelf(player: Player) {
        val progress = runtime?.players?.get(player.uniqueId)
        if (progress == null) { message(player, "<yellow>Начальный квест у тебя не включён."); return }
        stop(player, progress.runId)
    }

    private fun accept(player: Player, runId: String) {
        val current = runtime ?: return
        if (!canAccess(player)) return
        val visit = current.menu.beginPending(player)
        val arrival = place(player, player.world.name, player.location.blockX, player.location.blockZ)?.takeIf { current.config.allowsQuestWorld(it.world) }
        request(player, { store, id ->
            val acceptance = if (runId == "new" && current.config.publicEnabled) {
                store.enroll(id).thenCompose { store.accept(id, it.runId) }
            } else store.accept(id, runId)
            acceptance.thenCompose { accepted ->
                if (accepted?.status == OnboardingStatus.ACTIVE && arrival != null) store.observe(id, accepted.runId, OnboardingMilestone.FIRST_RTP, arrival)
                else CompletableFuture.completedFuture(accepted)
            }
        }, refreshOnReject = true, pendingToken = visit) { active, progress ->
            if (progress == null || progress.status !in setOf(OnboardingStatus.ACTIVE, OnboardingStatus.COMPLETED)) { active.menu.finish(player, visit, "Квест изменился. Открой журнал ещё раз."); return@request }
            active.npc?.refresh(player)
            if (active.menu.isCurrent(player, visit)) {
                active.menu.forget(player.uniqueId)
                ArcMenus.closeDialog(player)
                if (questView(player)?.goal == OnboardingQuestGoal.TRAVEL) openGoal(player, OnboardingQuestGoal.TRAVEL)
            }
        }
    }

    private fun stop(player: Player, runId: String) {
        if (!isTestAdmin(player) && !canAccess(player)) return
        val active = runtime ?: return
        val visit = active.menu.beginPending(player)
        request(player, { store, id -> store.stop(id, runId) }, refreshOnReject = true, pendingToken = visit) { current, stopped ->
            if (stopped?.status != OnboardingStatus.STOPPED) { current.menu.finish(player, visit, "Квест изменился. Открой журнал ещё раз."); return@request }
            current.npc?.refresh(player)
            current.menu.finish(player, visit, "Начальный квест выключен для тебя. Вернуть тест: /arc onboarding test")
        }
    }

    private fun openGoal(player: Player, goal: OnboardingQuestGoal) {
        if (!canAccess(player)) return
        when (goal) {
            OnboardingQuestGoal.TRAVEL -> {
                val current = runtime ?: return
                val runId = current.players[player.uniqueId]?.runId ?: return
                OnboardingWorldDialog { viewer, world ->
                    if (runtime === current && current.players[viewer.uniqueId]?.runId == runId &&
                        questView(viewer)?.goal == OnboardingQuestGoal.TRAVEL && current.config.allowsQuestWorld(world)) {
                        viewer.performCommand("arc rtp $world")
                    }
                }.open(player)
            }
            OnboardingQuestGoal.HOME -> HelpCenterModule.open(player, HelpCenterPage.TRAVEL)
            OnboardingQuestGoal.CLAIM -> ru.arc.landsui.LandsUiModule.open(player)
            OnboardingQuestGoal.BUILD -> { ArcMenus.closeDialog(player); player.performCommand("kit start") }
        }
    }

    private fun isTestAdmin(player: Player): Boolean = player.isOp || player.hasPermission("arc.admin")

    private fun confirmArrival(player: Player, progress: OnboardingProgress?) {
        val current = runtime ?: return
        if (progress?.status == OnboardingStatus.ACTIVE && OnboardingMilestone.FIRST_RTP !in progress.milestones &&
            current.config.allowsQuestWorld(player.world.name)) {
            observe(player, OnboardingMilestone.FIRST_RTP, place(player, player.world.name, player.location.blockX, player.location.blockZ))
        }
    }

    fun recordFirstRtp(
        player: Player,
        location: Location,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.FIRST_RTP_COMPLETE,
            ProductFeature.RTP,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, location.world?.name, location.blockX, location.blockZ) ?: return
        observe(player, OnboardingMilestone.FIRST_RTP, place)
    }

    fun recordHomeCreated(
        player: Player,
        worldName: String,
        blockX: Int,
        blockZ: Int,
        verifiedFoothold: Boolean,
        expectedRunId: String? = currentRunId(player.uniqueId),
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.HOME_CREATED,
            ProductFeature.HOMES,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, worldName, blockX, blockZ) ?: return
        if (expectedRunId != null && expectedRunId == currentRunId(player.uniqueId)) {
            observe(player, OnboardingMilestone.HOME_CREATED, place, verifiedFoothold)
        }
    }

    fun recordLandClaimed(
        player: Player,
        worldName: String,
        chunkX: Int,
        chunkZ: Int,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.LAND_CLAIMED,
            ProductFeature.LANDS,
            ProductEntryPoint.GAMEPLAY,
        )
        val place =
            runCatching { OnboardingPlace.fromChunk(worldName, chunkX, chunkZ) }
                .onFailure { failure -> error("Could not normalize Lands onboarding place for {}", player.name, failure) }
                .getOrNull() ?: return
        observe(player, OnboardingMilestone.LAND_CLAIMED, place)
    }

    override fun record(
        playerId: UUID,
        stage: OnboardingBuildProgressService.Stage,
        world: String,
        blockX: Int,
        blockZ: Int,
        operationId: String?,
    ): CompletionStage<Void> {
        check(Bukkit.isPrimaryThread()) { "Book progress must be captured on the Paper thread" }
        val current = runtime ?: return CompletableFuture.completedFuture(null)
        val store = current.store ?: return CompletableFuture.failedFuture(IllegalStateException("Quest storage unavailable"))
        if (!current.config.allowsQuestWorld(world)) return CompletableFuture.completedFuture(null)
        val place = OnboardingPlace.fromBlock(world, blockX, blockZ)
        val milestone = when (stage) {
            OnboardingBuildProgressService.Stage.BOOK_OPENED -> OnboardingMilestone.BUILD_BOOK_OPENED
            OnboardingBuildProgressService.Stage.BUILD_STARTED -> OnboardingMilestone.AUTOBUILD_STARTED
            OnboardingBuildProgressService.Stage.BUILD_COMPLETED -> OnboardingMilestone.AUTOBUILD_COMPLETE
        }
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
        val operation: (OnboardingStore, UUID) -> CompletableFuture<OnboardingProgress?> = { repository, id ->
            repository.load(id).thenCompose { state ->
                if (state?.status != OnboardingStatus.ACTIVE) CompletableFuture.completedFuture(state)
                else repository.observe(id, state.runId, milestone, place, operationId = operationId)
            }
        }
        if (player == null) {
            val pending = enqueue(current, playerId) { operation(store, playerId) }
            pending.whenComplete { _, _ -> current.tasks.runSync {
                if (Bukkit.getPlayer(playerId) == null && current.tails[playerId] === pending) current.tails.remove(playerId)
            } }
            return pending.thenApply { null }
        }
        return request(player, operation, refreshOnReject = true) { active, _ ->
            active.npc?.refresh(player)
        }.thenApply { null }
    }

    private fun observe(
        player: Player,
        milestone: OnboardingMilestone,
        place: OnboardingPlace?,
        verifiedFoothold: Boolean = false,
    ) {
        val current = runtime ?: return
        val progress = current.players[player.uniqueId] ?: return
        if (current.invalidPlaytimes[player.uniqueId] == progress.runId) return
        if (progress.status != OnboardingStatus.ACTIVE || !current.config.allowsQuestWorld(place?.world ?: player.world.name)) return
        val before = progress.milestones
        request(player, { store, id -> store.observe(id, progress.runId, milestone, place, verifiedFoothold) }, refreshOnReject = true) { active, updated ->
            if (updated == null || updated.runId != progress.runId) { active.npc?.refresh(player); return@request }
            active.npc?.refresh(player)
            if (OnboardingMilestone.FOOTHOLD_COMPLETE in updated.milestones && OnboardingMilestone.FOOTHOLD_COMPLETE !in before) {
                MetricsModule.recordProductOutcome(player, ProductOutcome.FOOTHOLD_COMPLETE, ProductFeature.LANDS, ProductEntryPoint.GAMEPLAY)
            }
        }
    }

    /** Serializes this player's Redis requests without blocking the gameplay thread. */
    private fun request(
        player: Player,
        operation: (OnboardingStore, UUID) -> CompletableFuture<OnboardingProgress?>,
        refreshOnReject: Boolean = false,
        pendingToken: Long? = null,
        complete: (Runtime, OnboardingProgress?) -> Unit,
    ): CompletableFuture<OnboardingProgress?> {
        val current = runtime ?: return CompletableFuture.completedFuture(null)
        val store = current.store
        if (store == null) {
            if (pendingToken != null) current.menu.finish(player, pendingToken, "Хранилище квеста недоступно. Попробуй позже.")
            else message(player, "<red>Хранилище начального квеста недоступно. Попробуй позже.")
            return CompletableFuture.failedFuture(IllegalStateException("Quest storage unavailable"))
        }
        val playerId = player.uniqueId
        val sequence = (current.requests[playerId] ?: 0L) + 1L
        current.requests[playerId] = sequence
        val pending = enqueue(current, playerId) {
            operation(store, playerId).thenCompose { result ->
                if (result == null && refreshOnReject) store.load(playerId) else CompletableFuture.completedFuture(result)
            }
        }
        pending.whenCompleteSync(current.tasks) { progress, failure ->
            if (runtime !== current || !player.isOnline || Bukkit.getPlayer(playerId) !== player) return@whenCompleteSync
            if (current.requests[playerId] != sequence) {
                if (pendingToken != null && current.menu.isCurrent(player, pendingToken)) open(player)
                return@whenCompleteSync
            }
            if (failure != null) {
                if (pendingToken != null) current.menu.finish(player, pendingToken, "Не удалось сохранить квест. Попробуй ещё раз.")
                else message(player, "<red>Не удалось сохранить квест. Прогресс не подтверждён, попробуй ещё раз.")
                return@whenCompleteSync
            }
            if (progress == null) current.players.remove(playerId) else current.players[playerId] = progress
            complete(current, progress)
            current.players[playerId]?.let { updated ->
                samplePlaytime(current, player)
                reconcileRewards(current, player, updated)
            }
        }
        return pending
    }

    /** Native counters advance only while playing; absolute high-water samples are safe to repeat. */
    private fun samplePlaytime(current: Runtime, player: Player, force: Boolean = false) {
        val store = current.playtime ?: return
        val progress = current.players[player.uniqueId]?.takeIf { it.status == OnboardingStatus.ACTIVE } ?: return
        if (current.invalidPlaytimes[player.uniqueId] == progress.runId) {
            expireActiveRun(current, player, progress.runId)
            return
        }
        val server = ARC.serverName?.takeIf(String::isNotBlank) ?: return
        val key = "${player.uniqueId}:${progress.runId}"
        val previous = current.playtimeTails[key]
        if (!force && previous?.isDone == false) return
        val counter = runCatching { player.getStatistic(Statistic.PLAY_ONE_MINUTE) }.getOrElse { failure ->
            if (current.playtimeFailures.add(player.uniqueId)) warn("Initial quest native playtime unavailable for {}", player.uniqueId, failure)
            return
        }
        val pending = (previous?.handle { _, _ -> Unit } ?: CompletableFuture.completedFuture(Unit))
            .thenCompose { store.sample(player.uniqueId, progress.runId, server, counter) }
        current.playtimeTails[key] = pending
        current.pending += pending
        pending.whenComplete { _, _ ->
            current.playtimeTails.remove(key, pending)
            current.pending.remove(pending)
        }
        pending.whenCompleteSync(current.tasks) { state, failure ->
            if (runtime !== current || current.players[player.uniqueId]?.runId != progress.runId) return@whenCompleteSync
            if (failure != null || state == null) {
                if (current.playtimeFailures.add(player.uniqueId)) warn("Initial quest playtime was not confirmed for {}", player.uniqueId, failure)
                if (current.players[player.uniqueId]?.status == OnboardingStatus.ACTIVE &&
                    generateSequence(failure) { it.cause }.any { it is OnboardingPlaytime.CounterMovedBackwards }) {
                    current.invalidPlaytimes[player.uniqueId] = progress.runId
                    expireActiveRun(current, player, progress.runId)
                }
                return@whenCompleteSync
            }
            current.playtimeFailures.remove(player.uniqueId)
            current.played[player.uniqueId] = progress.runId to state
            if (state.expired) expireActiveRun(current, player, progress.runId)
        }
    }

    private fun expireActiveRun(current: Runtime, player: Player, runId: String) {
        val key = "${player.uniqueId}:$runId"
        if (!current.expiring.add(key)) return
        enqueue(current, player.uniqueId) { requireNotNull(current.store).expireActive(player.uniqueId, runId) }
            .whenCompleteSync(current.tasks) { updated, failure ->
                current.expiring.remove(key)
                if (failure == null && runtime === current && updated != null && current.players[player.uniqueId]?.runId == runId) {
                    current.players[player.uniqueId] = updated
                    current.npc?.refresh(player)
                }
            }
    }

    private fun reconcileRewards(current: Runtime, player: Player, progress: OnboardingProgress) {
        val rewards = current.rewards ?: return
        val pending = rewards.reconcile(player, progress,
            Math.multiplyExact(current.config.coinsPerStep, 100L), Math.multiplyExact(current.config.tokensPerStep, 100L))
        current.pending += pending
        pending.whenComplete { _, failure ->
            current.pending.remove(pending)
            if (failure != null) error("Initial quest reward settlement failed for {}", player.uniqueId, failure)
        }
    }

    /** One durable FIFO shared by online actions and construction callbacks for offline owners. */
    private fun enqueue(
        current: Runtime,
        playerId: UUID,
        operation: () -> CompletableFuture<OnboardingProgress?>,
    ): CompletableFuture<OnboardingProgress?> {
        val previous = current.tails[playerId] ?: CompletableFuture.completedFuture(null)
        val pending = previous.handle { _, _ -> Unit }.thenCompose { operation() }
        current.tails[playerId] = pending
        current.pending += pending
        pending.whenComplete { _, failure ->
            current.pending.remove(pending)
            if (failure != null) error("Initial quest operation failed for {}", playerId, failure)
        }
        return pending
    }

    private fun place(player: Player, worldName: String?, blockX: Int, blockZ: Int): OnboardingPlace? =
        runCatching { OnboardingPlace.fromBlock(requireNotNull(worldName), blockX, blockZ) }
            .onFailure { failure -> error("Could not normalize initial quest place for {}", player.name, failure) }.getOrNull()

    private fun message(player: Player, text: String) = player.sendMessage(TextUtil.mm(text))
}
