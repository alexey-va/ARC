package ru.arc.dungeons

import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.ModuleRuntimeStatus
import ru.arc.core.PaperArcRuntime
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.whenCompleteSync
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.dungeons.api.ArcDungeonsService
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.dungeons.menu.DungeonsMenuSchema
import ru.arc.eliteloot.EliteLootModule
import ru.arc.eliteloot.LostLootModule
import ru.arc.enchanting.EliteEnchantingModule
import ru.arc.hooks.elitemobs.*
import ru.arc.hooks.elitemobs.arena.NativeArenaLevels
import ru.arc.hooks.lootchest.LootChestListener
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.staffspells.ArcStaffSpellServiceProvider
import ru.arc.staffspells.StaffSpellsModule
import ru.arc.staffspells.api.ArcStaffSpellService
import ru.arc.sync.EmSync
import java.nio.file.Path
import java.util.logging.Level
import java.util.concurrent.CompletableFuture

open class ArcDungeonsPlugin : JavaPlugin() {
    companion object { lateinit var instance: ArcDungeonsPlugin; private set }
    lateinit var legacyDataPath: Path
        private set
    lateinit var serverName: String
        private set
    internal var emHook: EMHook? = null
        private set
    internal var dungeonQol: EMDungeonQol? = null
        private set
    internal var emSync: EmSync? = null
        private set
    internal var redisManager: PaperRedisClient? = null
        private set
    private var redisGeneration = Long.MIN_VALUE
    private var spells: ArcStaffSpellServiceProvider? = null
    private var sharedSkillXp: DungeonSharedSkillXp? = null
    internal var progression: DungeonProgression? = null
        private set
    private var mastery: DungeonMasteryEffects? = null
    private var passiveClasses: PassiveClassCompatibility? = null
    private var arenaLevels: NativeArenaLevels? = null
    private var nativeInitializationListener: Listener? = null
    private var retiredSync: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    private var awaitingSync: Any? = null
    private var runtime: PaperPluginRuntime? = null
    internal val taskScope get() = requireNotNull(runtime).tasks
    private var metrics: ArcMetricsScope? = null
    @Volatile private var health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
    private val nativeListeners = mutableListOf<Listener>()
    private var background: BoundedAsyncExecutor? = null
    private var installedGlobals = false
    private var lifecycleDrain = CompletableFuture.completedFuture(Unit)
    private var lifecycleEpoch = 0L
    private var stopped = true
    internal lateinit var nativeWork: BudgetedTaskQueue
        private set

    /** Captured lifecycle worker for detached preparation; callers own late candidates through drain. */
    internal fun <T> prepareBackground(operation: () -> T): CompletableFuture<T> =
        background?.takeUnless { stopped }?.submit(operation)
            ?: CompletableFuture.failedFuture(IllegalStateException("ArcDungeons is stopping"))

    internal fun closeCompletion(): CompletableFuture<Unit> = lifecycleDrain.copy()

    override fun onLoad() { instance = this }

    override fun onEnable() {
        val host = server.servicesManager.load(ArcDungeonConfiguration::class.java)
        if (host == null) {
            logger.warning("ArcDungeons requires the FULL ARC runtime profile; disabling before initialization")
            server.pluginManager.disablePlugin(this)
            return
        }
        stopped = false
        installedGlobals = false
        val epoch = ++lifecycleEpoch
        health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
        val lifecycle = PaperPluginRuntime(this, scheduler = BukkitTaskScheduler(this)).also { runtime = it; it.start() }
        fun start(failure: Throwable?) {
            if (stopped || lifecycleEpoch != epoch) return
            try {
                if (failure != null) throw failure
                background = BoundedAsyncExecutor("arc-dungeons-background")
                PaperArcRuntime.installScheduling(this)
                installedGlobals = true
                nativeWork = BudgetedTaskQueue(lifecycle.tasks)
                lifecycle.registerHealth("modules") { health }
                prepareHost(host, initial = true)
            } catch (startupFailure: Throwable) {
                logger.log(Level.SEVERE, "Could not start ArcDungeons", startupFailure)
                server.pluginManager.disablePlugin(this)
            }
        }
        // Re-enable can wait for the old captured native tail; fresh startup never schedules a hop.
        if (lifecycleDrain.isDone) start(runCatching { lifecycleDrain.join() }.exceptionOrNull())
        else lifecycleDrain.whenCompleteSync(lifecycle.tasks) { _, failure -> start(failure) }
    }

