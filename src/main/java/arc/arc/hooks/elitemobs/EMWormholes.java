package arc.arc.hooks.elitemobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import com.destroystokyo.paper.ParticleBuilder;
import com.magmaguy.elitemobs.wormhole.Wormhole;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;

public class EMWormholes {

    private static BukkitTask wormholeTask;

    public void init() {
        cancel();

        System.out.println("Setting up wormhole task!");
        wormholeTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    runWormholes();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, Config.wormholePeriod);

    }

    public void cancel() {
        if (wormholeTask != null && !wormholeTask.isCancelled()) wormholeTask.cancel();
    }


    private void runWormholes() {
        Collection<ParticleBuilder> particleBuilders = new ArrayList<>();
        if (Wormhole.getWormholes() == null) return;
        for (Wormhole wormhole : Wormhole.getWormholes()) {
            if (wormhole.getWormholeEntry1() == null || wormhole.getWormholeEntry2() == null) continue;
            if (wormhole.getWormholeEntry1().getLocation() == null || wormhole.getWormholeEntry2().getLocation() == null)
                continue;
            Location l1 = wormhole.getWormholeEntry1().getLocation();
            Location l2 = wormhole.getWormholeEntry2().getLocation();

            double modifier = wormhole.getWormholeConfigFields().getSizeMultiplier();
            if(l1.getWorld() != null) {
                particleBuilders.add(new ParticleBuilder(Particle.REDSTONE).color(wormhole.getParticleColor())
                        .offset(Config.particleOffset * modifier, Config.particleOffset * modifier, Config.particleOffset * modifier)
                        .location(l1).count((int) (Config.particleCount * modifier * modifier)));
            }
            if(l2.getWorld() != null) {
                particleBuilders.add(new ParticleBuilder(Particle.REDSTONE).color(wormhole.getParticleColor())
                        .offset(Config.particleOffset * modifier, Config.particleOffset * modifier, Config.particleOffset * modifier)
                        .location(l2).count((int) (Config.particleCount * modifier * modifier)));
            }
        }
        for (ParticleBuilder builder : particleBuilders) {
            builder.spawn();
        }
    }

}
