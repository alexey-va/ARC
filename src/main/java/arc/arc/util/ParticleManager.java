package arc.arc.util;

import arc.arc.ARC;
import com.destroystokyo.paper.ParticleBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ParticleManager {

    private static Queue<ParticleDisplay> queue = new ConcurrentLinkedQueue<>();
    private static List<Particle> particlePool = new ArrayList<>() {{
        add(Particle.CRIT);
        add(Particle.FLAME);
        add(Particle.END_ROD);
    }};
    private static BukkitTask task;

    private static void showParticles(ParticleDisplay particleDisplay) {
        try {
            if (particleDisplay == null) return;
            Particle particle = particleDisplay.particle == null ?
                    particlePool.get((new Random().nextInt(0, particlePool.size()))) :
                    particleDisplay.particle;
            new ParticleBuilder(particle)
                    .count(particleDisplay.count)
                    .location(particleDisplay.location)
                    .offset(particleDisplay.offsetX, particleDisplay.offsetY, particleDisplay.offsetZ)
                    .receivers(particleDisplay.players)
                    .data(particleDisplay.data)
                    .extra(particleDisplay.extra)
                    .spawn();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setupParticleManager() {
        if (task != null && !task.isCancelled()) task.cancel();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                while (!queue.isEmpty()) showParticles(queue.poll());
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 1L);
    }

    public static void queue(Player player, Location location) {
        queue.offer(new ParticleDisplay(player, location));
    }

    public static void queue(ParticleDisplay display) {
        queue.offer(display);
    }

    public static void queue(Player player, Collection<Location> locations) {
        for (Location location : locations) {
            queue(player, location);
        }
    }

    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParticleDisplay {
        Collection<Player> players;
        Location location;
        @Builder.Default
        double offsetX = 0.3, offsetY = 0.3, offsetZ = 0.3;
        @Builder.Default
        double extra = 0.0;
        @Builder.Default
        Particle particle = Particle.FLAME;
        @Builder.Default
        int count = 10;
        @Builder.Default
        Pattern pattern = Pattern.POINT;
        @Builder.Default
        Object data = null;

        public ParticleDisplay(Player player, Location location) {
            this.players = List.of(player);
            this.location = location;
        }
    }

    public enum Pattern {
        BLOCK_OUTLINE, POINT
    }

}
