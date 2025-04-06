package ru.arc;

import ru.arc.audit.AuditManager;
import ru.arc.autobuild.BuildingManager;
import ru.arc.board.Board;
import ru.arc.bschests.PersonalLootManager;
import ru.arc.commands.*;
import ru.arc.commands.tabcompletes.InvestTabComplete;
import ru.arc.common.locationpools.LocationPoolManager;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.configs.*;
import ru.arc.eliteloot.EliteLootManager;
import ru.arc.farm.FarmManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.leafdecay.LeafDecayManager;
import ru.arc.misc.JoinMessages;
import ru.arc.mobspawn.MobSpawnManager;
import ru.arc.network.NetworkRegistry;
import ru.arc.network.RedisManager;
import ru.arc.network.repos.RedisRepo;
import ru.arc.stock.StockClient;
import ru.arc.stock.StockMarket;
import ru.arc.stock.StockPlayerManager;
import ru.arc.store.StoreManager;
import ru.arc.sync.*;
import ru.arc.treasurechests.TreasureHuntManager;
import ru.arc.util.CooldownManager;
import ru.arc.util.HeadTextureCache;
import ru.arc.util.ParticleManager;
import ru.arc.xserver.PluginMessenger;
import ru.arc.xserver.XActionManager;
import ru.arc.xserver.announcements.AnnounceManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

@Log4j2
public final class ARC extends JavaPlugin {

    public static ARC plugin;
    public static String serverName;
    public static PluginMessenger pluginMessenger;

    public LocationPoolConfig locationPoolConfig;
    public BoardConfig boardConfig;
    @Getter
    private static Economy econ;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;
    public static HeadTextureCache headTextureCache;


    @Override
    public void onLoad() {
        plugin = this;
        try {
            Logging.addLokiAppender();
        } catch (Exception e) {
            log.error("Error creating loki appender", e);
        }
    }

    @Override
    public void onEnable() {
        if (pluginMessenger == null) pluginMessenger = new PluginMessenger();
        log.info("Starting ARC");
        log.info("Creating hook registry");
        try {
            log.info("Loading redis");
            setupRedis();
        } catch (Exception e) {
            log.error("Failed to load redis", e);
        }
        System.out.println("Setting up network registry");
        try {
            networkRegistry = new NetworkRegistry(redisManager);
            networkRegistry.init();
        } catch (Exception e) {
            log.error("Failed to setup network registry", e);
        }
        try {
            hookRegistry = new HookRegistry();
            log.info("Setting up hooks");
            hookRegistry.setupHooks();
        } catch (Exception e) {
            log.error("Failed to setup hooks", e);
        }
        log.info("Loading config");
        loadConfig(true);
        load();
    }

    // reloadable
    public void loadConfig(boolean initial) {
        ConfigManager.reloadAll();

        serverName = ConfigManager
                .of(ARC.plugin.getDataPath(), "misc.yml")
                .string("redis.server-name", "default");

        if (!initial) setupRedis();

        System.out.println("Location pool loading...");
        locationPoolConfig = new LocationPoolConfig();
        LocationPoolManager.init();

        System.out.println("Loading board config");
        boardConfig = new BoardConfig();

        System.out.println("Loading stock config");
        StockConfig.load();

        System.out.println("Loading auction config");
        AuctionConfig.load();

        log.info("Starting farm manager");
        FarmManager.init();

        log.info("Starting announce manager");
        AnnounceManager.init();

        log.info("Starting xaction manager");
        XActionManager.init();

        headTextureCache = new HeadTextureCache();

        LeafDecayManager.reload();

        log.info("Loading treasure pools");
        TreasurePool.loadAllTreasures();

        PersonalLootManager.reload();

        AuditManager.init();

        startSyncs();

        TreasureHuntManager.loadTreasureHuntTypes();

        log.info("Starting board");
        Board.init();

        log.info("Starting auto build manager");
        BuildingManager.init();
    }

    @Override
    public void onDisable() {
        SyncManager.saveAll();

        RedisRepo.saveAll();

        TreasurePool.saveAllTreasurePools();

        hookRegistry.cancelTasks();
        TreasureHuntManager.stopAll();
        TreasurePool.cancelSaveTask();
        if (locationPoolConfig != null) {
            locationPoolConfig.saveLocationPools(true);
            locationPoolConfig.cancelTasks();
        }
        BuildingManager.stopAll();
        StockMarket.cancelTasks();
        StockMarket.saveHistory();
        StockClient.stopClient();
        StoreManager.saveAll();
        headTextureCache.save();
        LeafDecayManager.cancel();
        PersonalLootManager.shutdown();
    }

