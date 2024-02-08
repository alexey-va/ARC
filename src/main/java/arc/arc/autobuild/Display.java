package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.ParticleManager;
import arc.arc.util.Utils;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static arc.arc.util.Utils.rotateBlockData;

@RequiredArgsConstructor
public class Display {

    private final ConstructionSite site;


    private BukkitTask displayTask;
    List<BlockDisplay> displays = new ArrayList<>();
    List<Utils.LocationData> borderLocations;
    List<Location> centerLocations;

    Set<Material> transparentMats = Set.of(Material.AIR, Material.SHORT_GRASS, Material.TALL_GRASS);

    public void showBorder(int seconds) {
        stop();
        displayBorder(seconds);
    }

    public void showBorderAndDisplay(int seconds) {
        stop();
        displayBorder(seconds);
        placeDisplayEntities(seconds);
    }

    public void stop() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
        removeDisplays();
    }

    public void displayBorder(int seconds) {
        if(borderLocations == null) borderLocations = getBorderLocations();
        if(centerLocations == null) centerLocations = getCenterLocations();
        final AtomicInteger integer = new AtomicInteger(0);
        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (integer.addAndGet(10) > seconds * 20) this.cancel();
                for (Utils.LocationData location : borderLocations) {
                    if (!location.corner() && site.player.getLocation().distanceSquared(location.location()) > 300) continue;
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
        if(HookRegistry.viaVersionHook != null){
            if(HookRegistry.viaVersionHook.getPlayerVersion(site.player) < 761) return;
        }
        ConstructionSite.Corners corners = site.getCorners();

        final int minX = corners.corner1().getBlockX();
        final int minY = corners.corner1().getBlockY();
        final int minZ = corners.corner1().getBlockZ();
        final int maxX = corners.corner2().getBlockX();
        final int maxY = corners.corner2().getBlockY();
        final int maxZ = corners.corner2().getBlockZ();


        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location location = new Location(site.getWorld(), x + site.getCenterBlock().getBlockX(),
                            y + site.getCenterBlock().getBlockY(), z + site.getCenterBlock().getBlockZ());

                    BlockData data = BukkitAdapter.adapt(site.getBuilding().getBlock(BlockVector3.at(x, y, z), site.getRotation()));
                    rotateBlockData(data, site.getRotation());

                    Block current = location.getBlock();
                    if(current.getType().isSolid()){
                        continue;
                    }

                    BlockDisplay display = site.getWorld().spawn(location, BlockDisplay.class);
                    display.setBlock(data);
                    display.getPersistentDataContainer().set(new NamespacedKey(ARC.plugin, "db"), PersistentDataType.STRING, site.getPlayer().getName());
                    displays.add(display);
                    //Utils.sendDisplayBlock(location, data, site.player);
                }
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                removeDisplays();
            }
        }.runTaskLater(ARC.plugin, seconds * 20L);
    }

    List<Location> getCenterLocations(){
        Location center1 = site.centerBlock.toBlockLocation().clone().add(-0.05,-1.05, -0.05);
        Location center2 = center1.clone().add(1.1,1.1, 1.1);
        return Utils.getBorderLocations(center1, center2, 6);
    }

    List<Utils.LocationData> getBorderLocations() {
        ConstructionSite.Corners corners = site.getCorners();

        Location corner1 = new Location(site.getWorld(),
                corners.corner1().getBlockX() + site.getCenterBlock().getBlockX(),
                corners.corner1().getBlockY() + site.getCenterBlock().getBlockY(),
                corners.corner1().getBlockZ() + site.getCenterBlock().getBlockZ());

        Location corner2 = new Location(site.getWorld(),
                corners.corner2().getBlockX() + site.getCenterBlock().getBlockX() + 1,
                corners.corner2().getBlockY() + site.getCenterBlock().getBlockY() + 1,
                corners.corner2().getBlockZ() + site.getCenterBlock().getBlockZ() + 1);

        return Utils.getBorderLocationsWithCornerData(corner1, corner2, 2, 3);
    }

    public void removeDisplays() {
        displays.forEach(e -> {
            try {
                e.remove();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        displays.clear();
    }


}
