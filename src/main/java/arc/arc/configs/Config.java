package arc.arc.configs;

import arc.arc.ARC;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.simple.SimpleLogger;
import org.apache.logging.log4j.simple.SimpleLoggerContext;
import org.bukkit.Particle;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Log4j2
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
    public static String logLevel;
    public static String timeFormat;



    public Config(){
        ARC.plugin.getConfig().options().copyDefaults(true);
        ARC.plugin.saveDefaultConfig();
        ARC.plugin.saveConfig();

        loadConfig();
    }
    public void loadConfig(){
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
        timeFormat = ARC.plugin.getConfig().getString("time-format", "%dд %dч %dмин");

        try {
            logLevel = ARC.plugin.getConfig().getString("log-level", "DEBUG");
            Level newRootLogLevel = Level.getLevel(logLevel);
            SimpleLogger simpleLogger = (SimpleLogger) LogManager.getRootLogger();
            simpleLogger.setLevel(newRootLogLevel);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

}
