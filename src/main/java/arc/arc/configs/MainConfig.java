package arc.arc.configs;

import arc.arc.ARC;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.HashSet;
import java.util.Set;

@Log4j2
public class MainConfig {

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


    public MainConfig() {
        ARC.plugin.getConfig().options().copyDefaults(true);
        ARC.plugin.saveDefaultConfig();
        ARC.plugin.saveConfig();

        loadConfig();
    }

    public void loadConfig() {
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
            logLevel = ARC.plugin.getConfig().getString("log-level", "INFO");
            Level newRootLogLevel = Level.getLevel(logLevel);
            //System.out.println("Config log level: "+newRootLogLevel);

            // get logger for package arc.arc and set it level to newRootLogLevel
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig("arc.arc");
            loggerConfig.setLevel(newRootLogLevel);

            // update loggers
            ctx.updateLoggers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
