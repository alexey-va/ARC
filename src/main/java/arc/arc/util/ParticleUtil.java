package arc.arc.util;

import arc.arc.ARC;
import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ParticleUtil {

    private static Queue<ParticleDisplay> queue = new ConcurrentLinkedQueue<>();
    private static List<Particle> particlePool = new ArrayList<>(){{
       add(Particle.CRIT);
       add(Particle.FLAME);
       add(Particle.END_ROD);
    }};
    private static BukkitTask task;

    private static void showParticles(ParticleDisplay particleDisplay){
        if(particleDisplay == null) return;
        Particle particle = particlePool.get((new Random().nextInt(0, particlePool.size())));
        new ParticleBuilder(particle).count(5).location(particleDisplay.location)
                .offset(0.3,0.3,0.3).receivers(particleDisplay.player).extra(0.1).spawn();
    }

    public static void setupParticleManager(){
        if(task != null && !task.isCancelled()) task.cancel();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                showParticles(queue.poll());
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 1L);
    }

    public static void queue(Player player, Location location){
        queue.offer(new ParticleDisplay(player, location));
    }

    public static class ParticleDisplay{
        Player player;
        Location location;

        public ParticleDisplay(Player player, Location location){
            this.player = player;
            this.location = location;
        }
    }

}
