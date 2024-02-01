package arc.arc;

import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.commands.*;
import arc.arc.commands.tabcompletes.LocationPoolTabComplete;
import arc.arc.commands.tabcompletes.TreasureHuntTabComplete;
import arc.arc.configs.AnnouneConfig;
import arc.arc.configs.Config;
import arc.arc.configs.TreasureHuntConfig;
import arc.arc.hooks.*;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.RedisManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ARC extends JavaPlugin {

    public static ARC plugin;
    Config config;
    AnnouneConfig announeConfig;
    TreasureHuntConfig treasureHuntConfig;
    private static Economy econ = null;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;





    public static Economy getEcon() {
        return econ;
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

    public void loadConfig(){
        config = new Config();
        System.out.println("Announce config loading...");
        announeConfig = new AnnouneConfig();
        System.out.println("Location pool loading...");
        LocationPoolManager.init();
        System.out.println("Loading treasure hunt config");
        treasureHuntConfig = new TreasureHuntConfig();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        hookRegistry.cleanHooks();
        LocationPoolManager.save();
        TreasureHuntManager.stopAll();
    }

    public void load() {
        System.out.println("Setting up hooks");
        hookRegistry.setupHooks();
        System.out.println("Registering commands");
        registerCommands();
        System.out.println("Setting up economy");
        setupEconomy();
        System.out.println("Setting up redis");
        setupRedis();
        System.out.println("Setting up network registry");
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();
        System.out.println("Setting up particle manager");
        ParticleManager.setupParticleManager();
        System.out.println("Setting up cooldown task");
        CooldownManager.setupTask(5);
    }

    private void registerCommands() {
        getCommand("arc").setExecutor(new Command());
        getCommand("mex").setExecutor(new MexCommand());
        getCommand("cforward").setExecutor(new CforwardCommand());
        getCommand("locpool").setExecutor(new LocPoolCommand());
        getCommand("locpool").setTabCompleter(new LocationPoolTabComplete());
        getCommand("treasure-hunt").setExecutor(new TreasureHuntCommand());
        getCommand("treasure-hunt").setTabCompleter(new TreasureHuntTabComplete());
        getCommand("treasure-item").setExecutor(new TreasureItemCommand());
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
