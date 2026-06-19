package ru.arc

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.commands.XCommand
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.LegacySubCommandExecutor
import ru.arc.commands.arc.subcommands.BuildBookSubCommand
import ru.arc.commands.arc.subcommands.EliteLootSubCommand
import ru.arc.commands.arc.subcommands.GiveBoostSubCommand
import ru.arc.commands.arc.subcommands.HuntSubCommand
import ru.arc.commands.arc.subcommands.InvestSubCommand
import ru.arc.commands.arc.subcommands.LocpoolSubCommand
import ru.arc.commands.arc.subcommands.SoundFollowSubCommand
import ru.arc.commands.arc.subcommands.StoreSubCommand
import ru.arc.commands.arc.subcommands.TestSubCommand
import ru.arc.commands.arc.subcommands.TreasuresSubCommand
import ru.arc.configs.BoardConfig
import ru.arc.configs.LocationPoolConfig
import ru.arc.core.ModuleRegistry
import ru.arc.core.modules.*
import ru.arc.ops.OpsHttpModule
import ru.arc.hooks.HookRegistry
import ru.arc.network.NetworkRegistry
import ru.arc.network.RedisManager
import ru.arc.util.HeadTextureCache
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
    var boardConfig: BoardConfig? = null

    // ==================== Lifecycle ====================

    override fun onLoad() {
        plugin = this
        createDefaultConfigs()
        initLogging()
    }

    override fun onEnable() {
        printBanner()

        if (pluginMessenger == null) {
            pluginMessenger = PluginMessenger()
        }

        registerModules()
        ModuleRegistry.initAll()
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
        ModuleRegistry.shutdownAll()
        info("ARC plugin disabled")
    }

    // ==================== Reload ====================

    /** Reload all plugin configuration and modules. Called by /arc reload. */
    fun reload() {
        info("Reloading ARC plugin")
        ModuleRegistry.reloadAll()
        info("ARC plugin reloaded")
    }

    // ==================== Module Registration ====================

    private fun registerModules() {
        debug("Registering modules...")

        ModuleRegistry.registerAll(
            // Core infrastructure (priority 10-29)
            RedisModule,
            NetworkModule,
            HooksModule,
            EconomyModule,
            // Configuration (priority 30-49)
            ConfigModule,
            OpsHttpModule,
            LocationPoolModule,
            BoardModule,
            // Core features (priority 50-69)
            ParticleModule,
            CooldownModule,
            HeadCacheModule,
            AuditModule,
            // Game features (priority 70-89)
            FarmModule,
            AnnounceModule,
            XActionModule,
            StockModule,
            StoreModule,
            TreasureModule,
            EliteLootModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            JoinMessagesModule,
            // Building system (priority 90)
            BuildingModule,
            // Sync systems (priority 100)
            SyncModule,
        )
    }

    // ==================== Command Registration ====================

    private fun registerCommands() {
        debug("Registering commands...")

        val arcCommand = ArcCommand.INSTANCE
        registerCommand("arc", arcCommand, arcCommand)
        registerCommand("x", XCommand, XCommand)
        registerLegacyCommands()
    }

    private fun registerLegacyCommands() {
        val legacyCommands =
            mapOf(
                "locpool" to LocpoolSubCommand,
                "treasure-hunt" to HuntSubCommand,
                "treasure-pool" to TreasuresSubCommand,
                "build-book" to BuildBookSubCommand,
                "arc-invest" to InvestSubCommand,
                "sound-follow" to SoundFollowSubCommand,
                "give-jobs-boost" to GiveBoostSubCommand,
                "arcstore" to StoreSubCommand,
                "arctest" to TestSubCommand,
                "eliteloot" to EliteLootSubCommand,
            )

        for ((name, subCommand) in legacyCommands) {
            val bridge = LegacySubCommandExecutor(subCommand)
            registerCommand(name, bridge, bridge)
        }
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
            if (!file.exists()) {
                file.parentFile.mkdirs()
                saveResource(resource, false)
                debug("Saved bundled resource: {}", resource)
            }
        }
    }

    private fun initLogging() {
        try {
            ru.arc.util.Logging.addLokiAppender()
        } catch (e: Throwable) {
            error("Error creating Loki appender", e)
        }
    }

    // ==================== Companion (static API) ====================

    companion object {
        /** All resource paths bundled in the JAR that must exist on disk before modules start. */
        private val BUNDLED_RESOURCES =
            listOf(
                "modules/logging.yml",
                "modules/ops-http.yml",
                "modules/announce.yml",
                "modules/board.yml",
                "modules/farms.yml",
                "modules/auction.yml",
                "modules/treasure-hunt.yml",
                "modules/mobspawn.yml",
                "modules/location-pools.yml",
                "modules/elite-loot.yml",
                "modules/auto-build.yml",
                "modules/leafdecay.yml",
                "modules/personalloot.yml",
                "modules/item-presets.yml",
                "stocks/stock.yml",
                "misc.yml",
                "backpacks.yml",
                "commands.yml",
                "config/commands.yml",
            )

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
