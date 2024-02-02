package arc.arc.util;

import arc.arc.ARC;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class CooldownManager {

    private static BukkitTask countdownTask;
    private static Map<UUID, Map<String, Cooldown>> cooldownMap = new HashMap<>();

    private static void countdown(long step) {
        List<UUID> uuidToRemove = new ArrayList<>();
        for (var mapEntry : cooldownMap.entrySet()) {
            List<String> toRemove = new ArrayList<>();
            for (var cooldownEntry : mapEntry.getValue().entrySet()) {
                if (cooldownEntry.getValue().ticksLeft <= step) toRemove.add(cooldownEntry.getKey());
                else cooldownEntry.getValue().ticksLeft -= step;
            }
            toRemove.forEach(s -> mapEntry.getValue().remove(s));
            if (mapEntry.getValue().isEmpty()) uuidToRemove.add(mapEntry.getKey());
        }
        uuidToRemove.forEach(uuid -> cooldownMap.remove(uuid));
    }

    public static long cooldown(UUID uuid, String id) {
        if (!cooldownMap.containsKey(uuid) || !cooldownMap.get(uuid).containsKey(id)) return 0;
        return cooldownMap.get(uuid).get(id).ticksLeft;
    }

    public static void addCooldown(UUID uuid, String id, long ticks) {
        if (!cooldownMap.containsKey(uuid)) cooldownMap.put(uuid, new HashMap<>());
        cooldownMap.get(uuid).put(id, new Cooldown(true, ticks, id));
    }

    public static void setupTask(long period) {
        if (countdownTask != null && !countdownTask.isCancelled()) countdownTask.cancel();
        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                countdown(period);
            }
        }.runTaskTimer(ARC.plugin, period, period);
    }


    static class Cooldown {
        boolean resetOnExit;
        long ticksLeft;
        String cooldownId;

        public Cooldown(boolean resetOnExit, long ticksLeft, String cooldownId) {
            this.resetOnExit = resetOnExit;
            this.ticksLeft = ticksLeft;
            this.cooldownId = cooldownId;
        }
    }


}
