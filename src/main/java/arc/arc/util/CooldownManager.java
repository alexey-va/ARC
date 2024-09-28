package arc.arc.util;

import arc.arc.ARC;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {

    private static BukkitTask countdownTask;
    private static Map<UUID, Map<String, Cooldown>> cooldownMap = new ConcurrentHashMap<>();

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
        Map<String, Cooldown> stringCooldownMap = cooldownMap.get(uuid);
        if (stringCooldownMap == null) return 0;
        Cooldown cooldown = stringCooldownMap.get(id);
        if (cooldown == null) return 0;
        if (cooldown.ticksLeft <= 0) {
            stringCooldownMap.remove(id);
            return 0;
        }
        return cooldown.ticksLeft;
    }

    public static void addCooldown(UUID uuid, String id, long ticks) {
        cooldownMap.compute(uuid, (k, v) -> {
            if (v == null) v = new ConcurrentHashMap<>();
            v.put(id, new Cooldown(true, ticks, id));
            return v;
        });
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
