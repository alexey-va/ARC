package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.ParticleManager;
import arc.arc.util.Utils;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static arc.arc.util.Utils.rotateBlockData;

@Slf4j
@RequiredArgsConstructor
public class Display {

    private final ConstructionSite site;


    private BukkitTask displayTask;
    List<BlockDisplay> displays = new ArrayList<>();
    List<Utils.LocationData> borderLocations;
    List<Location> centerLocations;

    Set<Material> transparentMats = Set.of(Material.AIR, Material.SHORT_GRASS, Material.TALL_GRASS);

    public void showBorder(int seconds) {
        stopTask();
        displayBorder(seconds);
    }

    public void showBorderAndDisplay(int seconds) {
        stopTask();
        displayBorder(seconds);
        placeDisplayEntities(seconds);
    }

    public void stopTask() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
    }

    public void stop(){
        stopTask();
        removeDisplays();
    }

    public void displayBorder(int seconds) {
        if (borderLocations == null) borderLocations = site.getBorderLocations();
        if (centerLocations == null) centerLocations = site.getCenterLocations();
        final AtomicInteger integer = new AtomicInteger(0);
        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (integer.addAndGet(10) > seconds * 20) this.cancel();
                for (Utils.LocationData location : borderLocations) {
                    if (!location.corner() && site.player.getLocation().distanceSquared(location.location()) > 300)
                        continue;
                    ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                            .location(location.location())
                            .offsetX(location.corner() ? 0.07 : 0.0).offsetY(location.corner() ? 0.07 : 0.0).offsetZ(location.corner() ? 0.07 : 0.0)
                            .count(location.corner() ? 3 : 1)
                            .extra(0.0)
                            .particle(Particle.FLAME)
                            .players(List.of(site.getPlayer()))
                            .build());
                }
                for (Location location : centerLocations) {
                    if (site.player.getLocation().distanceSquared(location) > 300) continue;
                    ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                            .location(location)
                            .offsetX(0.0).offsetY(0.0).offsetZ(0.0)
                            .count(1)
                            .extra(0.0)
                            .particle(Particle.NAUTILUS)
                            .players(List.of(site.getPlayer()))
                            .build());
                }

            }
        }.runTaskTimer(ARC.plugin, 0L, 5L);

    }

    public void placeDisplayEntities(int seconds) {
        if (HookRegistry.viaVersionHook != null) {
            if (HookRegistry.viaVersionHook.getPlayerVersion(site.player) < 761) return;
        }
        ConstructionSite.Corners corners = site.getCorners();

        final int minX = corners.corner1().x();
        final int minY = corners.corner1().y();
        final int minZ = corners.corner1().z();
        final int maxX = corners.corner2().x();
        final int maxY = corners.corner2().y();
        final int maxZ = corners.corner2().z();


        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location location = new Location(site.getWorld(), x + site.getCenterBlock().x(),
                            y + site.getCenterBlock().y(), z + site.getCenterBlock().z());

                    int fullRotation = site.getRotation() + site.getSubRotation();

                    BlockData data = BukkitAdapter.adapt(site.getBuilding().getBlock(BlockVector3.at(x, y, z), fullRotation));
                    rotateBlockData(data, fullRotation);

                    Block current = location.getBlock();
                    if (current.getType().isSolid()) {
                        continue;
                    }

                    BlockDisplay display = site.getWorld().spawn(location, BlockDisplay.class, entity -> {
                        entity.setBlock(data);
                        entity.getPersistentDataContainer().set(BuildingManager.displayKey, PersistentDataType.STRING, site.getPlayer().getName());
                    });


                    displays.add(display);
                    //Utils.sendDisplayBlock(location, data, site.player);
                }
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                log.info("Removing display due to timeout {}", seconds);
                removeDisplays();
            }
        }.runTaskLater(ARC.plugin, seconds * 20L);
    }


    public void removeDisplays() {
        Set<Chunk> chunks =
                displays.stream().map(BlockDisplay::getLocation).map(Location::getChunk).collect(Collectors.toSet());
        chunks.forEach(c -> c.setForceLoaded(false));
        AtomicInteger integer = new AtomicInteger(0);
        log.info("Starting display removal task");
        new BukkitRunnable() {
            @Override
            public void run() {
                if (chunks.stream().allMatch(Chunk::isLoaded) || integer.get() > 20) {
                    log.info("Removing displays after {} iterations", integer.get());
                    chunks.forEach(c -> c.setForceLoaded(false));
                    displays.forEach(e -> {
                        try {
                            e.remove();
                        } catch (Exception ex) {
                            log.error("Error removing display", ex);
                        }
                    });
                    displays.clear();
                    this.cancel();
                } else {
                    integer.incrementAndGet();
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, 10L);
    }


}
