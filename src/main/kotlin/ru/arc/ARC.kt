package ru.arc

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.ServicePriority
import ru.arc.chat.ChatModeConfig
import ru.arc.chat.IsolatedChatGlyphModule
import ru.arc.chat.SlimefunNetworkChatModule
import ru.arc.itemcatalog.ArcItemMaterializerBridge
import ru.arc.paper.api.ArcTelemetryProvider
import ru.arc.paper.api.ArcAfkProvider
import ru.arc.paper.api.ArcItemMaterializer
import ru.arc.paper.api.ArcSidebarService
import ru.arc.sidebar.SectionedSidebarService
import ru.arc.metrics.ArcTelemetryProviderBridge
import ru.arc.audit.autosell.AutoSellAuditModule
import ru.arc.audit.bank.BankAuditModule
import ru.arc.commands.XCommand
import ru.arc.commands.MainMenuCommand
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.LegacySubCommandExecutor
import ru.arc.commands.arc.subcommands.BuySubCommand
import ru.arc.commands.arc.subcommands.EliteLootSubCommand
import ru.arc.commands.arc.subcommands.GiveBoostSubCommand
import ru.arc.commands.arc.subcommands.HuntSubCommand
import ru.arc.commands.arc.subcommands.SoundFollowSubCommand
import ru.arc.commands.arc.subcommands.TestSubCommand
import ru.arc.commands.arc.subcommands.TreasuresSubCommand
import ru.arc.commands.chat.ChatModeAliasCommand
import ru.arc.commandhide.CommandHideModule
import ru.arc.citizens.NpcChunkTicketModule
import ru.arc.cleanup.EntityCleanupModule
import ru.arc.config.ConfigManager
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.LocationPoolConfig
import ru.arc.core.ModuleRegistry
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.dataPath
import ru.arc.core.modules.AiModule
import ru.arc.core.modules.AnnounceModule
import ru.arc.core.modules.AuditModule
import ru.arc.core.modules.BoardModule
import ru.arc.core.modules.ConfigModule
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.SharedServiceMetrics
import ru.arc.parkour.ArcParkourModule
import ru.arc.core.modules.CooldownModule
import ru.arc.core.modules.EconomyModule
import ru.arc.enchanting.EnchantingModule
import ru.arc.core.modules.HeadCacheModule
import ru.arc.core.modules.HooksModule
import ru.arc.core.modules.JoinMessagesModule
import ru.arc.core.modules.ChatModeModule
import ru.arc.core.modules.LeafDecayModule
import ru.arc.core.modules.LocationPoolModule
import ru.arc.core.modules.MobSpawnModule
import ru.arc.core.modules.NetworkModule
import ru.arc.core.modules.ParticleModule
import ru.arc.core.modules.PersonalLootModule
import ru.arc.core.modules.RedisModule
import ru.arc.core.modules.StoreModule
import ru.arc.core.modules.SyncModule
import ru.arc.core.modules.TreasureModule
import ru.arc.core.modules.XActionModule
import ru.arc.investigation.InvestigationModule
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.CmiAfkProviderBridge
import ru.arc.hooks.citizens.ArcNpcHologramModule
import ru.arc.gui.GuiDefaults
import ru.arc.gui.ArcMenus
import ru.arc.dialogdemo.DialogDemoModule
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.iteminfo.ItemInfoModule
import ru.arc.itemcatalog.ItemsCatalogModule
import ru.arc.itemlore.ItemLoreModule
import ru.arc.itemcatalog.CaseRewardIssueCommand
import ru.arc.landsui.LandsUiModule
import ru.arc.network.NetworkRegistry
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.sql.PaperSqlServiceProvider
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.metrics.PaperArcMetricsProvider
import ru.arc.coreplugin.ArcCoreServices
import ru.arc.ops.OpsHttpModule
import ru.arc.onboarding.OnboardingModule
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.packet.PaperVisualPacketRuntime
import ru.arc.restart.RestartModule
import ru.arc.slimefunmenu.SlimefunMenuAliasCommand
import ru.arc.slimefunmenu.SlimefunMenuCommand
import ru.arc.slimefunmenu.SlimefunMenuModule
import ru.arc.slimefunmenu.NetworkSpawnCommand
import ru.arc.travelanchors.TravelAnchorsModule
import ru.arc.rtp.RtpPlayerRegistry
import ru.arc.scheduled.ScheduledCommandsModule
import ru.arc.spy.CrossServerSpyModule
import ru.arc.sidebar.ArcBaseSidebar
import ru.arc.util.HeadTextureCache
import ru.arc.util.Logging
import ru.arc.util.Logging.consoleLog
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.xserver.PluginMessenger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException

/**
 * Main plugin class for ARC.
 *
 * Handles plugin lifecycle, module and command registration, and default config seeding.
 * All feature logic is delegated to modules in [ru.arc.core.modules].
 */

/**
 * Main plugin class for ARC.
 *
 * `open` is required so MockBukkit can create a ByteBuddy proxy subclass during tests.
 */
open class ARC : JavaPlugin() {
    // ==================== Instance Fields ====================

    var locationPoolConfig: LocationPoolConfig? = null

    internal lateinit var chunkTicketRegistry: PaperChunkTicketRegistry
        private set

    internal lateinit var sidebarService: SectionedSidebarService
        private set

    internal var visualPacketRuntime: PaperVisualPacketRuntime? = null
        private set

