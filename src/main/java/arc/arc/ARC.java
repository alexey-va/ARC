package arc.arc;

import arc.arc.autobuild.BuildingManager;
import arc.arc.board.Board;
import arc.arc.commands.*;
import arc.arc.commands.tabcompletes.*;
import arc.arc.configs.*;
import arc.arc.farm.FarmManager;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.RedisManager;
import arc.arc.stock.StockClient;
import arc.arc.stock.StockMarket;
import arc.arc.stock.StockPlayerManager;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleManager;
import lombok.extern.log4j.Log4j2;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

@Log4j2
public final class ARC extends JavaPlugin {

    public static ARC plugin;
    MainConfig mainConfig;
    AnnouneConfig announeConfig;
    TreasureHuntConfig treasureHuntConfig;
    public LocationPoolConfig locationPoolConfig;
    public BuildingConfig buildingConfig;
    public BoardConfig boardConfig;
    private static Economy econ = null;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;


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
        System.out.println("Loading config");
        loadConfig();
        System.out.println("Loading hook registry");
        hookRegistry = new HookRegistry();
        load();
    }

    public void loadConfig() {
        mainConfig = new MainConfig();

        System.out.println("Announce config loading...");
        announeConfig = new AnnouneConfig();

        System.out.println("Location pool loading...");
        locationPoolConfig = new LocationPoolConfig();
        LocationPoolManager.init();

        System.out.println("Loading treasure hunt config");
        treasureHuntConfig = new TreasureHuntConfig();

        System.out.println("Loading buildings config");
        buildingConfig = new BuildingConfig();

        System.out.println("Loading board config");
        boardConfig = new BoardConfig();

        System.out.println("Loading invest config");
        //InvestConfig.load();

        System.out.println("Loading stock config");
        StockConfig.load();

        System.out.println("Loading auction config");
        AuctionConfig.load();

        if (HookRegistry.farmManager != null) {
            HookRegistry.farmManager.clear();
            HookRegistry.farmManager = new FarmManager();
            HookRegistry.farmManager.init();
        }

        ConfigManager.reloadAll();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        hookRegistry.cancelTasks();
        TreasureHuntManager.stopAll();
        if (treasureHuntConfig != null) {
            treasureHuntConfig.saveTreasurePools(true);
            treasureHuntConfig.cancelTasks();
        }
        if (locationPoolConfig != null) {
            locationPoolConfig.saveLocationPools(true);
            locationPoolConfig.cancelTasks();
        }
        BuildingManager.stopAll();
        StockMarket.cancelTasks();
        StockMarket.saveHistory();
        StockClient.stopClient();
    }

    public void load() {
        System.out.println("Setting up redis");
        setupRedis();

        System.out.println("Setting up hooks");
        hookRegistry.setupHooks();

        System.out.println("Registering commands");
        registerCommands();

        System.out.println("Setting up economy");
        setupEconomy();

        System.out.println("Setting up board");
        Board.instance();

        System.out.println("Setting up network registry");
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();

        System.out.println("Setting up particle manager");
        ParticleManager.setupParticleManager();

        System.out.println("Setting up cooldown task");
        CooldownManager.setupTask(5);

        System.out.println("Setting up building cleanup task");
        BuildingManager.setupCleanupTask();

        //System.out.println("Setting up investments");
        //BusinessManager.load();

        System.out.println("Setting up stock");
        StockPlayerManager.init();
        StockMarket.init();

        System.out.println("Loading lootchest config");
        LootChestsConfig.load();

        if(HookRegistry.jobsHook != null){
            log.debug("Creating jobs repo.");
            HookRegistry.jobsHook.createRepo();
        }

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
        getCommand("build-book").setExecutor(new BuildBookCommand());
        getCommand("build-book").setTabCompleter(new BuildBookTabComplete());
        getCommand("arc-invest").setExecutor(new InvestCommand());
        getCommand("sound-follow").setExecutor(new SoundFollowCommand());

        GiveJobsBoostCommand giveJobsBoostCommand = new GiveJobsBoostCommand();
        getCommand("give-jobs-boost").setExecutor(giveJobsBoostCommand);
        getCommand("give-jobs-boost").setTabCompleter(giveJobsBoostCommand);

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
            redisManager = new RedisManager(getConfig().getString("redis.ip", "localhost"),
                    getConfig().getInt("redis.port", 3306), getConfig().getString("redis.username", "default"),
                    getConfig().getString("redis.password", ""));
            System.out.println("Redis setup.");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


}
