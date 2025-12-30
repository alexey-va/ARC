package ru.arc.autobuild;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.destroystokyo.paper.ParticleBuilder;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.hooks.packetevents.BlockDisplayReq;
import ru.arc.util.LocationUtils;
import ru.arc.util.ParticleManager;

import static ru.arc.util.BlockUtils.rotateBlockData;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@RequiredArgsConstructor
public class Display {

    private static final Cache<UUID, Integer> displayCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, java.util.concurrent.TimeUnit.MINUTES)
            .build();
    private final ConstructionSite site;

    private BukkitTask displayTask;
    List<LocationUtils.LocationData> borderLocations;
    List<Location> centerLocations;
    List<Integer> entityIds = new ArrayList<>();

    private Config config;

    private Config getConfig() {
        if (config == null) {
            if (ARC.plugin == null) {
                // Return a dummy config for testing
                return ConfigManager.of(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")), "auto-build" +
                        ".yml");
            }
            config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");
        }
        return config;
    }

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
        try {
            removeDisplays();
        } catch (Exception e) {
            error(e.getMessage());
        }
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

                Particle particle = getConfig().particle("display.border-particle", Particle.FLAME);
                int count = getConfig().integer("display.border-particle-count", 1);
                int countCorner = getConfig().integer("display.border-particle-corner-count", 3);
                double offset = getConfig().real("display.border-particle-offset", 0.0);
                double offsetCorner = getConfig().real("display.border-particle-corner-offset", 0.07);
                for (LocationUtils.LocationData location : borderLocations) {
                    if (!location.getCorner() && site.player.getLocation().distanceSquared(location.getLocation()) > 300)
                        continue;
                    ParticleManager.queue(new ParticleBuilder(particle)
                            .location(location.getLocation())
                            .extra(0.0)
                            .offset(location.getCorner() ? offsetCorner : offset,
                                    location.getCorner() ? offsetCorner : offset,
                                    location.getCorner() ? offsetCorner : offset)
                            .count(location.getCorner() ? countCorner : count)
                            .receivers(List.of(site.getPlayer())));
                }

                Particle centerParticle = getConfig().particle("display.center-particle", Particle.NAUTILUS);
                count = getConfig().integer("display.center-particle-count", 1);
                for (Location location : centerLocations) {
                    if (site.player.getLocation().distanceSquared(location) > 300) continue;
                    ParticleManager.queue(new ParticleBuilder(centerParticle)
                            .location(location)
                            .extra(0.0)
                            .offset(0.0, 0.0, 0.0)
                            .count(count)
                            .receivers(List.of(site.getPlayer())));
                }

            }
        }.runTaskTimer(ARC.plugin, 0L, getConfig().integer("display.border-particle-interval", 5));
    }

    private void placeDisplayEntities(int seconds) {
        if (HookRegistry.viaVersionHook != null) {
            if (HookRegistry.viaVersionHook.getPlayerVersion(site.player) < 761) return;
        }
        if (HookRegistry.packetEventsHook == null) return;
        ConstructionSite.Corners corners = site.getCorners();

        int maxPer10Min = getConfig().integer("display.max-per-10-min", 10);
        Integer curShows = displayCache.getIfPresent(site.player.getUniqueId());
        if(curShows != null && curShows >= maxPer10Min) {
            site.player.sendMessage(getConfig().componentDef("messages.display-limit-2", "<gray>\uD83D\uDEE0 " +
                    "<red>Превышен лимит показа блоков. Строение не будет показано"));
            return;
        }

        final int minX = corners.corner1().x();
        final int minY = corners.corner1().y();
        final int minZ = corners.corner1().z();
        final int maxX = corners.corner2().x();
        final int maxY = corners.corner2().y();
        final int maxZ = corners.corner2().z();

        int totalBlockAmount = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int maxBlocks = getConfig().integer("display.max-blocks", 30_000);

        List<BlockDisplayReq> reqs = new ArrayList<>();
        loop:
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location location = new Location(site.getWorld(), x + site.getCenterBlock().x(),
                            y + site.getCenterBlock().y(), z + site.getCenterBlock().z());

                    int fullRotation = site.fullRotation();

                    BlockData data = BukkitAdapter.adapt(site.getBuilding().getBlock(BlockVector3.at(x, y, z), fullRotation));
                    rotateBlockData(data, fullRotation);
                    if (isFootOfBed(data)) continue;

                    Block current = location.getBlock();
                    if (current.getType().isSolid()) continue;
                    reqs.add(new BlockDisplayReq(location, data));
                    if (reqs.size() >= maxBlocks) {
                        site.player.sendMessage(
                                getConfig().componentDef("messages.display-limit", "<gray>\uD83D\uDEE0 <red>Слишком " +
                                                "много блоков в строении. Показывается лишь часть. <gray>" +
                                                "(<amount>/<max>)",
                                        "<amount>", String.valueOf(totalBlockAmount),
                                        "<max>", String.valueOf(maxBlocks))
                        );
                        break loop;
                    }
                }
            }
        }
        int newShows = curShows == null ? 0 : curShows + reqs.size();
        displayCache.put(site.player.getUniqueId(), newShows);

        entityIds = HookRegistry.packetEventsHook.createDisplayBlocks(reqs, site.player);

        new BukkitRunnable() {
            @Override
            public void run() {
                info("Removing display due to timeout {}", seconds);
                removeDisplays();
            }
        }.runTaskLater(ARC.plugin, seconds * 20L);
    }

    private static boolean isFootOfBed(BlockData data) {
        if (data instanceof Bed bed) {
            return bed.getPart() == Bed.Part.FOOT;
        }
        return false;
    }


    private void removeDisplays() {
        if (HookRegistry.packetEventsHook != null) {
            HookRegistry.packetEventsHook.removeDisplayBlocks(entityIds, site.player);
        }
    }
}
