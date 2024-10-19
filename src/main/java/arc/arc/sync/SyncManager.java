package arc.arc.sync;

import arc.arc.ARC;
import arc.arc.sync.base.Sync;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Log4j2
public class SyncManager {

    private final static Map<Class<?>, Sync> syncMap = new ConcurrentHashMap<>();
    private static BukkitTask saveAllTask;

    public static void registerSync(Class<?> clazz, Sync sync) {
        syncMap.put(clazz, sync);
    }

    public static void unregisterSync(Class<?> clazz) {
        syncMap.remove(clazz);
    }

    public static List<Sync> getSyncs() {
        return new ArrayList<>(syncMap.values());
    }

    public static Sync getSync(Class<?> clazz) {
        return syncMap.get(clazz);
    }

    public static void processEvent(Event event) {
        syncMap.values().forEach(sync -> sync.processEvent(event));
    }

    public static void playerJoin(UUID uuid) {
        log.trace("Player join sync: {}", uuid);
        syncMap.values().forEach(sync -> sync.playerJoin(uuid));
    }

    public static void playerQuit(UUID uuid) {
        log.trace("Player quit sync: {}", uuid);
        syncMap.values().forEach(sync -> sync.playerQuit(uuid));
    }

    public static void startSaveAllTasks() {
        stopSaveAllTasks();
        saveAllTask = new BukkitRunnable() {
            @Override
            public void run() {
                Sync sync = SyncRoundRobin.getNext();
                if (sync == null) return;
                int delay = 1;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> {
                        if (player == null || !player.isOnline()) return;
                        log.trace("Forcing save for {} with sync {}", player.getName(), sync.getClass().getSimpleName());
                        sync.forceSave(player.getUniqueId());
                    }, delay);
                    delay += 1;
                }

            }
        }.runTaskTimer(ARC.plugin, 20 * 60, 20 * 60 );
    }

    public static void saveAll() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        syncMap.values().forEach(sync -> {
            for (Player player : players) {
                sync.forceSave(player.getUniqueId());
            }
        });
    }

    public static void stopSaveAllTasks() {
        if (saveAllTask != null && !saveAllTask.isCancelled()) saveAllTask.cancel();
    }

    static class SyncRoundRobin {
        static Sync previous;

        public static Sync getNext() {
            if (syncMap.isEmpty()) return null;
            if (previous == null) {
                previous = syncMap.values().iterator().next();
                return previous;
            }
            for (Sync sync : syncMap.values()) {
                if (sync == previous) continue;
                previous = sync;
                return sync;
            }
            previous = syncMap.values().iterator().next();
            return previous;
        }

    }


}
