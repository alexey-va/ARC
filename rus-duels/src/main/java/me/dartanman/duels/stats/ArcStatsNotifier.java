package me.dartanman.duels.stats;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class ArcStatsNotifier {
    private static final String BRIDGE_CLASS = "ru.arc.sync.duels.DuelStatsBridge";

    private final Logger logger;
    private volatile Method statsChanged;

    public ArcStatsNotifier(Logger logger) {
        this.logger = logger;
    }

    public void statsChanged(UUID playerId) {
        try {
            Method method = resolve();
            if (method != null) method.invoke(null, playerId);
        } catch (ReflectiveOperationException | LinkageError exception) {
            statsChanged = null;
            logger.log(Level.WARNING, "Could not notify ARC about changed duel stats", exception);
        }
    }

    private Method resolve() throws ReflectiveOperationException {
        Method cached = statsChanged;
        if (cached != null) return cached;
        Plugin arc = Bukkit.getPluginManager().getPlugin("ARC");
        if (arc == null || !arc.isEnabled()) return null;
        Class<?> bridge = Class.forName(BRIDGE_CLASS, true, arc.getClass().getClassLoader());
        Method method = bridge.getMethod("statsChanged", UUID.class);
        statsChanged = method;
        return method;
    }
}
