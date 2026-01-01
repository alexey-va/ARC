package ru.arc.common.treasure;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.common.treasure.impl.SubPoolTreasure;
import ru.arc.treasurechests.TreasureHuntManager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;
import static ru.arc.util.Logging.warn;

@RequiredArgsConstructor
@Data
public class TreasurePool {

    private static final Map<String, TreasurePool> pools = new ConcurrentHashMap<>();
    private static BukkitTask saveTask;

    private final TreeMap<Integer, Treasure> treasureMap = new TreeMap<>();
    private int totalWeight = 0;
    private final String id;
    private String commonMessage;
    private String commonAnnounceMessage;
    private boolean commonAnnounce = false;
    private boolean dirty = false;

    private static String keyOf(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /**
     * Public getter for Kotlin interop (Lombok getters are not visible to Kotlin).
     */
    public String getId() {
        return id;
    }

    public boolean add(Treasure treasure) {
        Objects.requireNonNull(treasure, "treasure");
        // weight validation
        if (treasure.getWeight() <= 0) {
            error("Treasure weight must be > 0: {}", treasure);
            return false;
        }
        // duplicates check
        for (Treasure t : treasureMap.values()) {
            if (t.equals(treasure)) {
                error("Treasure already exists in pool {} {}", treasure, t);
                return false;
            }
        }
        // subpool self-reference guard
        if (treasure instanceof SubPoolTreasure subPoolTreasure) {
            if (keyOf(subPoolTreasure.getSubPoolId()).equals(keyOf(id))) {
                error("SubPoolTreasure can't have the same id as the pool");
                return false;
            }
        }
        totalWeight += treasure.getWeight();
        treasure.setPool(this);
        treasureMap.put(totalWeight, treasure);
        dirty = true;
        return true;
    }

    public void remove(Treasure treasure) {
        if (treasure == null) return;
        if (treasureMap.values().remove(treasure)) {
            regenerateWeights(); // fixes totalWeight and keys
            dirty = true;
        }
    }

    public int size() {
        return treasureMap.size();
    }

    public Collection<Treasure> getTreasures() {
        return treasureMap.values();
    }

    public Map<String, Object> serialize() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("common-message", commonMessage);
        data.put("common-announce-message", commonAnnounceMessage);
        data.put("common-announce", commonAnnounce); // was missing

        List<Map<String, Object>> treasures = new ArrayList<>();
        for (Treasure treasure : treasureMap.values()) {
            Map<String, Object> map = treasure.serialize();
            treasures.add(map);
        }
        data.put("treasures", treasures);

        return data;
    }

    @Nullable
    public Treasure random() {
        if (treasureMap.isEmpty() || totalWeight <= 0) return null;
        // 1..totalWeight inclusive
        int rand = ThreadLocalRandom.current().nextInt(1, totalWeight + 1);
        Map.Entry<Integer, Treasure> e = treasureMap.ceilingEntry(rand);
        return e != null ? e.getValue() : null;
    }

    @SneakyThrows
    @NotNull
    public static TreasurePool getOrCreate(String poolName) {
        String key = keyOf(poolName);
        if (pools.containsKey(key)) return pools.get(key);

        info("getOrCreate missing treasure pool {}", poolName);
        Path dir = Paths.get(ARC.plugin.getDataFolder().toString(), "treasures");
        Files.createDirectories(dir);
        Path path = dir.resolve(poolName + ".yml");
        File file = path.toFile();
        if (Files.notExists(path)) {
            Files.createFile(path);
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        TreasurePool pool = loadTreasurePool(configuration, file, poolName);
        if (pool == null) {
            error("Error loading treasure pool {}", poolName);
            throw new RuntimeException("Error loading treasure pool " + poolName);
        }
        pools.put(keyOf(pool.getId()), pool);
        return pool;
    }

    public static void cancelSaveTask() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
    }

