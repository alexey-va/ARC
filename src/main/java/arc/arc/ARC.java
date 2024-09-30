package arc.arc;

import arc.arc.autobuild.BuildingManager;
import arc.arc.board.Board;
import arc.arc.bschests.PersonalLootManager;
import arc.arc.commands.*;
import arc.arc.commands.tabcompletes.InvestTabComplete;
import arc.arc.commands.tabcompletes.LocationPoolTabComplete;
import arc.arc.commands.tabcompletes.TreasureHuntTabComplete;
import arc.arc.commands.tabcompletes.TreasurePoolTabcomplete;
import arc.arc.configs.*;
import arc.arc.eliteloot.EliteLootManager;
import arc.arc.farm.FarmManager;
import arc.arc.generic.treasure.TreasurePool;
import arc.arc.hooks.HookRegistry;
import arc.arc.leafdecay.LeafDecayManager;
import arc.arc.mobspawn.MobSpawnManager;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.RedisManager;
import arc.arc.stock.StockClient;
import arc.arc.stock.StockMarket;
import arc.arc.stock.StockPlayerManager;
import arc.arc.store.StoreManager;
import arc.arc.sync.*;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.HeadTextureCache;
import arc.arc.util.ParticleManager;
import arc.arc.xserver.announcements.AnnounceManager;
import lombok.extern.log4j.Log4j2;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

@Log4j2
public final class ARC extends JavaPlugin {

    public static ARC plugin;
    MainConfig mainConfig;
    TreasureHuntConfig treasureHuntConfig;
    public LocationPoolConfig locationPoolConfig;
    public BoardConfig boardConfig;
    private static Economy econ = null;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;
    public static HeadTextureCache headTextureCache;


    boolean loadedPacketApi = false;


    public static Economy getEcon() {
        return econ;
    }


    @Override
    public void onLoad() {
/*        if (!loadedPacketApi) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
                PacketEvents.getAPI().getSettings().reEncodeByDefault(false)
                                .checkForUpdates(true)
                                .bStats(true);
                PacketEvents.getAPI().load();
                loadedPacketApi = true;
            }*/
    }

    @Override
    public void onEnable() {
        plugin = this;
        log.info("Starting ARC");
        log.info("Creating hook registry");

        System.out.println("Setting up redis");
        setupRedis();

        System.out.println("Setting up network registry");
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();

        hookRegistry = new HookRegistry();
        log.info("Setting up hooks");
        hookRegistry.setupHooks();

        log.info("Loading config");
        loadConfig();
        load();
    }

    public void loadConfig() {
        ConfigManager.reloadAll();

        mainConfig = new MainConfig();

        System.out.println("Location pool loading...");
        locationPoolConfig = new LocationPoolConfig();
        LocationPoolManager.init();

        System.out.println("Loading treasure hunt config");
        treasureHuntConfig = new TreasureHuntConfig();

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

        headTextureCache = new HeadTextureCache();

        LeafDecayManager.reload();

        TreasurePool.loadAllTreasures();

        PersonalLootManager.reload();
    }

    @Override
    public void onDisable() {
        SyncManager.saveAll();

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

        System.out.println("Setting up board");
        Board.init();

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

        log.info("Starting auto build manager");
        BuildingManager.init();

        log.info("Starting leaf decay manager");
        LeafDecayManager.init();

        log.info("Starting treasure pool save task");
        TreasurePool.startSaveTask();

        log.info("Starting personal loot manager");
        PersonalLootManager.init();

        log.info("Starting MobSpawnManager");
        MobSpawnManager.init();

        startSyncs();
    }

    private void startSyncs() {
        if (HookRegistry.sfHook != null) {
            info("Starting slimefun sync.");
            SyncManager.registerSync(SlimefunSync.class, new SlimefunSync());
        }

        if (HookRegistry.emHook != null) {
            info("Starting em sync.");
            SyncManager.registerSync(EmSync.class, new EmSync());
        }

        if (HookRegistry.cmiHook != null) {
            info("Starting cmi sync.");
            SyncManager.registerSync(CMISync.class, new CMISync());
        }

        if (HookRegistry.auraSkillsHook != null) {
            info("Starting aura skills sync.");
            SyncManager.registerSync(SkillsSync.class, new SkillsSync());
        }

        SyncManager.startSaveAllTasks();
    }

    private void registerCommands() {
        getCommand("arc").setExecutor(new Command());
        getCommand("mex").setExecutor(new MexCommand());
        getCommand("cforward").setExecutor(new CforwardCommand());
        getCommand("locpool").setExecutor(new LocPoolCommand());
        getCommand("locpool").setTabCompleter(new LocationPoolTabComplete());
        getCommand("treasure-hunt").setExecutor(new TreasureHuntCommand());
        getCommand("treasure-hunt").setTabCompleter(new TreasureHuntTabComplete());
        getCommand("treasure-pool").setExecutor(new TreasurePoolCommand());
        getCommand("treasure-pool").setTabCompleter(new TreasurePoolTabcomplete());


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
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return true;
    }

    private void setupRedis() {
        try {
            if (redisManager != null) redisManager.close();

            redisManager = new RedisManager(getConfig().getString("redis.ip", "localhost"),
                    getConfig().getInt("redis.port", 3306), getConfig().getString("redis.username", "default"),
                    getConfig().getString("redis.password", ""));
            System.out.println("Redis setup.");
        } catch (Exception e) {
            e.printStackTrace();
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
        //Bukkit.getLogger().fine(toPrint);
    }


}