    private fun prepareHost(host: ArcDungeonConfiguration, initial: Boolean) {
        val lifecycle = requireNotNull(runtime)
        check(host.ready().isDone) { "ARC must finish installation before ArcDungeons enables" }
        host.ready().join()
        health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
        legacyDataPath = host.legacyDataPath()
        serverName = host.serverName()
        val redisGenerationId = host.sharedRedisGeneration()
        val replaceRedis = redisGenerationId != redisGeneration
        val redisAvailable = host.sharedRedisAvailable()
        val service = server.servicesManager.load(ru.arc.paper.api.ArcRedisService::class.java)
        val consumerName = name
        val (menus, candidateRedis) = checkNotNull(background).submit {
            if (!initial) ConfigManager.reloadAll()
            mergeDefaults()
            ConfigManager.of(legacyDataPath, "modules/logging.yml")
            ConfigManager.of(legacyDataPath, "modules/misc.yml")
            ConfigManager.of(legacyDataPath.resolve("lootchests"), "bosses.yml")
            val menus = DungeonsMenuSchema.prepare(legacyDataPath)
            menus to if (replaceRedis && redisAvailable) PaperRedisClient.openPrimary(checkNotNull(service), consumerName) else null
        }.join()
        if (replaceRedis) {
            try {
                closeNative()
                retiredSync.join() // Native capture/cancellation is complete; this is storage-only.
                redisManager?.close()
            } catch (failure: Throwable) {
                runCatching { candidateRedis?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            redisManager = candidateRedis
            redisGeneration = redisGenerationId
        }
        if (initial) attachPreparedRuntime(menus)
        else {
            DungeonsMenus.reload(menus)
            reloadNative()
            ModuleRegistry.reloadAll()
            reportReadyAfterModules(lifecycle)
        }
    }

    private fun attachPreparedRuntime(menus: ru.arc.config.Config) {
        val lifecycle = requireNotNull(runtime)
        ArcLogging.install(PaperLoggingPlatform("ArcDungeons", "ArcDungeons"), LoggingConfigSource {
            ConfigManager.of(legacyDataPath, "modules/logging.yml")
        })
        metrics = PaperArcMetrics.open(this, "arc-dungeons")
        DungeonsMenus.initialize(this, legacyDataPath, menus)
        if (server.pluginManager.isPluginEnabled("EliteMobs")) {
            nativeInitializationListener = EliteMobsLifecycleListener {
                // EliteMobs soft reload replaces its runtime; release the old bindings first.
                closeNative()
                reloadNative()
                publishModuleMetrics()
            }.also { server.pluginManager.registerEvents(it, this) }
        }
        spells = ArcStaffSpellServiceProvider(this) { StaffSpellsModule.activeController() }
            .also(StaffSpellsModule::installDuelAccess)
        PaperArcRuntime.installModuleLifecycleReporting({ logger.info(it) }, { message, failure ->
            logger.log(java.util.logging.Level.SEVERE, message, failure)
        })
        ModuleRegistry.registerAll(EliteLootModule, EliteEnchantingModule, StaffSpellsModule, LostLootModule)
        reloadNative()
        ModuleRegistry.initAll()
        server.servicesManager.register(ArcStaffSpellService::class.java, requireNotNull(spells), this, ServicePriority.Normal)
        server.servicesManager.register(ArcDungeonsService::class.java, DungeonsServiceProvider(this), this, ServicePriority.Normal)
        val command = EMDungeonCommand(ConfigManager.of(legacyDataPath, "modules/elitemobs.yml"))
        for (name in listOf("dungeon", "dungeonstart", "dungeonsave", "dungeonsaves")) {
            getCommand(name)?.let { it.setExecutor(command); it.tabCompleter = command }
        }
        reportReadyAfterModules(lifecycle)
    }

    private fun reportReadyAfterModules(lifecycle: PaperPluginRuntime) {
        val failure = runCatching { LostLootModule.ready().join() }.exceptionOrNull()
        if (failure != null) logger.log(Level.WARNING, "Lost loot mailbox is unavailable; local capture retains its outbox", failure)
        publishModuleMetrics()
        lifecycle.ready("modules" to effectiveModuleStatuses().count { it.ready })
        lifecycle.reportHealthEvery(1_200L)
    }

    private fun mergeDefaults() {
        for (resource in listOf("modules/elitemobs.yml", "modules/elitemobs-wormholes.yml",
            "modules/elite-loot.yml", "modules/staff-spells.yml", "modules/enchanting.yml", "modules/arena-levels.yml",
            "guis/eliteloot.yml", "guis/menus.yml")) {
            ConfigManager.of(legacyDataPath, resource).mergeMissingFromBundled(resource)
            if (resource.startsWith("modules/")) ConfigManager.ofModule(legacyDataPath, resource.removePrefix("modules/"))
        }
    }

    internal fun reloadDungeons() {
        if (::nativeWork.isInitialized) nativeWork.close()
        runtime?.reload()
        runtime?.let { nativeWork = BudgetedTaskQueue(it.tasks) }
        awaitingSync = null
        val host = requireNotNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))
        prepareHost(host, initial = false)
    }

