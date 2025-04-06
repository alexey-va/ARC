package ru.arc.common.locationpools;

import ru.arc.ARC;
import ru.arc.common.ServerLocation;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.ParticleManager;
import com.destroystokyo.paper.ParticleBuilder;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LocationPoolManager {

    private static final Map<String, LocationPool> locationPools = new ConcurrentHashMap<>();
    private static final Map<UUID, String> editingPlayers = new ConcurrentHashMap<>();

    private static final Cache<UUID, String> recentEdits = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "location-pools.yml");

    private static BukkitTask showTask;
    private static BukkitTask timeoutTask;

    public static void init() {
        startShowTask();
    }


    public static void addPool(LocationPool locationPool) {
        locationPools.put(locationPool.getId(), locationPool);
    }

    public static List<LocationPool> getAll() {
        return new ArrayList<>(locationPools.values());
    }


    public static void cancelEditing(UUID uuid, boolean timeout) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            if (!timeout)
                player.sendMessage(config.componentDef("messages.cancel-editing", "<red>Редактирование пула локаций %name% отменено!",
                        "%name%", editingPlayers.getOrDefault(uuid, "null")));
            else
                player.sendMessage(config.componentDef("messages.timeout-editing", "<red>Редактирование пула локаций %name% завершено из-за неактивности!",
                        "%name%", editingPlayers.getOrDefault(uuid, "null")));
        }
        editingPlayers.remove(uuid);
    }

    public static String getEditing(UUID uuid) {
        return editingPlayers.get(uuid);
    }

    public static void setEditing(UUID uuid, String id) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null)
            player.sendMessage(config.componentDef("messages.start-editing", "<green>Начато редактирование пула локаций %name%!",
                    "%name%", id));
        editingPlayers.put(uuid, id);
    }

    public static void processLocationPool(BlockPlaceEvent event) {
        String poolId = LocationPoolManager.getEditing(event.getPlayer().getUniqueId());
        if (poolId == null) return;

        boolean add = event.getBlockPlaced().getType() == Material.GOLD_BLOCK;
        boolean remove = event.getBlockPlaced().getType() == Material.REDSTONE_BLOCK;
        if (!add && !remove) {
            event.getPlayer().sendMessage(config.componentDef("messages.invalid-block", "<red>Вы поставили неверный блок! <gray>При редактировании используйте только блоки <gold>золото<gray> и <red>красный камень<gray>!"));
            return;
        }

        event.setCancelled(true);
        if (add) {
            LocationPoolManager.addLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            int newCount = LocationPoolManager.getPool(poolId).getLocations().size();
            event.getPlayer().sendMessage(config.componentDef("messages.block-added", "<green>Блок добавлен! <gray>(%count%)",
                    "%count%", String.valueOf(newCount)));
        } else {
            boolean res = LocationPoolManager.removeLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            int newCount = LocationPoolManager.getPool(poolId).getLocations().size();
            if (res)
                event.getPlayer().sendMessage(config.componentDef("messages.block-removed", "<green>Блок удален! <gray>(%count%)",
                        "%count%", String.valueOf(newCount)));
            else
                event.getPlayer().sendMessage(config.componentDef("messages.not-in-pool", "<red>Блок не в пуле! <gray>(%count%)",
                        "%count%", String.valueOf(newCount)));
        }
        recentEdits.put(event.getPlayer().getUniqueId(), poolId);
    }

    public static void addLocation(String id, Location location) {
        LocationPool locationPool = getPool(id);
        if (locationPool == null) locationPool = createPool(id);
        locationPool.addLocation(location, 1);
    }

    public static boolean removeLocation(String id, Location location) {
        LocationPool locationPool = getPool(id);
        if (locationPool == null) {
            throw new IllegalArgumentException("No such pool! " + id);
        }
        return locationPool.removeLocation(location);
    }

    public static List<Location> getNearbyLocations(String id, Location location) {
        LocationPool locationPool = getPool(id);
        if (locationPool == null) return List.of();
        return locationPool.nearbyLocations(location, 50).stream()
                .filter(ServerLocation::isSameServer)
                .map(ServerLocation::toLocation)
                .toList();
    }

    public static LocationPool getPool(String id) {
        if (id == null) return null;
        return locationPools.get(id);
    }

    public static LocationPool createPool(String id) {
        LocationPool locationPool = locationPools.get(id);
        if (locationPool == null) {
            locationPool = new LocationPool(id);
            locationPools.put(id, locationPool);
        }
        return locationPool;
    }

    public static void clear() {
        locationPools.clear();
    }

    public static void startShowTask() {
        if (showTask != null && !showTask.isCancelled()) showTask.cancel();

        showTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : editingPlayers.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        editingPlayers.remove(uuid);
                        continue;
                    }

                    Location location = player.getLocation();
                    List<Location> locations = getNearbyLocations(editingPlayers.get(uuid), location);
                    locations.forEach(loc ->
                            ParticleManager.queue(new ParticleBuilder(Particle.END_ROD)
                                    .location(loc)
                                    .count(10)
                                    .extra(0)
                                    .offset(0.1, 0.1, 0.1)
                                    .receivers(List.of(player)))
                    );
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 10L, 5L);
    }

    public static void startTimeoutTask() {
        if (timeoutTask != null && !timeoutTask.isCancelled()) timeoutTask.cancel();

        timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : editingPlayers.keySet()) {
                    String ifPresent = recentEdits.getIfPresent(uuid);
                    if (ifPresent == null) {
                        cancelEditing(uuid, true);
                    }
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 10L, 5L);
    }


    public static boolean delete(String id) {
        if (!locationPools.containsKey(id)) return false;
        locationPools.remove(id);
        ARC.plugin.locationPoolConfig.deleteFile(id);
        for (UUID uuid : editingPlayers.keySet()) {
            if (editingPlayers.get(uuid).equals(id)) {
                cancelEditing(uuid, false);
            }
        }
        return true;
    }
}