    public void load() {
        System.out.println("Registering commands");
        registerCommands();

        System.out.println("Setting up economy");
        setupEconomy();

        System.out.println("Initializing store manager");
        StoreManager.init();

        System.out.println("Setting up particle manager");
        ParticleManager.setupParticleManager();

        System.out.println("Setting up cooldown task");
        CooldownManager.setupTask(5);

        System.out.println("Setting up stock");
        StockPlayerManager.init();
        StockMarket.init();

        System.out.println("Setting up elite loot");
        EliteLootManager.init();

        log.info("Starting leaf decay manager");
        LeafDecayManager.init();

        log.info("Starting treasure pool save task");
        TreasurePool.startSaveTask();

        log.info("Starting personal loot manager");
        PersonalLootManager.init();

        log.info("Starting MobSpawnManager");
        MobSpawnManager.init();

        log.info("Starting join messages");
        JoinMessages.init();
    }

    private void startSyncs() {
        Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");
        if (HookRegistry.sfHook != null && config.bool("sync.slimefun", true)) {
            info("Starting slimefun sync.");
            SyncManager.registerSync(SlimefunSync.class, new SlimefunSync());
        }

        if (HookRegistry.emHook != null && config.bool("sync.em", true)) {
            info("Starting em sync.");
            SyncManager.registerSync(EmSync.class, new EmSync());
        }

        if (HookRegistry.cmiHook != null && config.bool("sync.cmi", false)) {
            info("Starting cmi sync.");
            SyncManager.registerSync(CMISync.class, new CMISync());
        }

        if (HookRegistry.auraSkillsHook != null && config.bool("sync.aura-skills", true)) {
            info("Starting aura skills sync.");
            SyncManager.registerSync(SkillsSync.class, new SkillsSync());
        }

        if(HookRegistry.duelsHook != null && config.bool("sync.duels", true)) {
            info("Starting duels sync.");
            SyncManager.registerSync(DuelsSync.class, new DuelsSync());
        }

        SyncManager.startSaveAllTasks();
    }

    @SuppressWarnings("ConstantConditions")
    private void registerCommands() {
        getCommand("arc").setExecutor(new Command());

        BuildBookCommand buildBookCommand = new BuildBookCommand();
        getCommand("build-book").setExecutor(buildBookCommand);
        getCommand("build-book").setTabCompleter(buildBookCommand);

        getCommand("arc-invest").setExecutor(new StockCommand());
        getCommand("sound-follow").setExecutor(new SoundFollowCommand());

        var arcStoreCommand = new ArcStoreCommand();
        getCommand("arcstore").setExecutor(arcStoreCommand);
        getCommand("arcstore").setTabCompleter(arcStoreCommand);

        GiveJobsBoostCommand giveJobsBoostCommand = new GiveJobsBoostCommand();
        getCommand("give-jobs-boost").setExecutor(giveJobsBoostCommand);
        getCommand("give-jobs-boost").setTabCompleter(giveJobsBoostCommand);

        TestCommand testCommand = new TestCommand();
        getCommand("arctest").setExecutor(testCommand);
        getCommand("arctest").setTabCompleter(testCommand);

        EliteLootComand eliteLootComand = new EliteLootComand();
        getCommand("eliteloot").setExecutor(eliteLootComand);
        getCommand("eliteloot").setTabCompleter(eliteLootComand);

        getCommand("arc-invest").setTabCompleter(new InvestTabComplete());

        XArcCommand xArcCommand = new XArcCommand();
        getCommand("x").setExecutor(xArcCommand);
        getCommand("x").setTabCompleter(xArcCommand);
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return;
        }
        econ = rsp.getProvider();
    }

    private void setupRedis() {
        try {
            Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");
            String ip = config.string("redis.ip", "localhost");
            int port = config.integer("redis.port", 3306);
            String username = config.string("redis.username", "default");
            String password = config.string("redis.password", "");
            if (redisManager != null) {
                redisManager.connect(ip, port, username, password);
                log.info("Reconnected to redis");
            } else {
                redisManager = new RedisManager(ip, port, username, password);
                log.info("Connected to redis");
            }
        } catch (Exception e) {
            log.error("Failed to connect to redis", e);
        }

    }

    public static void info(String s, Object... args) {
        String toPrint = s;
        for (Object arg : args) toPrint = toPrint.replaceFirst("\\{}", arg == null ? "null" : arg.toString());
        Bukkit.getLogger().info(toPrint);
    }

    public static void warn(String s, Object... args) {
        String toPrint = s;
        for (Object arg : args) {
            toPrint = toPrint.replaceFirst("\\{}", arg == null ? "null" : arg.toString());
        }
        Bukkit.getLogger().warning(toPrint);
    }

    public static void trace(String s, Object... args) {
        String toPrint = s;
        for (Object arg : args) {
            toPrint = toPrint.replaceFirst("\\{}", arg == null ? "null" : arg.toString());
        }
    }

    public static void trySeverCommand(String command) {
        log.info("Executing server command: {}", command);
        ServerCommandEvent event = new ServerCommandEvent(Bukkit.getConsoleSender(), command);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        Bukkit.dispatchCommand(event.getSender(), event.getCommand());
    }
}