    private var baseSidebar: ArcBaseSidebar? = null
    internal lateinit var sqlService: PaperSqlServiceProvider
        private set
    internal lateinit var redisService: PaperRedisServiceProvider
        private set
    private var sqlConsumer: AutoCloseable? = null
    private var sqlDrain = CompletableFuture<Void>()
    private var metricsService: PaperArcMetricsProvider? = null
    private var sharedServiceMetrics: SharedServiceMetrics? = null
    private var coreServices: ArcCoreServices? = null
    private var completionTasks: LifecycleTaskScope? = null
    private var background: BoundedAsyncExecutor? = null
    private var installedGlobals = false
    private var lifecycleDrain = CompletableFuture.completedFuture(Unit)
    private var storageClose = CompletableFuture.completedFuture<Void>(null)
    private var nativeClose = CompletableFuture.completedFuture<Void>(null)
    private val hotload = ru.arc.paper.runtime.PaperPluginHotload(this) {
        stopRuntime()
        linkedMapOf("storage" to storageClose, "native" to nativeClose, "runtime" to lifecycleDrain)
    }
    private var lifecycleEpoch = 0L
    private var stopped = false
    @Volatile private var ready = CompletableFuture<Void>()
    private var pendingReload: CompletableFuture<Void>? = null
    private val cleanupMonitor = Any()
    private val pendingCleanup = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private var cleanupSealed = false
    private var cleanupFailure: Throwable? = null
    internal var materialTranslations: Map<String, String> = emptyMap()
        private set
    internal lateinit var nativeWork: BudgetedTaskQueue
        private set

    private data class PreparedConfiguration(
        val profile: ArcRuntimeProfile,
        val menus: ru.arc.config.Config?,
        val joinMessageDialog: ru.arc.config.Config?,
        val headCache: HeadTextureCache?,
        val translations: Map<String, String>,
    )

    /** Actual generation readiness; stable host providers are registered before dependents enable. */
    fun readiness(): CompletableFuture<Void> = if (stopped) {
        CompletableFuture.failedFuture(IllegalStateException("ARC is stopped"))
    } else ready.copy()

    internal fun closeCompletion(): CompletableFuture<Unit> = lifecycleDrain.copy()

    internal fun fullRuntimeReadiness(): CompletableFuture<Void> = readiness().thenApply {
        check(runtimeProfile == ArcRuntimeProfile.FULL) { "This ARC runtime profile does not provide FULL host services" }
        null
    }

    internal fun <T> submitBackground(action: () -> T): CompletableFuture<T> = checkNotNull(background).submit(action)
    internal fun backgroundOwner(): BoundedAsyncExecutor = checkNotNull(background)

