package arc.arc;

import arc.arc.commands.CforwardCommand;
import arc.arc.commands.MexCommand;
import arc.arc.hooks.*;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.RedisManager;
import arc.arc.util.CooldownManager;
import arc.arc.util.ParticleUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

public final class ARC extends JavaPlugin {

    public static ARC plugin;
    public static Config config;
    private static Economy econ = null;
    public static RedisManager redisManager;
    public static HookRegistry hookRegistry;
    public static NetworkRegistry networkRegistry;



    public static void noPermissionMessage(Player player) {
        player.sendMessage(
                Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        .append(Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        );
    }

    public static Economy getEcon() {
        return econ;
    }

    @Override
    public void onEnable() {
        plugin = this;
        config = new Config();
        hookRegistry = new HookRegistry();

        load();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        hookRegistry.cleanHooks();
    }

    public void load() {
        hookRegistry.setupHooks();
        registerCommands();
        setupEconomy();
        setupRedis();
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();
        ParticleUtil.setupParticleManager();
        CooldownManager.setupTask(5);
    }

    private void registerCommands() {
        getCommand("arc").setExecutor(new Command());
        getCommand("mex").setExecutor(new MexCommand());
        getCommand("cforward").setExecutor(new CforwardCommand());
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
