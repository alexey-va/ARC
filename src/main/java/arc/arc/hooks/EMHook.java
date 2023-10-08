package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.Config;
import com.destroystokyo.paper.ParticleBuilder;
import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import com.magmaguy.elitemobs.wormhole.Wormhole;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;

public class EMHook implements Listener, ArcModule {

    private static BukkitTask wormholeTask;

    public EMHook() {
        boot();
    }

    public void boot() {
        if (Config.sendWormholes && (wormholeTask == null || wormholeTask.isCancelled())) {
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
            }.runTaskTimer(ARC.plugin, 20L, Config.wormholePeriod);
        }
    }

    private void runWormholes() {
        Collection<ParticleBuilder> particleBuilders = new ArrayList<>();
        for (Wormhole wormhole : Wormhole.getWormholes()) {
            try {
                if (wormhole.getWormholeEntry1().getLocation() == null || wormhole.getWormholeEntry2().getLocation() == null) {
                    continue;
                }
                double modifier = wormhole.getWormholeConfigFields().getSizeMultiplier();
                Collection<Player> players1 = wormhole.getWormholeEntry1().getLocation().getNearbyPlayers(40);
                players1.removeIf(player -> !player.hasLineOfSight(wormhole.getWormholeEntry1().getLocation()));
                particleBuilders.add(new ParticleBuilder(Particle.REDSTONE).color(wormhole.getParticleColor())
                        .receivers(players1).offset(Config.particleOffset*modifier, Config.particleOffset*modifier, Config.particleOffset*modifier)
                        .location(wormhole.getWormholeEntry1().getLocation()).count((int)(Config.particleCount*modifier*modifier)));

                Collection<Player> players2 = wormhole.getWormholeEntry2().getLocation().getNearbyPlayers(40);
                players2.removeIf(player -> !player.hasLineOfSight(wormhole.getWormholeEntry2().getLocation()));
                particleBuilders.add(new ParticleBuilder(Particle.REDSTONE).color(wormhole.getParticleColor())
                        .receivers(players2).offset(Config.particleOffset*modifier, Config.particleOffset*modifier, Config.particleOffset*modifier)
                        .location(wormhole.getWormholeEntry2().getLocation()).count((int)(Config.particleCount*modifier*modifier)));
            } catch (Exception ignored){

            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ParticleBuilder builder : particleBuilders) {
                    builder.spawn();
                }
            }
        }.runTaskAsynchronously(ARC.plugin);
    }

    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        if (Config.noExpWorlds.isEmpty()) return;
        if (Config.noExpWorlds.contains(event.getExplosionSourceLocation().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    public void cancel() {
        if (wormholeTask != null && !wormholeTask.isCancelled()) wormholeTask.cancel();
    }

}