    /** Fixed module resource owners retire even if ordinary work has filled the worker queue. */
    internal fun retireInBackground(action: () -> Unit): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        synchronized(cleanupMonitor) {
            if (cleanupSealed) return CompletableFuture.failedFuture(IllegalStateException("ARC cleanup admission is closed"))
            pendingCleanup.add {
                try { action(); result.complete(Unit) }
                catch (failure: Throwable) { result.completeExceptionally(failure); throw failure }
            }
        }
        // A rejected ordinary wake-up leaves the accepted cleanup for the reserved terminal drain.
        checkNotNull(background).submit(::drainCleanupOnWorker)
        return result
    }

    private fun drainCleanupOnWorker() {
        while (true) {
            val close = pendingCleanup.poll() ?: break
            try { close() } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
        }
    }

    private fun recordCleanupFailure(failure: Throwable) {
        if (cleanupFailure == null) cleanupFailure = failure
        else if (cleanupFailure !== failure) cleanupFailure?.addSuppressed(failure)
    }

    internal fun <T> whenCompleteOnMain(
        future: java.util.concurrent.CompletionStage<T>,
        callback: (T?, Throwable?) -> Unit,
    ) {
        completionTasks?.let { future.whenCompleteSync(it, action = callback) }
    }

    internal fun sharedServiceSnapshot(): Map<String, Int> = sharedServiceMetrics?.snapshot().orEmpty()

    /** Metrics recovery finishes asynchronously; only its current generation binds. */
    internal fun bindTelemetryRegistry(registry: io.micrometer.core.instrument.MeterRegistry?) {
        metricsService?.rebind(registry)
    }

    internal fun refreshSharedServiceMetrics() { sharedServiceMetrics?.refresh() }
    internal var tablist: ru.arc.tablist.ArcTablist? = null
        private set
    private var slimefunMenuAlias: Command? = null
    private var networkSpawnAlias: Command? = null
    private var previousNetworkSpawnCommand: Command? = null

    var runtimeProfile: ArcRuntimeProfile = ArcRuntimeProfile.FULL
        private set

    // ==================== Lifecycle ====================

    override fun onLoad() {
        plugin = this
        ru.arc.pvp.PvpModule.prepareLandsFlag(this)
    }

    override fun onEnable() {
        try { hotload.beginGeneration() } catch (failure: Throwable) {
            ready = CompletableFuture.failedFuture(failure)
            logger.log(java.util.logging.Level.SEVERE, "ARC startup refused: previous generation has not stopped cleanly", failure)
            server.pluginManager.disablePlugin(this)
            return
        }
        stopped = false
        installedGlobals = false
        ready = CompletableFuture()
        sqlDrain = CompletableFuture()
        val epoch = ++lifecycleEpoch
        completionTasks = LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(this))
        nativeWork = BudgetedTaskQueue(requireNotNull(completionTasks))
        fun start(failure: Throwable?) {
            if (stopped || lifecycleEpoch != epoch) return
            try {
                if (failure != null) throw failure
                hotload.register()
                beginRuntime()
            } catch (startupFailure: Throwable) {
                ready.completeExceptionally(startupFailure)
                logger.log(java.util.logging.Level.SEVERE, "ARC startup failed", startupFailure)
                server.pluginManager.disablePlugin(this)
            }
        }
        // Only an old native cleanup can need another tick. Ordinary startup is sequential.
        if (lifecycleDrain.isDone) start(runCatching { lifecycleDrain.join() }.exceptionOrNull())
        else lifecycleDrain.whenCompleteSync(requireNotNull(completionTasks)) { _, failure -> start(failure) }
    }

    private fun beginRuntime() {
        printBanner()
        background = BoundedAsyncExecutor("arc-background")
        cleanupFailure = null
        synchronized(cleanupMonitor) { cleanupSealed = false }
        installedGlobals = true
        PaperArcRuntime.installScheduling(this)
        val sharedServices = checkNotNull(server.servicesManager.load(ArcCoreServices::class.java)) {
            "ArcCore services are unavailable; install and enable ArcCore before ARC"
        }
        check(sharedServices.ready().isDone) { "ArcCore must finish installation before ARC enables" }
        sharedServices.ready().join()
        coreServices = sharedServices
        sqlService = sharedServices.sql
        sqlConsumer = PaperSqlServices.installConsumer(this, sqlDrain)
        redisService = sharedServices.redis
        val redisApi = checkNotNull(server.servicesManager.load(ru.arc.paper.api.ArcRedisService::class.java)) {
            "ArcCore Redis service is unavailable"
        }
        metricsService = sharedServices.metrics.also {
            sharedServiceMetrics = SharedServiceMetrics(it, sqlService, redisService)
        }
        visualPacketRuntime = sharedServices.visualPackets
        chunkTicketRegistry = PaperChunkTicketRegistry(this)
        val root = dataPath
        val owner = name
        val jobsAvailable = server.pluginManager.isPluginEnabled("Jobs")
        val scheduler = Tasks.scheduler
        // This preparation contains no native callbacks. Waiting is deliberate maintenance IO;
        // the same worker continues to own gameplay persistence after startup.
        val config = checkNotNull(background).submit {
            val loaded = prepareConfiguration(root, javaClass.classLoader, initial = true)
            if (loaded.profile != ArcRuntimeProfile.ISOLATED || ChatModeConfig.load(root).isolatedGlyphProtectionEnabled) {
                RedisModule.prepareOnWorker(root, redisApi, owner)
            }
            prepareRepositoriesOnWorker(loaded.profile, root, scheduler, jobsAvailable)
            loaded
        }.join()
        runtimeProfile = config.profile
        ru.arc.dungeons.DungeonHostServices.register(this)
        ru.arc.integrations.mounts.MountHostServices.register(this)
        ru.arc.integrations.contracts.ContractsHostServices.register(this)
        ru.arc.integrations.origin.OriginHostServices.register(this)
        attachPreparedRuntime(config)
        finishReadiness(ready, initial = true)
    }

    private fun attachPreparedRuntime(config: PreparedConfiguration) {
        runtimeProfile = config.profile
        headTextureCache = config.headCache
        materialTranslations = config.translations
        if (runtimeProfile == ArcRuntimeProfile.FULL) GuiDefaults.init(dataPath)
        if ((runtimeProfile == ArcRuntimeProfile.FULL || runtimeProfile == ArcRuntimeProfile.SLIMEFUN) && pluginMessenger == null) {
            pluginMessenger = PluginMessenger(networkRtpEnabled = runtimeProfile == ArcRuntimeProfile.FULL)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            ArcMenus.initialize(this, dataPath, requireNotNull(config.menus))
            ru.arc.misc.JoinMessageGuiFactory.attach(requireNotNull(config.joinMessageDialog))
        } else if (runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            ArcMenus.initializeDialogRuntime(this)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL || runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            sidebarService = SectionedSidebarService(this)
            server.servicesManager.register(ArcSidebarService::class.java, sidebarService, this, ServicePriority.Normal)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            RtpPlayerRegistry.initialize(dataPath)
        }
        registerModules()
        PaperArcRuntime.installModuleLifecycleReporting(
            consoleLog = { consoleLog(it) },
            logError = { msg, t -> error(msg, t) },
        )
        ModuleRegistry.initAll()
        bindTelemetryRegistry(MetricsModule.registry())

        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            server.servicesManager.register(ArcAfkProvider::class.java, CmiAfkProviderBridge, this, ServicePriority.Normal)
            server.servicesManager.register(ArcTelemetryProvider::class.java, ArcTelemetryProviderBridge, this, ServicePriority.Normal)
            server.servicesManager.register(ArcItemMaterializer::class.java, ArcItemMaterializerBridge, this, ServicePriority.Normal)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL || runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            baseSidebar = ArcBaseSidebar(this, sidebarService).also(ArcBaseSidebar::start)
            tablist = ru.arc.tablist.ArcTablist(this).also { it.start() }
        }
        // Start the single Redis subscription after ALL modules have registered their channels.
        // Calling init() multiple times (once per module) caused the subscription to be
        // constantly restarted and never complete its 1s startup delay.
        redisManager?.let {
            consoleLog("<dark_gray>[ARC]</dark_gray> <aqua>☁  Redis</aqua>  subscribing to <white>${it.getChannelCount()}</white> channels")
            it.init()
        }
        registerCommands()

    }

    private fun finishReadiness(generation: CompletableFuture<Void>, initial: Boolean) {
        val durableState = if (runtimeProfile == ArcRuntimeProfile.FULL) CompletableFuture.allOf(
            RtpPlayerRegistry.readiness(), ru.arc.onboarding.OnboardingService.readiness(),
            LocationPoolModule.readiness(), ru.arc.investigation.InvestigationModule.readiness(),
            ru.arc.scheduled.ScheduledCommandsManager.readiness(),
        ) else CompletableFuture.completedFuture(null)
        val prepared = CompletableFuture.allOf(durableState, OpsHttpModule.readiness())
        fun complete(failure: Throwable?) {
            if (ready !== generation || stopped) return
            if (failure != null) {
                generation.completeExceptionally(failure)
                error("ARC durable-state preparation failed", failure)
                if (initial) server.pluginManager.disablePlugin(this)
                return
            }
            generation.complete(null)
            if (initial) consoleLog("<dark_gray>[ARC]</dark_gray> <bold><green>✔  ARC is ready</green></bold>")
            else {
                ru.arc.integrations.mounts.mountService()?.reload()
                ru.arc.integrations.contracts.contractsService()?.reload()
                ru.arc.integrations.origin.originService()?.reload()
                ru.arc.dungeons.dungeonService()?.reload()
                info("ARC plugin reloaded")
            }
        }
        if (prepared.isDone) complete(runCatching { prepared.join() }.exceptionOrNull())
        else whenCompleteOnMain(prepared) { _, failure -> complete(failure) }
    }

    private fun printBanner() {
        val v = pluginMeta.version
        consoleLog("<dark_gray>  ┌──────────────────────────────────────┐</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <bold><aqua>    _    ____   ____   </aqua></bold><dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <bold><aqua>   / \\  |  _ \\ / ___|  </aqua></bold><dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <bold><aqua>  / _ \\ | |_) | |      </aqua></bold><dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <bold><aqua> / ___ \\|  _ <\\| |___   </aqua></bold><dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <bold><aqua>/_/   \\_\\_| \\_\\\\____|  </aqua></bold><dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  │</dark_gray>  <green>  version <bold>$v</bold></green>                 <dark_gray>│</dark_gray>")
        consoleLog("<dark_gray>  └──────────────────────────────────────┘</dark_gray>")
    }

    override fun onDisable() {
        try { hotload.prepare() } finally { hotload.close() }
    }

    private fun stopRuntime() {
        if (stopped) return
        stopped = true
        ++lifecycleEpoch
        var nativeFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                if (nativeFailure == null) nativeFailure = failure else if (nativeFailure !== failure) nativeFailure!!.addSuppressed(failure)
            }
        }
        ready.completeExceptionally(CancellationException("ARC is stopping"))
        pendingReload?.completeExceptionally(CancellationException("ARC is stopping"))
        pendingReload = null
        if (::nativeWork.isInitialized) attempt { nativeWork.close() }
        attempt { completionTasks?.close() }
        completionTasks = null
        val moduleDrains = mutableListOf<CompletableFuture<*>>()
        val nativeDrains = mutableListOf<CompletableFuture<*>>(lifecycleDrain)
        if (installedGlobals) {
            info("Stopping ARC plugin")
            attempt { server.servicesManager.unregisterAll(this) }
            attempt { unregisterSlimefunMenuAlias() }
            attempt { unregisterNetworkSpawnAlias() }
            if (runtimeProfile == ArcRuntimeProfile.FULL) attempt { Portal.removeAll() }
            attempt { ModuleRegistry.shutdownAllChecked() }
            attempt { RtpPlayerRegistry.shutdown() }
            moduleDrains += RtpPlayerRegistry.closeCompletion()
            moduleDrains += ru.arc.onboarding.OnboardingService.closeCompletion()
            moduleDrains += ru.arc.onboarding.OptionalOnboardingService.closeCompletion()
            moduleDrains += ru.arc.investigation.InvestigationModule.storageDrained()
            nativeDrains += ru.arc.investigation.InvestigationModule.drained()
            nativeDrains += HooksModule.drained()
            nativeDrains += ru.arc.backwings.BackWingsModule.drained()
            moduleDrains += ru.arc.scheduled.ScheduledCommandsManager.drained()
            attempt { sharedServiceMetrics?.close() }
            sharedServiceMetrics = null
            val retiredSqlConsumer = sqlConsumer
            sqlConsumer = null
            attempt { retiredSqlConsumer?.close() }
            attempt { metricsService?.rebind(null) }
            metricsService = null
            visualPacketRuntime = null
            coreServices = null
            attempt { baseSidebar?.close() }
            baseSidebar = null
            attempt { tablist?.close() }
            tablist = null
            if (::sidebarService.isInitialized) attempt { sidebarService.close() }
            if (runtimeProfile == ArcRuntimeProfile.FULL || runtimeProfile == ArcRuntimeProfile.SLIMEFUN) attempt { ArcMenus.close() }
            attempt { ru.arc.misc.JoinMessageGuiFactory.close() }
            if (::chunkTicketRegistry.isInitialized) attempt { chunkTicketRegistry.close() }
            attempt { pluginMessenger?.shutdown() }
            pluginMessenger = null
            attempt { GuiDefaults.reset() }
            synchronized(cleanupMonitor) { cleanupSealed = true }
            attempt { Tasks.reset() }
        }
        attempt { hotload.retireGameplayRegistrations() }
        val oldWorker = background
        background = null
        val storageDrain = oldWorker?.closeAsync {
            drainCleanupOnWorker()
            try { discardPreparedRepositoriesOnWorker() } catch (failure: Throwable) { recordCleanupFailure(failure) }
            cleanupFailure?.let { throw it }
        } ?: CompletableFuture.completedFuture(Unit)
        moduleDrains += storageDrain

        val failure = nativeFailure
        storageClose = CompletableFuture.allOf(*moduleDrains.toTypedArray())
        nativeClose = CompletableFuture.allOf(*nativeDrains.toTypedArray())
        val terminalSql = sqlDrain
        storageClose.whenComplete { _, problem ->
            val result = problem ?: failure
            if (result == null) terminalSql.complete(null) else terminalSql.completeExceptionally(result)
        }
        // ArcCore stays enabled: its native cleanup may need future ticks. Do not join it on Main.
        lifecycleDrain = CompletableFuture.allOf(storageClose, nativeClose,
            if (failure == null) CompletableFuture.completedFuture(Unit) else CompletableFuture.failedFuture(failure),
        ).handle { _, problem ->
            var result = problem
            for (close in listOf<() -> Unit>({ Logging.closeDiagnosticFile() }, { ru.arc.logging.ArcLogging.close() })) {
                try { close() } catch (loggingFailure: Throwable) {
                    if (result == null) result = loggingFailure else if (result !== loggingFailure) result.addSuppressed(loggingFailure)
                }
            }
            result?.let { throw it }
            Unit
        }
        lifecycleDrain.whenComplete { _, problem ->
            if (problem != null) logger.log(java.util.logging.Level.SEVERE, "ARC cleanup failed", problem)
        }
        installedGlobals = false
        info("ARC plugin disabled")
    }

    // ==================== Reload ====================

    /** Explicit maintenance: prepare serially, then bind native state before returning. */
    fun reload(): CompletableFuture<Void> {
        if (stopped) return CompletableFuture.failedFuture(IllegalStateException("ARC is stopped"))
        if (!ready.isDone || pendingReload != null) return CompletableFuture.failedFuture(IllegalStateException("ARC is already preparing"))
        val generation = CompletableFuture<Void>()
        pendingReload = generation
        try {
            val root = dataPath
            val resources = javaClass.classLoader
            val activeProfile = runtimeProfile
            val services = checkNotNull(coreServices)
            val redisApi = checkNotNull(server.servicesManager.load(ru.arc.paper.api.ArcRedisService::class.java))
            val owner = name
            val hasRedisModule = ModuleRegistry.getModules().any { it === RedisModule }
            val config = checkNotNull(background).submit {
                if (hasRedisModule) RedisModule.requireReloadCompatible(root)
                if (activeProfile == ArcRuntimeProfile.FULL) ru.arc.audit.AuditManager.requireReloadCompatible(root)
                val loaded = prepareConfiguration(root, resources, initial = false, activeProfile = activeProfile)
                if (activeProfile != ArcRuntimeProfile.ISOLATED || ChatModeConfig.load(root).isolatedGlyphProtectionEnabled) {
                    RedisModule.prepareOnWorker(root, redisApi, owner)
                }
                loaded
            }.join()
            services.reloadVisualPacketConfigAsync().join() // Host maintenance applies inline on Main.
            ready = generation
            if (config.profile != runtimeProfile) warn("Runtime profile changes require a server restart; keeping {}", runtimeProfile)
            if (runtimeProfile == ArcRuntimeProfile.FULL) {
                Portal.removeAll()
                ArcMenus.reload(requireNotNull(config.menus))
                ru.arc.misc.JoinMessageGuiFactory.attach(requireNotNull(config.joinMessageDialog))
            }
            ModuleRegistry.reloadAll()
            bindTelemetryRegistry(MetricsModule.registry())
            baseSidebar?.refresh()
            tablist?.refresh()
            redisManager?.init()
            finishReadiness(generation, initial = false)
        } catch (failure: Throwable) {
            generation.completeExceptionally(failure)
            error("ARC reload failed", failure)
        } finally { pendingReload = null }
        return generation.copy()
    }

    // ==================== Module Registration ====================

    private fun registerModules() {
        debug("Registering modules...")

        if (runtimeProfile == ArcRuntimeProfile.ISOLATED) {
            ModuleRegistry.registerAll(ConfigModule, OpsHttpModule, RestartModule, ItemInfoModule)
            if (ChatModeConfig.load(dataPath).isolatedGlyphProtectionEnabled) {
                ModuleRegistry.registerAll(RedisModule, IsolatedChatGlyphModule)
                info("Runtime profile isolated: local operations with chat glyph authorization")
            } else {
                info("Runtime profile isolated: Config, OpsHttp, Restart, ItemInfo only")
            }
            return
        }

        if (runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            ModuleRegistry.registerAll(
                ConfigModule,
                OpsHttpModule,
                RestartModule,
                ItemInfoModule,
                HooksModule,
                RedisModule,
                ChatModeModule,
                SlimefunNetworkChatModule,
            )
            if (ChatModeConfig.load(dataPath).isolatedGlyphProtectionEnabled) {
                ModuleRegistry.registerAll(IsolatedChatGlyphModule)
                info("Runtime profile slimefun: isolated chat glyph protection enabled")
            } else {
                info("Runtime profile slimefun: utility, shared chat mode, and player list")
            }
            ModuleRegistry.registerAll(SlimefunMenuModule, HelpCenterModule)
            return
        }

        ModuleRegistry.registerAll(
            // Core infrastructure (priority 10-29)
            RedisModule,
            NetworkModule,
            HooksModule,
            NpcChunkTicketModule,
            ArcNpcHologramModule,
            AiModule,
            EconomyModule,
            // Configuration (priority 30-49)
            ConfigModule,
            MetricsModule,
            ru.arc.metrics.telemetry.PlayerTelemetryModule,
            OpsHttpModule,
            LocationPoolModule,
            BoardModule,
            // Core features (priority 50-69)
            ParticleModule,
            CooldownModule,
            HeadCacheModule,
            AuditModule,
            AutoSellAuditModule,
            BankAuditModule,
            // Game features (priority 70-89)
            AnnounceModule,
            ScheduledCommandsModule,
            XActionModule,
            RestartModule,
            StoreModule,
            InvestigationModule,
            TreasureModule,
            EnchantingModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            EntityCleanupModule,
            TravelAnchorsModule,
            JoinMessagesModule,
            ChatModeModule,
            CrossServerSpyModule,
            ArcParkourModule,
            ItemsCatalogModule,
            ItemLoreModule,
            ru.arc.pvp.PvpModule,
            LandsUiModule,
            ru.arc.decorinteraction.DecorInteractionModule,
            ru.arc.survival.SurvivalGameplayModule,
            ru.arc.playerseat.PlayerSeatModule,
            ru.arc.barrierremover.BarrierRemoverModule,
            ru.arc.backwings.BackWingsModule,
            HelpCenterModule,
            ru.arc.homemarkers.HomeMarkersModule,
            ru.arc.graves.GravesModule,
            DialogDemoModule,
            CommandHideModule,
            OnboardingModule,
            ItemInfoModule,
            // Sync systems (priority 100)
            SyncModule,
        )
    }

    // ==================== Command Registration ====================

    private fun registerCommands() {
        debug("Registering commands...")

        val arcCommand = ArcCommand.INSTANCE
        registerCommand("arc", arcCommand, arcCommand)
        if (runtimeProfile == ArcRuntimeProfile.ISOLATED) {
            // Remove this plugin's unused labels and aliases, preserving other plugins.
            val commandMap = server.commandMap
            val inactive = commandMap.knownCommands.values.filterIsInstance<PluginCommand>()
                .filter { it.plugin === this && it.name != "arc" }.toSet()
            // Paper's Brigadier-backed entry iterator does not support removal.
            val labels = commandMap.knownCommands.filterValues { it in inactive }.keys.toList()
            labels.forEach { commandMap.knownCommands.remove(it) }
            inactive.forEach { it.unregister(commandMap) }
            return
        }
        if (runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            // Retain the utility menu and chat-mode aliases; keep FULL-only labels out.
            val commandMap = server.commandMap
            val preserved = setOf("arc", "menu", "skyblock", "g", "l")
            val inactive = commandMap.knownCommands.values.filterIsInstance<PluginCommand>()
                .filter { it.plugin === this && it.name !in preserved }.toSet()
            val labels = commandMap.knownCommands.filterValues { it in inactive }.keys.toList()
            labels.forEach { commandMap.knownCommands.remove(it) }
            inactive.forEach { it.unregister(commandMap) }
            ChatModeAliasCommand.register(this)
            registerCommand("menu", SlimefunMenuCommand, null)
            registerCommand("skyblock", SlimefunMenuCommand, SlimefunMenuCommand)
            registerSlimefunMenuAlias()
            registerNetworkSpawnAlias()
            return
        }
        unregisterOwnedPluginCommand("skyblock")
        registerCommand("x", XCommand, XCommand)
        ChatModeAliasCommand.register(this)
        registerCommand("menu", MainMenuCommand, null)
        registerCommand("arc-reward-issue", CaseRewardIssueCommand(), null)
        getCommand("graves")?.setTabCompleter { _, _, _, _ -> emptyList() }
        registerLegacyCommands()
    }

    private fun registerLegacyCommands() {
        val legacyCommands =
            mapOf(
                "treasure-hunt" to HuntSubCommand,
                "treasure-pool" to TreasuresSubCommand,
                "sound-follow" to SoundFollowSubCommand,
                "give-jobs-boost" to GiveBoostSubCommand,
                "arctest" to TestSubCommand,
                "eliteloot" to EliteLootSubCommand,
                "buy" to BuySubCommand,
            )

        for ((name, subCommand) in legacyCommands) {
            val bridge = LegacySubCommandExecutor(subCommand)
            registerCommand(name, bridge, bridge)
        }
    }

    private fun registerSlimefunMenuAlias() {
        val commandMap = server.commandMap
        if (commandMap.getCommand("mm") != null) {
            warn("Could not register /mm because another command already owns that label")
            return
        }
        val alias = SlimefunMenuAliasCommand()
        if (commandMap.register("arc", alias) && commandMap.getCommand("mm") === alias) {
            slimefunMenuAlias = alias
        } else {
            removeSlimefunMenuAlias(commandMap, alias)
            warn("Could not register /mm Slimefun menu alias")
        }
    }

    private fun unregisterSlimefunMenuAlias() {
        slimefunMenuAlias?.let { removeSlimefunMenuAlias(server.commandMap, it) }
        slimefunMenuAlias = null
    }

    private fun registerNetworkSpawnAlias() {
        val commandMap = server.commandMap
        val previous = commandMap.getCommand("spawn")
        val alias = NetworkSpawnCommand()
        commandMap.register("arc", alias)
        if (commandMap.getCommand("arc:spawn") !== alias) {
            removeNetworkSpawnAlias(commandMap, alias)
            warn("Could not register the namespaced /arc:spawn network route")
            return
        }
        if (previous != null) {
            // SLIMEFUN owns the network /spawn route. Remember the prior alias so disabling ARC
            // restores it instead of deleting another plugin's command-map entry.
            previousNetworkSpawnCommand = previous
            commandMap.knownCommands["spawn"] = alias
            warn("Replacing the existing /spawn label with the Slimefun network route")
        }
        if (commandMap.getCommand("spawn") === alias) {
            networkSpawnAlias = alias
        } else {
            removeNetworkSpawnAlias(commandMap, alias)
            previousNetworkSpawnCommand = null
            warn("Could not bind the unnamespaced /spawn network route")
        }
    }

    private fun unregisterNetworkSpawnAlias() {
        networkSpawnAlias?.let { alias ->
            val commandMap = server.commandMap
            if (commandMap.getCommand("spawn") === alias) {
                val previous = previousNetworkSpawnCommand
                if (previous == null) commandMap.knownCommands.remove("spawn") else commandMap.knownCommands["spawn"] = previous
            }
            removeNetworkSpawnAlias(commandMap, alias)
        }
        networkSpawnAlias = null
        previousNetworkSpawnCommand = null
    }

    private fun removeNetworkSpawnAlias(commandMap: org.bukkit.command.CommandMap, alias: Command) {
        val labels = commandMap.knownCommands.filterValues { it === alias }.keys.toList()
        labels.forEach { commandMap.knownCommands.remove(it) }
        alias.unregister(commandMap)
    }

    private fun removeSlimefunMenuAlias(commandMap: org.bukkit.command.CommandMap, alias: Command) {
        val labels = commandMap.knownCommands.filterValues { it === alias }.keys.toList()
        labels.forEach { commandMap.knownCommands.remove(it) }
        alias.unregister(commandMap)
    }

    private fun registerCommand(
        name: String,
        executor: CommandExecutor,
        completer: TabCompleter?,
    ) {
        val command = getCommand(name)
        if (command == null) {
            warn("Command '{}' not found in plugin.yml (test environment?)", name)
            return
        }
        command.setExecutor(executor)
        command.tabCompleter = completer ?: (executor as? TabCompleter) ?: TabCompleter { _, _, _, _ -> emptyList() }
    }

    private fun unregisterOwnedPluginCommand(name: String) {
        val commandMap = server.commandMap
        val inactive = commandMap.knownCommands.values.filterIsInstance<PluginCommand>()
            .filter { it.plugin === this && it.name.equals(name, ignoreCase = true) }.toSet()
        val labels = commandMap.knownCommands.filterValues { it in inactive }.keys.toList()
        labels.forEach { commandMap.knownCommands.remove(it) }
        inactive.forEach { it.unregister(commandMap) }
    }

    // ==================== Configuration ====================

    private fun prepareRepositoriesOnWorker(
        profile: ArcRuntimeProfile,
        root: Path,
        scheduler: ru.arc.core.TaskScheduler,
        jobsAvailable: Boolean,
    ) {
        val redis = RedisModule.preparedClient()
        if (profile == ArcRuntimeProfile.ISOLATED) return
        if (redis != null) {
            ru.arc.chat.ChatModeService.prepareOnWorker(redis)
            if (jobsAvailable) ru.arc.jobs.JobsModule.prepareOnWorker(redis, root)
        }
        if (profile != ArcRuntimeProfile.FULL) return
        LocationPoolModule.prepareOnWorker(root)
        val origin = RedisModule.preparedOrigin()
        if (redis != null) {
            ru.arc.board.BoardManager.prepareOnWorker(redis)
            ru.arc.store.StoreManager.prepareOnWorker(redis)
            ru.arc.misc.JoinMessagesManager.prepareOnWorker(redis)
            ru.arc.misc.JoinMessageCatalogManager.prepareOnWorker(redis)
            ru.arc.bschests.PersonalLootModule.prepareOnWorker(redis, origin)
        }
        ru.arc.audit.AuditManager.prepareOnWorker(root, scheduler, redis, origin)
    }

    /** Also releases candidates when bootstrap failed before ModuleRegistry attached them. */
    private fun discardPreparedRepositoriesOnWorker() {
        val cleanup = listOf<() -> Unit>(
            LocationPoolModule::discardPreparedOnWorker,
            ru.arc.audit.AuditManager::discardPreparedOnWorker,
            ru.arc.bschests.PersonalLootModule::discardPreparedOnWorker,
            ru.arc.misc.JoinMessageCatalogManager::discardPreparedOnWorker,
            ru.arc.misc.JoinMessagesManager::discardPreparedOnWorker,
            ru.arc.store.StoreManager::discardPreparedOnWorker,
            ru.arc.board.BoardManager::discardPreparedOnWorker,
            ru.arc.jobs.JobsModule::discardPreparedOnWorker,
            ru.arc.chat.ChatModeService::discardPreparedOnWorker,
            RedisModule::discardPreparedOnWorker,
        )
        var failure: Throwable? = null
        for (close in cleanup) try { close() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    /** Worker-only resource/config phase; no Bukkit registry or native object is touched here. */
    private fun prepareConfiguration(
        root: Path,
        resources: ClassLoader,
        initial: Boolean,
        activeProfile: ArcRuntimeProfile? = null,
    ): PreparedConfiguration {
        createDefaultConfigs(root, resources)
        if (!initial) ConfigManager.reloadAll()
        for (directory in listOf("modules", "guis", "config")) {
            val folder = root.resolve(directory)
            if (!Files.isDirectory(folder)) continue
            Files.list(folder).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".yml") || it.fileName.toString().endsWith(".yaml") }
                    .forEach { path ->
                        val file = path.fileName.toString()
                        ConfigManager.of(root, "$directory/$file")
                        if (directory == "modules") ConfigManager.ofModule(root, file)
                    }
            }
        }
        val profile = ArcRuntimeProfile.load(root)
        ConfigManager.of(root, "modules/misc.yml").mergeMissingFromBundled("config/portal-animation-defaults.yml")
        val menus = if ((activeProfile ?: profile) == ArcRuntimeProfile.FULL) {
            ConfigManager.of(root, ru.arc.gui.ArcMenuConfiguration.RESOURCE)
                .mergeMissingFromBundled(ru.arc.gui.ArcMenuConfiguration.RESOURCE)
            ru.arc.gui.ArcMenuConfiguration.prepare(root)
        } else null
        val joinMessageDialog = if ((activeProfile ?: profile) == ArcRuntimeProfile.FULL) {
            ru.arc.misc.JoinMessageGuiFactory.prepareOnWorker(root)
        } else null
        initLogging(root)
        val headCache = if (initial && profile == ArcRuntimeProfile.FULL) HeadTextureCache(root.resolve("head-cache.json")) else null
        val translations = if (initial && profile != ArcRuntimeProfile.ISOLATED) ru.arc.hooks.ztranslator.TranslatorHook.prepare(root) else emptyMap()
        return PreparedConfiguration(profile, menus, joinMessageDialog, headCache, translations)
    }

    private fun createDefaultConfigs(root: Path, resources: ClassLoader) {
        Files.createDirectories(root)
        for (resource in BUNDLED_RESOURCES) {
            val target = root.resolve(resource)
            if (resource !in AUTHORITATIVE_BUNDLED_RESOURCES && Files.exists(target)) continue
            Files.createDirectories(target.parent)
            resources.getResourceAsStream(resource).use { source ->
                requireNotNull(source) { "Bundled resource is unavailable: $resource" }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        ConfigManager.of(root, "modules/personalloot.yml").mergeMissingFromBundled("modules/personalloot.yml")
        ConfigManager.of(root, "modules/item-presets.yml").mergeMissingFromBundled("modules/item-presets.yml")
        ConfigManager.of(root.resolve("config"), "commands.yml").mergeMissingFromBundled("config/commands.yml")
    }

    private fun initLogging(root: Path) {
        try {
            Logging.installForPlugin(root)
        } catch (failure: Throwable) {
            error("Error initializing logging / Loki", failure)
        }
    }

    // ==================== Companion (static API) ====================

    companion object {
        /** All resource paths bundled in the JAR that must exist on disk before modules start. */
        private val BUNDLED_RESOURCES =
            listOf(
                "modules/runtime.yml",
                "modules/audit.yml",
                "modules/bank-audit.yml",
                "modules/back-wings.yml",
                "modules/decor-interactions.yml",
                "modules/dialog-demo.yml",
                "modules/enchanting.yml",
                "modules/enchantments-guide.yml",
                "modules/entity-cleanup.yml",
                "modules/graves.yml",
                "modules/home-markers.yml",
                "modules/item-lore.yml",
                "modules/join-message-dialog-legacy.yml",
                "modules/join-message-dialog-v2.yml",
                "modules/metrics.yml",
                "modules/npc-chat.yml",
                "modules/player-telemetry.yml",
                "modules/resourcepack-sync.yml",
                "modules/reward-catalog.yml",
                "modules/reward-redemption.yml",
                "modules/survival-gameplay.yml",
                "guis/jobs-boost.yml",
                "guis/personal-loot.yml",
                "guis/store.yml",
                "modules/logging.yml",
                "modules/visual-packets.yml",
                "modules/redis.yml",
                "modules/ops-http.yml",
                "modules/citizens-chunk-tickets.yml",
                "modules/npc-holograms.yml",
                "modules/announce.yml",
                "modules/scheduled-commands.yml",
                "modules/restart.yml",
                "modules/board.yml",
                "modules/command-hide.yml",
                "modules/chat-mode.yml",
                "modules/cross-server-spy.yml",
                "modules/investigations.yml",
                "modules/investigation-cases.yml",
                "modules/auction.yml",
                "modules/treasure-hunt.yml",
                "modules/mobspawn.yml",
                "modules/teleport-anchors.yml",
                "modules/parkour.yml",
                "modules/location-pools.yml",
                "modules/onboarding.yml",
                "modules/leafdecay.yml",
                "modules/personalloot.yml",
                "treasures/structures_common.yml",
                "treasures/structures_special.yml",
                "treasures/enchant_supply.yml",
                "treasures/enchant_boss_supply.yml",
                "modules/item-presets.yml",
                "modules/items-catalog.yml",
                "modules/lands-ui.yml",
                "modules/help-center.yml",
                "modules/item-info.yml",
                "modules/slimefun-menu.yml",
                "modules/furniture-gallery.yml",
                "modules/pouches.yml",
                "modules/backpacks.yml",
                "modules/commands.yml",
                "modules/text.yml",
                "modules/misc.yml",
                "modules/scoreboard.yml",
                "modules/tablist.yml",
                "modules/join-message-dialog.yml",
                "config/commands.yml",
                "guis/defaults.yml",
                "guis/menus.yml",
                "guis/board.yml",
                "guis/investigations.yml",
                "guis/parkour.yml",
                "guis/scheduled-commands.yml",
            )

        private val AUTHORITATIVE_BUNDLED_RESOURCES = emptySet<String>()

        @JvmField var plugin: ARC? = null

        /** Non-null accessor for use after [onLoad]. Throws if the plugin is not yet initialized. */
        @JvmStatic
        val instance: ARC get() = checkNotNull(plugin) { "ARC plugin is not initialized" }

        @JvmField var serverName: String? = null

        @JvmField var pluginMessenger: PluginMessenger? = null

        @JvmField var redisManager: PaperRedisClient? = null

        @JvmField var hookRegistry: HookRegistry? = null

        @JvmField var networkRegistry: NetworkRegistry? = null

        @JvmField var headTextureCache: HeadTextureCache? = null

        /** Execute a command as the server console. */
        @JvmStatic
        fun trySeverCommand(command: String) {
            info("Executing server command: {}", command)
            @Suppress("UnstableApiUsage")
            val event = ServerCommandEvent(Bukkit.getConsoleSender(), command)
            Bukkit.getPluginManager().callEvent(event)
            if (!event.isCancelled) {
                Bukkit.dispatchCommand(event.sender, event.command)
            }
        }
    }
}