    private fun publishModuleMetrics() {
        val statuses = effectiveModuleStatuses()
        health = RuntimeHealthContribution(
            state = if (statuses.all { it.ready }) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
            dependencies = statuses.associate { it.name.lowercase(java.util.Locale.ROOT) to it.ready },
        )
        metrics?.gauge("arc_dungeons_native_ready", "Native dungeon integration initialized", emptyMap())
            ?.set(if (emHook != null && server.pluginManager.isPluginEnabled("EliteMobs")) 1.0 else 0.0)
        for (status in statuses) {
            metrics?.gauge("arc_dungeons_module_ready", "ArcDungeons module readiness",
                mapOf("module" to status.name))?.set(if (status.ready) 1.0 else 0.0)
        }
    }

    private fun effectiveModuleStatuses() = moduleStatusesWithLostLootReadiness(
        ModuleRegistry.getRuntimeStatuses(), LostLootModule.isReady,
    )

    private fun reloadNative() {
        if (!server.pluginManager.isPluginEnabled("EliteMobs")) {
            closeNative()
            return
        }
        // softdepend orders onEnable calls, but EliteMobs finishes initialization asynchronously.
        if (!EliteMobsLifecycleListener.isReady()) return

        attemptOptionalNativeIntegration(
            initialize = {
                // Keep the candidate reachable before reload so rollback owns partial state.
                val hook = emHook ?: EMHook().also { emHook = it }
                hook.reload()
                if (passiveClasses == null) {
                    PassiveClassCompatibility(
                        this,
                        openClasses = { player -> dungeonQol?.action(player, "classes") },
                        openForm = { player, id, atTrainer -> dungeonQol?.openClass(player, id, atTrainer) },
                    ).also { passiveClasses = it }.start()
                }
                val levels = arenaLevels
                if (levels == null) {
                    NativeArenaLevels(this, ConfigManager.of(legacyDataPath, "modules/arena-levels.yml"))
                        .also { arenaLevels = it }.install()
                } else levels.reload()
                if (dungeonQol == null) initializeDungeonQol()
                synchronizeEliteMobs()
            },
            rollback = ::closeNative,
            onFailure = ::logNativeStartupFailure,
        )
    }

