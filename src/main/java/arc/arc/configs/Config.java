package arc.arc.configs;

import arc.arc.ARC;
import org.bukkit.Particle;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Config {

    public static boolean blockBackpacks;
    public static boolean sendWormholes;
    public static int wormholePeriod;
    public static int particleCount;
    public static double particleOffset;
    public static boolean endProtection;
    public static String server;
    public static boolean enablePortals;
    public static Set<String> noExpWorlds = new HashSet<>();
    public static String partyTag;
    public static long cForwardDelay;



    public Config(){
        ARC.plugin.getConfig().options().copyDefaults(true);
        ARC.plugin.saveDefaultConfig();
        ARC.plugin.saveConfig();

        reloadConfig();
    }
    public void reloadConfig(){
        blockBackpacks = ARC.plugin.getConfig().getBoolean("disable-backpacks", false);
        endProtection = ARC.plugin.getConfig().getBoolean("end-protection", false);
        sendWormholes = ARC.plugin.getConfig().getBoolean("wormholes.enable", false);
        wormholePeriod = ARC.plugin.getConfig().getInt("wormholes.period", 10);
        noExpWorlds = new HashSet<>(ARC.plugin.getConfig().getStringList("no-explosion-worlds"));
        particleCount = ARC.plugin.getConfig().getInt("wormholes.count", 30);
        particleOffset = ARC.plugin.getConfig().getDouble("wormholes.offset", 1.0);
        server = ARC.plugin.getConfig().getString("redis.server-name", "none");
        enablePortals = ARC.plugin.getConfig().getBoolean("enable-portals", true);
        partyTag = ARC.plugin.getConfig().getString("party.tag", "&7[%color%%tag%&7]&r ");
        cForwardDelay = ARC.plugin.getConfig().getLong("xserver.cforward-delay", 20L);
    }

}