    public static void startSaveTask() {
        cancelSaveTask();
        // first run in 60s, then every 60s
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveAllTreasurePools();
            }
        }.runTaskTimer(ARC.plugin, 20 * 60, 20 * 60);
    }

    public static void loadAllTreasures() {
        // optional: persist dirty before reload
        TreasurePool.saveAllTreasurePools();

        Path dir = Paths.get(ARC.plugin.getDataFolder().toString(), "treasures");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            error("Failed to create treasures directory", e);
        }

        pools.clear();
        try (var stream = Files.walk(dir, 3)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().endsWith(".yml"))
                    .map(Path::toFile)
                    .map(YamlConfiguration::loadConfiguration)
                    .filter(config -> {
                        String id = config.getString("id");
                        if (id == null) {
                            warn("Id is not defined in treasure file, skipping");
                            return false;
                        }
                        String k = keyOf(id);
                        if (pools.containsKey(k)) {
                            error("Treasure pool with id {} already exists", id);
                            return false;
                        }
                        return true;
                    })
                    .map(config -> loadTreasurePool(config, null, null))
                    .filter(Objects::nonNull)
                    .forEach(pool -> pools.put(keyOf(pool.getId()), pool));
        } catch (Exception e) {
            error("Error loading treasures", e);
        }
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    private static TreasurePool loadTreasurePool(YamlConfiguration configuration, @Nullable File file, @Nullable String poolName) {
        info("Loading treasure pool {}", poolName);
        String id = configuration.getString("id");
        if (id == null) {
            info("Id is not defined in treasure file!");
            if (file != null && poolName != null) {
                configuration.set("id", poolName);
                configuration.save(file);
                info("Added id to treasure file: {}", poolName);
                id = poolName;
            } else {
                error("Cant load treasure pool without id");
                return null;
            }
        }

        TreasurePool treasurePool = new TreasurePool(id);

        String commonMessage = configuration.getString("common-message", "");
        treasurePool.setCommonMessage(commonMessage);

        String commonAnnounceMessage = configuration.getString("common-announce-message", "");
        treasurePool.setCommonAnnounceMessage(commonAnnounceMessage);

        boolean commonAnnounce = configuration.getBoolean("common-announce", false);
        treasurePool.setCommonAnnounce(commonAnnounce);

        List<Map<String, Object>> treasureList = (List<Map<String, Object>>) configuration.getList("treasures");
        if (treasureList == null) {
            error("Cant load treasure pool for id {}", id);
            if (file != null) {
                configuration.set("treasures", List.of());
                configuration.save(file);
            }
            // return empty pool as before
            treasurePool.setDirty(false);
            return treasurePool;
        }

        int count = 0;
        for (var map : treasureList) {
            try {
                Treasure treasure = Treasure.from(map, treasurePool);
                if (treasurePool.add(treasure)) count++;
            } catch (Exception e) {
                error("Error loading treasure in pool {}: {}", id, map, e);
                error("Aborting loading of pool {}", id);
                return null;
            }
        }
        debug("Loaded {}/{} treasures for pool {}", count, treasureList.size(), id);
        debug("Additional data: {} {} {}", commonMessage, commonAnnounceMessage, commonAnnounce);

        treasurePool.setDirty(false);
        return treasurePool;
    }

    public static void saveAllTreasurePools() {
        TreasureHuntManager.getTreasurePools().stream()
                .filter(TreasurePool::isDirty)
                .forEach(TreasurePool::saveTreasurePool);
    }

    public static void saveTreasurePool(TreasurePool treasurePool) {
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "treasures", treasurePool.getId() + ".yml");
        File file = path.toFile();

        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            Map<String, Object> map = treasurePool.serialize();
            map.forEach(configuration::set);
            info("Saving treasure {}", treasurePool.getId());
            configuration.save(file);
            treasurePool.setDirty(false);
        } catch (IOException e) {
            error("Error saving treasure pool {}", treasurePool.getId(), e);
        }
    }

    public static TreasurePool getTreasurePool(String id) {
        return pools.get(keyOf(id));
    }

    public static Collection<TreasurePool> getTreasurePools() {
        return pools.values();
    }

    public void regenerateWeights() {
        totalWeight = 0;
        // preserve unique treasures and valid weights
        List<Treasure> treasures = new ArrayList<>(treasureMap.values());
        treasureMap.clear();
        for (Treasure t : treasures) {
            if (t.getWeight() > 0) {
                totalWeight += t.getWeight();
                treasureMap.put(totalWeight, t);
            } else {
                warn("Skipping treasure with non-positive weight during reindex: {}", t);
            }
        }
    }
}