    private fun initializeDungeonQol() {
        val sidebar = requireNotNull(server.servicesManager.load(ArcSidebarService::class.java))
            .register(this, "dungeon", ArcSidebarPriorities.DUNGEON)
        val qol = try {
            EMDungeonQol(sidebar = sidebar)
        } catch (failure: Throwable) {
            try {
                sidebar.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        dungeonQol = qol
        listen(EMListener())
        val progressionOwner = DungeonProgression(sync = { emSync },
            text = { key, fallback -> qol.text("progression.$key", fallback) },
        ).also { progression = it }
        sharedSkillXp = DungeonSharedSkillXp(
            recipient = DungeonSharedSkillRouting::target,
            ready = { player -> emSync?.isReady(player.uniqueId) ?: !eliteSyncEnabled() },
            awarded = progressionOwner::record,
        ).also(::listen)
        listen(progressionOwner)
        mastery = DungeonMasteryEffects(
            inDungeon = qol::masteryArea,
            ready = { player -> emSync?.isReady(player.uniqueId) == true },
        ).also {
            listen(it)
            it.start()
            StaffSpellsModule.installMasteryCooldown(it::staffCooldownTicks)
        }
        listen(qol.teleporter)
        listen(DungeonCaseRewards)
        listen(qol)
        listen(ElitePickupListener())
        if (server.pluginManager.isPluginEnabled("CMI")) listen(DungeonAdminExtractor())
        if (server.pluginManager.isPluginEnabled("LootChest")) {
            listen(LootChestListener(ConfigManager.of(legacyDataPath.resolve("lootchests"), "bosses.yml")))
        }
        qol.startAutosaves()
    }

    private fun synchronizeEliteMobs() {
        val syncEnabled = eliteSyncEnabled()
        if (syncEnabled && emSync == null) {
            // A reload must not read Redis ahead of the retired owner's final accepted snapshot.
            val retiring = retiredSync
            if (!retiring.isDone) {
                val request = Any()
                awaitingSync = request
                val generation = redisGeneration
                val scope = taskScope
                retiring.whenComplete { _, failure ->
                    scope.runSync {
                        if (awaitingSync !== request) return@runSync
                        awaitingSync = null
                        if (failure != null) logger.log(Level.WARNING, "Previous EliteMobs snapshot failed before reload", failure)
                        // Deferred startup needs the same partial-owner rollback as initial startup.
                        if (isEnabled && redisGeneration == generation && emHook != null) reloadNative()
                    }
                }
                return
            }
            awaitingSync = null
            EmSync(this, serverName).also {
                // Keep the candidate reachable so rollback can release a lease if start fails.
                emSync = it
                it.start()
            }
        }
        if (!syncEnabled) {
            emSync?.let(::retireSync)
            emSync = null
        }
    }

    private fun eliteSyncEnabled(): Boolean = redisManager != null &&
        ConfigManager.of(legacyDataPath, "modules/misc.yml").bool("sync.em", true)

    private fun retireSync(sync: EmSync) {
        sync.close()
        retiredSync = CompletableFuture.allOf(retiredSync, sync.closeCompletion())
    }

    private fun listen(listener: Listener) {
        nativeListeners += listener
        server.pluginManager.registerEvents(listener, this)
    }

    private fun closeNative(): Throwable? {
        val listeners = nativeListeners.toList()
        val qol = dungeonQol
        val hook = emHook
        val sync = emSync
        val sharedXp = sharedSkillXp
        val progressionOwner = progression
        val masteryOwner = mastery
        val passiveOwner = passiveClasses
        val arenaOwner = arenaLevels

        // Completion rights must be captured while the old synchronized native state is reachable.
        runCatching { progressionOwner?.flushCompletions() }.onFailure {
            logger.log(Level.SEVERE, "Could not capture pending dungeon completion books before shutdown", it)
        }

        // Clear fields before cleanup so every caller sees a consistently detached state,
        // even if individual close operations fail.
        nativeListeners.clear()
        dungeonQol = null
        emHook = null
        emSync = null
        sharedSkillXp = null
        progression = null
        mastery = null
        passiveClasses = null
        arenaLevels = null
        awaitingSync = null
        StaffSpellsModule.installMasteryCooldown(null)

        val actions = mutableListOf<() -> Unit>()
        listeners.forEach { listener -> actions.add { HandlerList.unregisterAll(listener) } }
        sharedXp?.let { actions.add { it.close() } }
        progressionOwner?.let { actions.add { it.close() } }
        masteryOwner?.let { actions.add { it.close() } }
        arenaOwner?.let { actions.add { it.close() } }
        passiveOwner?.let { actions.add { it.close() } }
        qol?.let { actions.add { it.close() } }
        hook?.let { actions.add { it.close() } }
        sync?.let { actions.add { retireSync(it) } }
        val failure = closeNativeResources(actions)
        if (failure != null) logger.log(Level.SEVERE, "One or more EliteMobs resources could not be closed", failure)
        return failure
    }

    private fun logNativeStartupFailure(failure: Throwable) {
        logger.log(
            Level.SEVERE,
            "EliteMobs integration is unavailable; ArcDungeons remains enabled and /arc reload will retry",
            failure,
        )
    }

    override fun onDisable() {
        if (stopped) return
        stopped = true
        ++lifecycleEpoch
        val lifecycle = runtime
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        attempt { lifecycle?.tasks?.close() }
        var lostLootTail = CompletableFuture.completedFuture<Void>(null)
        var lostLootStorage = CompletableFuture.completedFuture<Void>(null)
        if (installedGlobals) {
            if (::nativeWork.isInitialized) attempt { nativeWork.close() }
            attempt { nativeInitializationListener?.let { HandlerList.unregisterAll(it) } }
            nativeInitializationListener = null
            attempt { server.servicesManager.unregisterAll(this) }
            attempt { spells?.close() }
            spells = null
            attempt { StaffSpellsModule.installDuelAccess(null) }
            attempt { ModuleRegistry.shutdownAll() }
            lostLootTail = LostLootModule.drained()
            lostLootStorage = LostLootModule.storageDrained()
            closeNative()?.let { problem -> attempt { throw problem } }
            attempt { DungeonsMenus.close() }
            attempt { metrics?.close() }
            metrics = null
            attempt { ru.arc.core.Tasks.reset() }
        }
        attempt { lifecycle?.close() }
        runtime = null
        val oldRedis = redisManager
        redisManager = null
        redisGeneration = Long.MIN_VALUE
        val syncDrain = retiredSync
        val oldWorker = background
        background = null
        val storage = oldWorker?.closeAsync {
            try { syncDrain.join() } finally { oldRedis?.close() }
        } ?: CompletableFuture.completedFuture(Unit)
        // No future in this wait can need another native tick. Ticket retirement is observed separately.
        attempt { storage.join() }
        attempt { lostLootStorage.join() }
        val terminalFailure = failure
        lifecycleDrain = CompletableFuture.allOf(lifecycleDrain, lostLootTail).thenApply {
            terminalFailure?.let { throw it }
            Unit
        }
        lifecycleDrain.whenComplete { _, problem ->
            if (problem != null) logger.log(Level.SEVERE, "ArcDungeons cleanup failed", problem)
        }
        installedGlobals = false
    }

}

internal fun moduleStatusesWithLostLootReadiness(
    statuses: List<ModuleRuntimeStatus>,
    lostLootReady: Boolean,
) = statuses.map { status ->
    if (status.name == LostLootModule.name) status.copy(ready = status.ready && lostLootReady) else status
}

/** Runs one optional native integration step without allowing it to abort plugin bootstrap/reload. */
internal fun attemptOptionalNativeIntegration(
    initialize: () -> Unit,
    rollback: () -> Throwable?,
    onFailure: (Throwable) -> Unit,
): Boolean {
    try {
        initialize()
        return true
    } catch (failure: Throwable) {
        val cleanupFailure = try {
            rollback()
        } catch (rollbackFailure: Throwable) {
            rollbackFailure
        }
        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        onFailure(failure)
        return false
    }
}

/** Attempts every native cleanup action and returns failures as one suppressed exception tree. */
internal fun closeNativeResources(actions: List<() -> Unit>): Throwable? {
    var firstFailure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (failure: Throwable) {
            val first = firstFailure
            if (first == null) firstFailure = failure
            else if (first !== failure) first.addSuppressed(failure)
        }
    }
    return firstFailure
}
