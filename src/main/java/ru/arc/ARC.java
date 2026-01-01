package ru.arc;

import java.util.Map;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.arc.commands.XCommand;
import ru.arc.commands.arc.ArcCommand;
import ru.arc.configs.BoardConfig;
import ru.arc.configs.LocationPoolConfig;
import ru.arc.core.ModuleRegistry;
import ru.arc.core.modules.*;
import ru.arc.hooks.HookRegistry;
import ru.arc.network.NetworkRegistry;
import ru.arc.network.RedisManager;
import ru.arc.util.HeadTextureCache;
import ru.arc.xserver.PluginMessenger;

import static ru.arc.util.Logging.*;

/**
 * Main plugin class for ARC.
 * <p>
 * This class focuses on:
 * - Plugin lifecycle (onLoad, onEnable, onDisable)
 * - Module registration
 * - Command registration
 * - Default config creation
 * <p>
 * All feature logic is delegated to modules in {@link ru.arc.core.modules}.
 */
public class ARC extends JavaPlugin {

    // ==================== Static References ====================
    
    public static ARC plugin;
    public static String serverName;
    public static PluginMessenger pluginMessenger;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;
    public static HeadTextureCache headTextureCache;

    // ==================== Instance Fields ====================

    public LocationPoolConfig locationPoolConfig;
    public BoardConfig boardConfig;

    // ==================== Lifecycle ====================

    @Override
    public void onLoad() {
        plugin = this;
        createDefaultConfigs();
        initLogging();
    }

    @Override
    public void onEnable() {
        info("Starting ARC plugin");

        if (pluginMessenger == null) {
            pluginMessenger = new PluginMessenger();
        }

        registerModules();
        ModuleRegistry.INSTANCE.initAll();
        registerCommands();

        info("ARC plugin enabled");
    }

    @Override
    public void onDisable() {
        info("Stopping ARC plugin");
        ModuleRegistry.INSTANCE.shutdownAll();
        info("ARC plugin disabled");
    }

    // ==================== Reload ====================

    /**
     * Reload all plugin configuration and modules.
     * Called by /arc reload command.
     */
    public void reload() {
        info("Reloading ARC plugin");
        ModuleRegistry.INSTANCE.reloadAll();
        info("ARC plugin reloaded");
    }

    // ==================== Module Registration ====================

    private void registerModules() {
        debug("Registering modules...");

        ModuleRegistry.INSTANCE.registerAll(
                // Core infrastructure (priority 10-29)
                RedisModule.INSTANCE,
                NetworkModule.INSTANCE,
                HooksModule.INSTANCE,
                EconomyModule.INSTANCE,

                // Configuration (priority 30-49)
                ConfigModule.INSTANCE,
                LocationPoolModule.INSTANCE,
                BoardModule.INSTANCE,

                // Core features (priority 50-69)
                ParticleModule.INSTANCE,
                CooldownModule.INSTANCE,
                HeadCacheModule.INSTANCE,
                AuditModule.INSTANCE,

                // Game features (priority 70-89)
                FarmModule.INSTANCE,
                AnnounceModule.INSTANCE,
                XActionModule.INSTANCE,
                StockModule.INSTANCE,
                StoreModule.INSTANCE,
                TreasureModule.INSTANCE,
                EliteLootModule.INSTANCE,
                LeafDecayModule.INSTANCE,
                PersonalLootModule.INSTANCE,
                MobSpawnModule.INSTANCE,
                JoinMessagesModule.INSTANCE,

                // Building system (priority 90)
                BuildingModule.INSTANCE,

                // Sync systems (priority 100)
                SyncModule.INSTANCE
        );
    }

    // ==================== Command Registration ====================

    private void registerCommands() {
        debug("Registering commands...");

        // Main /arc command with all subcommands
        var arcCommand = ArcCommand.Companion.getINSTANCE();
        registerCommand("arc", arcCommand, arcCommand);

        // Standalone /x command for cross-server execution
        registerCommand("x", ru.arc.commands.XCommand.INSTANCE, ru.arc.commands.XCommand.INSTANCE);
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter completer) {
        var command = getCommand(name);
        if (command == null) {
            warn("Command '{}' not found in plugin.yml (test environment?)", name);
            return;
        }
        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
    }

    // ==================== Configuration ====================

    private void createDefaultConfigs() {
        var dataFolder = getDataFolder();
        debug("Creating default configs in: {}", dataFolder.getAbsolutePath());
        dataFolder.mkdirs();

        var configs = Map.ofEntries(
                entry("logging.yml", "enabled: false\nhost: localhost\nport: 3100\nlabels: {}"),
                entry("announce.yml", "config:\n  delay-seconds: 600\nmessages: {}"),
                entry("board.yml", "boards: {}"),
                entry("farms.yml", "farms: {}"),
                entry("auction.yml", "enabled: false"),
                entry("treasure-hunt.yml", "hunts: {}"),
                entry("mobspawn.yml", "enabled: false\nspawns: {}"),
                entry("portal.yml", "enabled: false\nportals: {}"),
                entry("location-pools.yml", "pools: {}"),
                entry("stock.yml", "enabled: false"),
                entry("elite-loot.yml", "enabled: false\nloot: {}"),
                entry("auto-build.yml", "enabled: true"),
                entry("leaf-decay.yml", "enabled: false"),
                entry("join-messages.yml", "enabled: false"),
                entry("personal-loot.yml", "enabled: false"),
                entry("misc.yml", "redis:\n  server-name: test-server"),
                entry("store.yml", "enabled: false"),
                entry("sync.yml", "enabled: false"),
                entry("bschests.yml", "enabled: false")
        );

        for (var cfg : configs.entrySet()) {
            createConfigIfMissing(cfg.getKey(), cfg.getValue());
        }
    }

    private void createConfigIfMissing(String name, String defaultContent) {
        var file = new java.io.File(getDataFolder(), name);
        if (file.exists()) {
            return;
        }

        try {
            saveResource(name, false);
            debug("Saved resource: {}", name);
        } catch (Exception e) {
            try {
                file.getParentFile().mkdirs();
                java.nio.file.Files.writeString(file.toPath(), defaultContent + "\n");
                debug("Created default config: {}", name);
            } catch (Exception ex) {
                error("Failed to create config: {}", name, ex);
            }
        }
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
    }

    private void initLogging() {
        try {
            ru.arc.util.Logging.addLokiAppender();
        } catch (Exception e) {
            error("Error creating Loki appender", e);
        }
    }

    // ==================== Utilities ====================

    /**
     * Execute a command as console.
     */
    public static void trySeverCommand(String command) {
        info("Executing server command: {}", command);
        var event = new ServerCommandEvent(Bukkit.getConsoleSender(), command);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            Bukkit.dispatchCommand(event.getSender(), event.getCommand());
        }
    }

    /**
     * Get the Vault economy instance.
     *
     * @deprecated Use {@link EconomyModule#getEconomy()} instead.
     */
    @Deprecated
    public static Economy getEcon() {
        return EconomyModule.getEconomy();
    }
}
