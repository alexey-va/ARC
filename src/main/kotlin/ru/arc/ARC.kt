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
import ru.arc.itemcatalog.ArcItemMaterializerBridge
import ru.arc.paper.api.ArcTelemetryProvider
import ru.arc.paper.api.ArcItemMaterializer
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.sidebar.PaperArcSidebarService
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
import ru.arc.core.dataPath
import ru.arc.core.modules.AiModule
import ru.arc.core.modules.AnnounceModule
import ru.arc.core.modules.AuditModule
import ru.arc.core.modules.BoardModule
import ru.arc.core.modules.ConfigModule
import ru.arc.metrics.MetricsModule
import ru.arc.mounts.MountModule
import ru.arc.parkour.ArcParkourModule
import ru.arc.core.modules.CooldownModule
import ru.arc.core.modules.EconomyModule
import ru.arc.core.modules.EliteLootModule
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
import ru.arc.contracts.ContractsModule
import ru.arc.investigation.InvestigationModule
import ru.arc.hooks.HookRegistry
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
import ru.arc.redis.RedisManager
import ru.arc.ops.OpsHttpModule
import ru.arc.onboarding.OnboardingModule
import ru.arc.origin.OriginSpawnModule
import ru.arc.origin.OriginPortalsModule
import ru.arc.origin.OriginDiningModule
import ru.arc.origin.OriginTrainingDummyModule
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.mountyard.OriginMountYardModule
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.restart.RestartModule
import ru.arc.slimefunmenu.SlimefunMenuAliasCommand
import ru.arc.slimefunmenu.SlimefunMenuCommand
import ru.arc.slimefunmenu.SlimefunMenuModule
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

    internal lateinit var sidebarService: PaperArcSidebarService
        private set

    private var baseSidebar: ArcBaseSidebar? = null
    private var slimefunMenuAlias: Command? = null

    var runtimeProfile: ArcRuntimeProfile = ArcRuntimeProfile.FULL
        private set

    // ==================== Lifecycle ====================

    override fun onLoad() {
        plugin = this
        createDefaultConfigs()
        runtimeProfile = ArcRuntimeProfile.load(dataPath)
        if (runtimeProfile == ArcRuntimeProfile.FULL) GuiDefaults.init(dataPath)
        initLogging()
    }

    override fun onEnable() {
        printBanner()

        if (runtimeProfile == ArcRuntimeProfile.FULL && pluginMessenger == null) {
            pluginMessenger = PluginMessenger()
        }

        PaperArcRuntime.installScheduling(this)
        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            ArcMenus.initialize(this, dataPath)
        } else if (runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            ArcMenus.initializeDialogRuntime(this)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            chunkTicketRegistry = PaperChunkTicketRegistry(this)
            sidebarService = PaperArcSidebarService(this)
            server.servicesManager.register(ArcSidebarService::class.java, sidebarService, this, ServicePriority.Normal)
            RtpPlayerRegistry.initialize(dataPath)
        }
        registerModules()
        PaperArcRuntime.installModuleLifecycleReporting(
            consoleLog = { consoleLog(it) },
            logError = { msg, t -> error(msg, t) },
        )
        ModuleRegistry.initAll()
        if (runtimeProfile == ArcRuntimeProfile.FULL) {
            server.servicesManager.register(ArcTelemetryProvider::class.java, ArcTelemetryProviderBridge, this, ServicePriority.Normal)
            server.servicesManager.register(ArcItemMaterializer::class.java, ArcItemMaterializerBridge, this, ServicePriority.Normal)
            baseSidebar = ArcBaseSidebar(this, sidebarService).also(ArcBaseSidebar::start)
        }
        // Start the single Redis subscription after ALL modules have registered their channels.
        // Calling init() multiple times (once per module) caused the subscription to be
        // constantly restarted and never complete its 1s startup delay.
        redisManager?.let {
            consoleLog("<dark_gray>[ARC]</dark_gray> <aqua>☁  Redis</aqua>  subscribing to <white>${it.getChannelCount()}</white> channels")
            it.init()
        }
        registerCommands()

        consoleLog("<dark_gray>[ARC]</dark_gray> <bold><green>✔  ARC is ready</green></bold>")
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
        info("Stopping ARC plugin")
        server.servicesManager.unregisterAll(this)
        unregisterSlimefunMenuAlias()
        if (runtimeProfile == ArcRuntimeProfile.FULL) Portal.removeAll()
        ModuleRegistry.shutdownAll()
        baseSidebar?.close()
        baseSidebar = null
        if (::sidebarService.isInitialized) {
            runCatching(sidebarService::close)
                .onFailure { error("Failed to close ARC sidebar service", it) }
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL || runtimeProfile == ArcRuntimeProfile.SLIMEFUN) ArcMenus.close()
        if (::chunkTicketRegistry.isInitialized) {
            runCatching(chunkTicketRegistry::close)
                .onFailure { error("Failed to close ARC chunk ticket registry", it) }
        }
        pluginMessenger?.shutdown()
        pluginMessenger = null
        GuiDefaults.reset()
        Tasks.reset()
        info("ARC plugin disabled")
    }

    // ==================== Reload ====================

    /** Reload all plugin configuration and modules. Called by /arc reload. */
    fun reload() {
        info("Reloading ARC plugin")
        if (runtimeProfile == ArcRuntimeProfile.FULL) Portal.removeAll()
        // Reload YAML from disk before modules re-read configs (announce delay, etc.).
        ConfigManager.reloadAll()
        if (ArcRuntimeProfile.load(dataPath) != runtimeProfile) {
            warn("Runtime profile changes require a server restart; keeping {}", runtimeProfile)
        }
        if (runtimeProfile == ArcRuntimeProfile.FULL) ArcMenus.reload()
        ModuleRegistry.reloadAll()
        baseSidebar?.refresh()
        // Modules may replace channel listeners during reload; restart the subscription once
        // after every module has refreshed its registrations.
        redisManager?.let {
            info("Redis resubscribing to {} channels after reload", it.getChannelCount())
            it.init()
        }
        info("ARC plugin reloaded")
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
            ModuleRegistry.registerAll(ConfigModule, OpsHttpModule, RestartModule, ItemInfoModule)
            if (ChatModeConfig.load(dataPath).isolatedGlyphProtectionEnabled) {
                ModuleRegistry.registerAll(RedisModule, IsolatedChatGlyphModule)
                info("Runtime profile slimefun: local operations with chat glyph authorization")
            } else {
                info("Runtime profile slimefun: Config, OpsHttp, Restart, ItemInfo, SlimefunMenu")
            }
            ModuleRegistry.registerAll(SlimefunMenuModule)
            return
        }

        ModuleRegistry.registerAll(
            // Core infrastructure (priority 10-29)
            RedisModule,
            NetworkModule,
            HooksModule,
            NpcChunkTicketModule,
            ArcNpcHologramModule,
            OriginSpawnModule,
            OriginPortalsModule,
            AiModule,
            EconomyModule,
            OriginDiningModule,
            OriginAmbientScenesModule,
            OriginTrainingDummyModule,
            OriginMountYardModule,
            // Configuration (priority 30-49)
            ConfigModule,
            MetricsModule,
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
            ContractsModule,
            InvestigationModule,
            TreasureModule,
            EliteLootModule,
            ru.arc.eliteloot.LostLootModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            EntityCleanupModule,
            TravelAnchorsModule,
            JoinMessagesModule,
            ChatModeModule,
            CrossServerSpyModule,
            MountModule,
            ArcParkourModule,
            ItemsCatalogModule,
            ItemLoreModule,
            LandsUiModule,
            HelpCenterModule,
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
            // Retain ARC and the utility menu; keep all FULL-only labels out of this profile.
            val commandMap = server.commandMap
            val preserved = setOf("arc", "menu")
            val inactive = commandMap.knownCommands.values.filterIsInstance<PluginCommand>()
                .filter { it.plugin === this && it.name !in preserved }.toSet()
            val labels = commandMap.knownCommands.filterValues { it in inactive }.keys.toList()
            labels.forEach { commandMap.knownCommands.remove(it) }
            inactive.forEach { it.unregister(commandMap) }
            registerCommand("menu", SlimefunMenuCommand, null)
            registerSlimefunMenuAlias()
            return
        }
        registerCommand("x", XCommand, XCommand)
        registerCommand("g", ChatModeAliasCommand, null)
        registerCommand("l", ChatModeAliasCommand, null)
        registerCommand("menu", MainMenuCommand, null)
        registerCommand("arc-reward-issue", CaseRewardIssueCommand(), null)
        val dungeonCommand = ru.arc.hooks.elitemobs.EMDungeonCommand(
            ru.arc.config.ConfigManager.of(dataPath, "modules/elitemobs.yml"),
        )
        for (name in listOf("dungeon", "dungeonstart", "dungeonsave", "dungeonsaves")) {
            registerCommand(name, dungeonCommand, dungeonCommand)
        }
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
        completer?.let { command.tabCompleter = it }
    }

    // ==================== Configuration ====================

    private fun createDefaultConfigs() {
        debug("Creating default configs in: {}", dataFolder.absolutePath)
        dataFolder.mkdirs()

        for (resource in BUNDLED_RESOURCES) {
            val file = File(dataFolder, resource)
            if (resource in AUTHORITATIVE_BUNDLED_RESOURCES) {
                file.parentFile.mkdirs()
                saveResource(resource, true)
                debug("Refreshed authoritative bundled resource: {}", resource)
            } else if (!file.exists()) {
                file.parentFile.mkdirs()
                saveResource(resource, false)
                debug("Saved bundled resource: {}", resource)
            }
        }

        ConfigManager.of(dataPath, "modules/elitemobs.yml").mergeMissingFromBundled("modules/elitemobs.yml")

        val commandsConfig = ConfigManager.of(dataFolder.toPath().resolve("config"), "commands.yml")
        if (commandsConfig.mergeMissingFromBundled("config/commands.yml")) {
            debug("Added missing bundled keys to config/commands.yml")
        }
    }

    private fun initLogging() {
        try {
            Logging.installForPlugin(dataPath)
        } catch (e: Throwable) {
            error("Error initializing logging / Loki", e)
        }
    }

    // ==================== Companion (static API) ====================

    companion object {
        /** All resource paths bundled in the JAR that must exist on disk before modules start. */
        private val BUNDLED_RESOURCES =
            listOf(
                "modules/runtime.yml",
                "modules/logging.yml",
                "modules/metrics.yml",
                "modules/redis.yml",
                "modules/ops-http.yml",
                "modules/citizens-chunk-tickets.yml",
                "modules/npc-holograms.yml",
                "modules/origin-spawn.yml",
                "modules/origin-dining.yml",
                "modules/origin-scenes.yml",
                "modules/origin-mount-yard.yml",
                "modules/origin-mount-care.yml",
                "modules/mount-care-boost.yml",
                "modules/announce.yml",
                "modules/scheduled-commands.yml",
                "modules/restart.yml",
                "modules/board.yml",
                "modules/command-hide.yml",
                "modules/chat-mode.yml",
                "modules/cross-server-spy.yml",
                "modules/contracts.yml",
                "modules/investigations.yml",
                "modules/investigation-cases.yml",
                "modules/auction.yml",
                "modules/treasure-hunt.yml",
                "modules/mobspawn.yml",
                "modules/teleport-anchors.yml",
                "modules/mounts.yml",
                "modules/parkour.yml",
                "modules/location-pools.yml",
                "modules/elite-loot.yml",
                "modules/onboarding.yml",
                "modules/leafdecay.yml",
                "modules/personalloot.yml",
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
                "modules/elitemobs.yml",
                "modules/text.yml",
                "modules/misc.yml",
                "modules/scoreboard.yml",
                "modules/join-message-dialog.yml",
                "config/commands.yml",
                "guis/defaults.yml",
                "guis/menus.yml",
                "guis/board.yml",
                "guis/contracts.yml",
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

        @JvmField var redisManager: RedisManager? = null

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
