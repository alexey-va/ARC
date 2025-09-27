package ru.arc.common.treasure;

import ru.arc.ARC;
import ru.arc.common.treasure.impl.SubPoolTreasure;
import ru.arc.treasurechests.TreasureHuntManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@Slf4j
@RequiredArgsConstructor
@Data
public class TreasurePool {

    private static final Map<String, TreasurePool> pools = new ConcurrentHashMap<>();
    private static BukkitTask saveTask;

    private TreeMap<Integer, Treasure> treasureMap = new TreeMap<>();
    private int totalWeight = 0;
    final String id;
    String commonMessage;
    String commonAnnounceMessage;
    boolean commonAnnounce = false;

    boolean dirty = false;

    public boolean add(Treasure treasure) {
        for (Treasure t : treasureMap.values()) {
            if (t.equals(treasure)) {
                error("Treasure already exists in pool {} {}", treasure, t);
                return false;
            }
        }
        if (treasure instanceof SubPoolTreasure subPoolTreasure) {
            if (subPoolTreasure.getSubPoolId().equalsIgnoreCase(id)) {
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
        treasureMap.values().remove(treasure);
        dirty = true;
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

        List<Map<String, Object>> treasures = new ArrayList<>();
        for (Treasure treasure : treasureMap.values()) {
            Map<String, Object> map = treasure.serialize();
            treasures.add(map);
        }
        data.put("treasures", treasures);

        return data;
    }

    public Treasure random() {
        int rand = ThreadLocalRandom.current().nextInt(0, totalWeight + 1);
        return treasureMap.ceilingEntry(rand).getValue();
    }

    @SneakyThrows
    @NotNull
    public static TreasurePool getOrCreate(String poolName) {
        if (pools.containsKey(poolName)) return pools.get(poolName);

        info("getOrCreate missing treasure pool {}", poolName);
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "treasures", poolName + ".yml");
        File file = path.toFile();
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        if (Files.notExists(path)) {
            Files.createFile(path);
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        TreasurePool pool = loadTreasurePool(configuration, file, poolName);
        if (pool == null) {
            error("Error loading treasure pool {}", poolName);
            throw new RuntimeException("Error loading treasure pool " + poolName);
        }
        pools.put(pool.getId(), pool);
        return pool;
    }

    public static void cancelSaveTask() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
    }

    public static void startSaveTask() {
        cancelSaveTask();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveAllTreasurePools();
            }
        }.runTaskTimer(ARC.plugin, 0, 20 * 60);
    }

    public static void loadAllTreasures() {
        TreasurePool.saveAllTreasurePools();

        File example = new File(ARC.plugin.getDataFolder() + File.separator + "treasures" + File.separator + "easter.yml");
        if (!example.exists()) {
            example.getParentFile().mkdirs();
        }
        pools.clear();
        try (var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "treasures"), 3)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().endsWith(".yml"))
                    .map(Path::toFile)
                    .map(YamlConfiguration::loadConfiguration)
                    .filter(config -> {
                        if (config.getString("id") == null) {
                            error("Id is not defined in treasure file!");
                            return false;
                        }
                        if (pools.containsKey(config.getString("id"))) {
                            error("Treasure pool with id {} already exists", config.getString("id"));
                            return false;
                        }
                        return true;
                    })
                    .map(config -> loadTreasurePool(config, null, null))
                    .filter(Objects::nonNull)
                    .forEach(pool -> pools.put(pool.getId(), pool));
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
        List<Map<String, Object>> treasureList = (List<Map<String, Object>>) configuration.getList("treasures");
        if (treasureList == null) {
            error("Cant load treasure pool for id {}", id);
            if(file != null) {
                configuration.set("treasures", List.of());
                configuration.save(file);
            }
            return new TreasurePool(id);
        }

        TreasurePool treasurePool = new TreasurePool(id);

        String commonMessage = configuration.getString("common-message", "");
        treasurePool.setCommonMessage(commonMessage);

        String commonAnnounceMessage = configuration.getString("common-announce-message", "");
        treasurePool.setCommonAnnounceMessage(commonAnnounceMessage);

        boolean commonAnnounce = configuration.getBoolean("common-announce", false);
        treasurePool.setCommonAnnounce(commonAnnounce);

        int count = 0;
        for (var map : treasureList) {
            try {
                Treasure treasure = Treasure.from(map, treasurePool);
                treasurePool.add(treasure);
                count++;
                //info("Loaded treasure in pool {}: {}", id, treasure);
            } catch (Exception e) {
                error("Error loading treasure in pool {}: {}", id, map, e);
                error("Aborting loading of pool {}", id);
                return null;
            }
        }
        info("Loaded {}/{} treasures for pool {}", count, treasureList.size(), id);
        info("Additional data: {} {} {}", commonMessage, commonAnnounceMessage, commonAnnounce);

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
        return pools.get(id);
    }

    public static Collection<TreasurePool> getTreasurePools() {
        return pools.values();
    }

    public void regenerateWeights() {
        totalWeight = 0;
        List<Treasure> treasures = new ArrayList<>(treasureMap.values());
        treasureMap.clear();
        treasures.forEach(this::add);
    }
}
