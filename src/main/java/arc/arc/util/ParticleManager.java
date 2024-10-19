package arc.arc.util;

import arc.arc.ARC;
import com.destroystokyo.paper.ParticleBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class ParticleManager {

    private static final Deque<ParticleBuilder> buildersQueue = new ConcurrentLinkedDeque<>();
    private static final Deque<ParticleBuilder> syncBuildersQueue = new ConcurrentLinkedDeque<>();

    private static BukkitTask task, syncTask;

    public static void setupParticleManager() {
        if (task != null && !task.isCancelled()) task.cancel();
        if (syncTask != null && !syncTask.isCancelled()) syncTask.cancel();

        task = new BukkitRunnable() {
            @Override
            public void run() {
                while (!buildersQueue.isEmpty()) buildersQueue.poll().spawn();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 0L, 1L);

        syncTask = new BukkitRunnable() {
            @Override
            public void run() {
                int count = 0;
                while (!syncBuildersQueue.isEmpty()) {
                    syncBuildersQueue.poll().spawn();
                    count++;
                    if (count > 200) {
                        log.warn("Too many particles to show in one tick. Size: {}", syncBuildersQueue.size());
                        break;
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, 1L);
    }

    public static void queue(ParticleBuilder builder) {
        boolean res = buildersQueue.offer(builder);
        if (!res) log.warn("Failed to queue particle builder: {}", builder);
    }
}
