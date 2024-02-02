package arc.arc.treasurechests.locationpools;

import arc.arc.ARC;
import arc.arc.configs.LocationPoolConfig;
import arc.arc.util.ParticleManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LocationPoolManager {

    private static Map<String, LocationPool> locationPools = new ConcurrentHashMap<>();
    private static Map<UUID, String> editingPlayers = new HashMap<>();

    private static BukkitTask showTask;


    private LocationPoolManager() {
    }


    public static void init() {
        startShowTask();
    }


    public static void addPool(LocationPool locationPool){
        //System.out.println("Adding pool "+locationPool.getId());
        locationPools.put(locationPool.getId(), locationPool);
    }

    public static List<LocationPool> getAll(){
        //System.out.println("Getting all");
        return new ArrayList<>(locationPools.values());
    }


    public static void cancelEditing(UUID uuid) {
        editingPlayers.remove(uuid);
    }

    public static String getEditing(UUID uuid) {
        return editingPlayers.get(uuid);
    }

    public static void setEditing(UUID uuid, String id) {
        editingPlayers.put(uuid, id);
    }

    public static void addLocation(String id, Location location) {
        LocationPool locationPool = getPool(id);
        if(locationPool == null) locationPool = createPool(id);
        locationPool.addLocation(location);
    }

    public static boolean removeLocation(String id, Location location){
        LocationPool locationPool = getPool(id);
        if(locationPool == null){
            throw new IllegalArgumentException("No such pool! "+id);
        }
        return locationPool.removeLocation(location);
    }

    public static List<Location> getNearbyLocations(String id, Location location) {
        LocationPool locationPool = getPool(id);
        if(locationPool == null)return List.of();
        return locationPool.nearbyLocations(location);
    }

    public static LocationPool getPool(String id) {
        return locationPools.get(id);
    }

    public static LocationPool createPool(String id){
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
                            ParticleManager.queue(ParticleManager.ParticleDisplay.builder()
                                    .count(10)
                                    .extra(0)
                                    .offsetX(0.1).offsetY(0.1).offsetZ(0.1)
                                    .location(loc)
                                    .particle(Particle.END_ROD)
                                    .players(List.of(player))
                                    .build())
                    );
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 10L, 5L);
    }


    public static boolean delete(String id) {
        if(!locationPools.containsKey(id)) return false;
        //System.out.println(locationPools);
        locationPools.remove(id);
        //System.out.println(locationPools);
        ARC.plugin.locationPoolConfig.deleteFile(id);
        //System.out.println(locationPools);
        return true;
    }
}
