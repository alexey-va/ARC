package ru.arc.hooks.elitemobs;

import com.destroystokyo.paper.ParticleBuilder;
import com.magmaguy.elitemobs.treasurechest.TreasureChest;
import com.magmaguy.elitemobs.wormhole.Wormhole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.ParticleManager;
import ru.arc.xserver.playerlist.PlayerManager;

import java.util.List;
import java.util.stream.Stream;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@Slf4j
@RequiredArgsConstructor
public class EMWormholes {

    private static BukkitTask wormholeTask;
    static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "elitemobs.yml");

    public void init() {
        cancel();

        info("Starting wormhole task");
        wormholeTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    runWormholes();
                    runChests();
                } catch (Exception e) {
                    error("Error running wormholes", e);
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, config.integer("wormholes.period-ticks", 2));
    }

    public void cancel() {
        if (wormholeTask != null && !wormholeTask.isCancelled()) wormholeTask.cancel();
    }

    private void runChests() {
        List<Player> players = PlayerManager.getOnlinePlayersThreadSafe();
        try {
            for (var entry : TreasureChest.getTreasureChestHashMap().entrySet()) {
                Location location = entry.getKey();
                if (location == null) continue;
                String worldName = entry.getValue().getWorldName();
                if (worldName == null) continue;
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;
                location.setWorld(world);
                for (Player p : players) {
                    Location playerLocation = p.getLocation();
                    if(playerLocation.getWorld() != world) continue;
                    if (location.distanceSquared(playerLocation) > config.real("chests.distance", 40.0) * config.real("chests.distance", 40.0))
                        continue;

                    List<String> restockTimers = entry.getValue().getCustomTreasureChestConfigFields().getRestockTimers();
                    if(restockTimers == null) continue;
                    boolean found = false;
                    for(String s : restockTimers) {
                        String[] split = s.split(":");
                        if(split.length < 1) continue;
                        if(split[0].equalsIgnoreCase(p.getUniqueId().toString())) {
                            found = true;
                            break;
                        }
                    }
                    if(found) continue;
                    ParticleManager.queue(new ParticleBuilder(config.particle("chests.particle", Particle.END_ROD))
                            .count(config.integer("chests.particle-count", 10))
                            .location(location.toCenterLocation())
                            .offset(config.real("chests.particle-offset", 0.5f),
                                    config.real("chests.particle-offset", 0.5f),
                                    config.real("chests.particle-offset", 0.5f))
                            .extra(config.real("chests.particle-extra", 0.05))
                            .receivers(players)
                            .spawn());
                    try {
                        String sound = config.string("chests.sound", "block.beacon.ambient");
                        Sound sound1 = Registry.SOUNDS.get(Key.key(sound));
                        if (sound1 == null) continue;
                        p.playSound(p.getLocation(), sound1, 1.0f, 1.0f);
                    } catch (Exception e) {
                        error("Error playing sound", e);
                    }
                }
            }
        } catch (Exception e) {
            error("Error running chests", e);
        }
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
