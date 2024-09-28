package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.packetevents.BlockDisplayReq;
import arc.arc.util.ParticleManager;
import arc.arc.util.Utils;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static arc.arc.util.Utils.rotateBlockData;

@Slf4j
@RequiredArgsConstructor
public class Display {

    private final ConstructionSite site;

    private BukkitTask displayTask;
    List<Utils.LocationData> borderLocations;
    List<Location> centerLocations;
    List<Integer> entityIds = new ArrayList<>();

    Config config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");

    public void showBorder(int seconds) {
        stopTask();
        displayBorder(seconds);
    }

    public void showBorderAndDisplay(int seconds) {
        stopTask();
        displayBorder(seconds);
        placeDisplayEntities(seconds);
    }

    public void stop() {
        stopTask();
        removeDisplays();
    }

    private void stopTask() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
    }

    private void displayBorder(int seconds) {
        if (borderLocations == null) borderLocations = site.getBorderLocations();
        if (centerLocations == null) centerLocations = site.getCenterLocations();
        final AtomicInteger integer = new AtomicInteger(0);
        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (integer.addAndGet(10) > seconds * 20) this.cancel();

                Particle particle = config.particle("display.border-particle", Particle.FLAME);
                int count = config.integer("display.border-particle-count", 1);
                int countCorner = config.integer("display.border-particle-corner-count", 3);
                double offset = config.real("display.border-particle-offset", 0.0);
                double offsetCorner = config.real("display.border-particle-corner-offset", 0.07);
                for (Utils.LocationData location : borderLocations) {
                    if (!location.corner() && site.player.getLocation().distanceSquared(location.location()) > 300)
                        continue;
                    ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                            .location(location.location())
                            .offsetX(location.corner() ? offsetCorner : offset)
                            .offsetY(location.corner() ? offsetCorner : offset)
                            .offsetZ(location.corner() ? offsetCorner : offset)
                            .count(location.corner() ? countCorner : count)
                            .extra(0.0)
                            .particle(particle)
                            .players(List.of(site.getPlayer()))
                            .build());
                }

                Particle centerParticle = config.particle("display.center-particle", Particle.NAUTILUS);
                count = config.integer("display.center-particle-count", 1);
                for (Location location : centerLocations) {
                    if (site.player.getLocation().distanceSquared(location) > 300) continue;
                    ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                            .location(location)
                            .offsetX(0.0).offsetY(0.0).offsetZ(0.0)
                            .count(count)
                            .extra(0.0)
                            .particle(centerParticle)
                            .players(List.of(site.getPlayer()))
                            .build());
                }

            }
        }.runTaskTimer(ARC.plugin, 0L, config.integer("display.border-particle-interval", 5));
    }

    private void placeDisplayEntities(int seconds) {
        if (HookRegistry.viaVersionHook != null) {
            if (HookRegistry.viaVersionHook.getPlayerVersion(site.player) < 761) return;
        }
        if (HookRegistry.packetEventsHook == null) return;
        ConstructionSite.Corners corners = site.getCorners();

        final int minX = corners.corner1().x();
        final int minY = corners.corner1().y();
        final int minZ = corners.corner1().z();
        final int maxX = corners.corner2().x();
        final int maxY = corners.corner2().y();
        final int maxZ = corners.corner2().z();

        int totalBlockAmount = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (totalBlockAmount > config.integer("display.max-blocks", 30_000)) {
            log.info("Too many blocks to display: {} for building: {}", totalBlockAmount, site.getBuilding().getFileName());
            return;
        }

        List<BlockDisplayReq> reqs = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location location = new Location(site.getWorld(), x + site.getCenterBlock().x(),
                            y + site.getCenterBlock().y(), z + site.getCenterBlock().z());

                    int fullRotation = site.fullRotation();

                    BlockData data = BukkitAdapter.adapt(site.getBuilding().getBlock(BlockVector3.at(x, y, z), fullRotation));
                    rotateBlockData(data, fullRotation);

                    Block current = location.getBlock();
                    if (current.getType().isSolid()) continue;
                    reqs.add(new BlockDisplayReq(location, data));
                }
            }
        }

        entityIds = HookRegistry.packetEventsHook.createDisplayBlocks(reqs, site.player);

        new BukkitRunnable() {
            @Override
            public void run() {
                log.info("Removing display due to timeout {}", seconds);
                removeDisplays();
            }
        }.runTaskLater(ARC.plugin, seconds * 20L);
    }


    private void removeDisplays() {
        if (HookRegistry.packetEventsHook != null) {
            HookRegistry.packetEventsHook.removeDisplayBlocks(entityIds, site.player);
        }
    }
}
