package arc.arc.hooks.elitemobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.util.ParticleManager;
import arc.arc.xserver.playerlist.PlayerManager;
import com.destroystokyo.paper.ParticleBuilder;
import com.magmaguy.elitemobs.wormhole.Wormhole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class EMWormholes {

    private static BukkitTask wormholeTask;
    static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "elitemobs.yml");

    public void init() {
        cancel();

        log.info("Starting wormhole task");
        wormholeTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    runWormholes();
                } catch (Exception e) {
                    log.error("Error running wormholes", e);
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, config.integer("wormholes.period-ticks", 2));

    }

    public void cancel() {
        if (wormholeTask != null && !wormholeTask.isCancelled()) wormholeTask.cancel();
    }


    private void runWormholes() {
        if (Wormhole.getWormholes() == null) return;
        List<Player> players = PlayerManager.getOnlinePlayersThreadSafe();
        for (Wormhole wormhole : Wormhole.getWormholes()) {
            if (wormhole.getWormholeEntry1() == null || wormhole.getWormholeEntry2() == null) continue;
            if (wormhole.getWormholeEntry1().getLocation() == null || wormhole.getWormholeEntry2().getLocation() == null)
                continue;
            Location l1 = wormhole.getWormholeEntry1().getLocation();
            Location l2 = wormhole.getWormholeEntry2().getLocation();

            Particle particle = config.particle("wormholes.particle", Particle.DUST);
            float offset = (float) config.real("wormholes.particle-offset", 1.0f);
            double extra = config.real("wormholes.particle-extra", 0.05);
            int count = config.integer("wormholes.particle-count", 30);

            double modifier = wormhole.getWormholeConfigFields().getSizeMultiplier();
            Stream.of(l1, l2)
                    .filter(l -> l.getWorld() != null)
                    .forEach(l -> ParticleManager.queue(new ParticleBuilder(particle)
                            .count(count)
                            .location(l)
                            .extra(extra)
                            .offset(offset * modifier, offset * modifier, offset * modifier)
                            .receivers(players)
                            .color(wormhole.getParticleColor())
                            .spawn()));
        }
    }

}
