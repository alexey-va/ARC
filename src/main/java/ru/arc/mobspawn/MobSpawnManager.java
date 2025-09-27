package ru.arc.mobspawn;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.arc.util.Logging.debug;

@Slf4j
public class MobSpawnManager {

    static BukkitTask task;
    static Config config;
    static TreeMap<Integer, EntityType> mobMap = new TreeMap<>();
    static Set<EntityType> mobSet = new HashSet<>();
    static int weightSum = 0;

    public static void init() {
        config = ConfigManager.of(ARC.plugin.getDataPath(), "mobspawn.yml");
        List<String> spawnedMobTypes = config.stringList("mobspawn.mobs");
        weightSum = 0;
        mobMap.clear();
        mobSet.clear();

        for (String mobType : spawnedMobTypes) {
            String[] split = mobType.split(":");
            String mob = split[0].toUpperCase();
            int weight = split.length > 1 ? Integer.parseInt(split[1]) : 1;
            EntityType entityType = EntityType.valueOf(mob);
            weightSum += weight;
            mobMap.put(weightSum, entityType);
            mobSet.add(entityType);
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                Set<String> worldNames = new HashSet<>(config.stringList("mobspawn.worlds"));
                int startHour = config.integer("mobspawn.start-hour", 13);
                int endHour = config.integer("mobspawn.end-hour", 0);
                boolean enabled = config.bool("mobspawn.enabled", true);
                if (!enabled) return;
                for (World world : Bukkit.getWorlds()) {
                    debug("Checking world {}", world.getName());
                    if (!worldNames.contains(world.getName())) continue;
                    boolean goodTime = world.getTime() >= startHour * 1000L || world.getTime() <= endHour * 1000L;
                    if (!goodTime) continue;
                    AtomicInteger count = new AtomicInteger(1);
                    AtomicInteger total = new AtomicInteger(0);
                    for (int i = 0; i < world.getPlayers().size(); i++) {
                        Player player = world.getPlayers().get(i);
                        final boolean finalPlayer = i == world.getPlayers().size() - 1;
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                total.addAndGet(trySpawn(player));
                                if (finalPlayer) debug("Spawned {} mobs in world {}", total.get(), world.getName());
                            }
                        }.runTaskLater(ARC.plugin, count.getAndIncrement());
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, config.integer("mobspawn.interval", 10) * 20L);
    }

    public static int trySpawn(Player player) {
        if (player.getLocation().getBlock().getBiome() == Biome.MUSHROOM_FIELDS) return 0;
        if (HookRegistry.landsHook != null && HookRegistry.landsHook.isClaimed(player.getLocation())) return 0;
        if (player.isFlying() || player.getGameMode() != GameMode.SURVIVAL) return 0;

        double radius = config.real("mobspawn.radius", 50);
        int threshold = config.integer("mobspawn.threshold", 5);
        List<Entity> nearbyEntities = player.getNearbyEntities(radius, radius, radius).stream()
                .filter(entity -> mobSet.contains(entity.getType()))
                .toList();
        debug("Found {} nearby mobs for {}", nearbyEntities.size(), player.getName());
        if (nearbyEntities.size() >= threshold) return 0;

        int amount = Math.min(threshold - nearbyEntities.size(), config.integer("mobspawn.amount", 2));
        debug("Spawning {} mobs near {}", amount, player.getName());

        if (config.bool("mobspawn.use-cmi-command", true)) {
            if (player.getLocation().getBlock().getLightLevel() > 7) {
                debug("Light level too high for mob spawn near {}", player.getName());
                return 0;
            }
            Map<EntityType, Integer> amountMap = new HashMap<>();
            for (int i = 0; i < amount; i++) {
                EntityType entityType = mobMap.ceilingEntry((int) (Math.random() * weightSum)).getValue();
                amountMap.put(entityType, amountMap.getOrDefault(entityType, 0) + 1);
            }
            for (Map.Entry<EntityType, Integer> entry : amountMap.entrySet()) {
                String sb = "cmi spawnmob " + entry.getKey().name() + " " + player.getName() + " q:" + entry.getValue() +
                        " sp:" + config.integer("mobspawn.cmi-spread", 30) + " -s";
                ARC.trySeverCommand(sb);
            }
            return amount;
        }

        Set<Location> locations = tryFindSpawnLocations(player, player.getLocation(), (int) radius, amount);
        debug("Spawning {} mobs near {}", locations.size(), player.getName());

        for (Location location : locations) {
            EntityType entityType = mobMap.ceilingEntry((int) (Math.random() * weightSum)).getValue();
            location.getWorld().spawnEntity(location, entityType);
        }
        return locations.size();
    }

    public static Set<Location> tryFindSpawnLocations(Player player, Location location, int radius, int amount) {
        Set<Location> locations = new HashSet<>();
        int n = amount * config.integer("mobspawn.try-multiplier", 30);
        for (int i = 0; i < n; i++) {
            double x = location.getX() + Math.random() * radius * 2 - radius;
            double y = location.getY() + Math.random() * radius * 2 - radius;
            double z = location.getZ() + Math.random() * radius * 2 - radius;
            Location loc = new Location(location.getWorld(), x, y, z);
            if (!loc.getBlock().getType().isSolid()) continue;
            if (loc.getBlock().getRelative(0, 1, 0).getType().isSolid()) continue;
            if (loc.getBlock().getRelative(0, 2, 0).getType().isSolid()) continue;
            if (loc.distance(location) > radius) continue;
            if (loc.getBlock().getRelative(0, 1, 0).getLightLevel() > 7) continue;
            if (player.hasLineOfSight(loc)) continue;
            if (loc.getBlock().isPassable()) locations.add(loc.add(0, 1, 0));
            if (locations.size() >= amount) break;
        }
        return locations;
    }

    public static void cancel() {
        if (task != null && !task.isCancelled()) task.cancel();
    }

}
