package arc.arc.generic.treasure;

import arc.arc.ARC;
import arc.arc.treasurechests.TreasureHuntManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class TreasurePool {

    private static Map<String, TreasurePool> pools = new ConcurrentHashMap<>();
    private static BukkitTask saveTask;

    private TreeMap<Integer, Treasure> treasureMap = new TreeMap<>();
    private int totalWeight = 0;
    @Getter
    final String id;
    @Getter
    @Setter
    boolean dirty = false;

    public void add(Treasure treasure) {
        totalWeight += treasure.weight();
        treasureMap.put(totalWeight, treasure);
        dirty = true;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> data = new HashMap<>();

        data.put("id", id);

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
    public static TreasurePool getOrCreate(String poolName) {
        if(pools.containsKey(poolName)) return pools.get(poolName);

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
        saveAllTreasurePools();

        File example = new File(ARC.plugin.getDataFolder() + File.separator + "treasures" + File.separator + "easter.yml");
        if (!example.exists()) {
            example.getParentFile().mkdirs();
        }

        try (var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "treasures"), 3)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().endsWith(".yml"))
                    .map(Path::toFile)
                    .map(YamlConfiguration::loadConfiguration)
                    .map(config -> loadTreasurePool(config, null, null))
                    .forEach(pool -> pools.put(pool.getId(), pool));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    private static TreasurePool loadTreasurePool(YamlConfiguration configuration, @Nullable File file, @Nullable String poolName) {
        String id = configuration.getString("id");
        if (id == null) {
            log.info("Id is not defined in treasure file!");
            if (file != null && poolName != null) {
                configuration.set("id", poolName);
                configuration.save(file);
                log.info("Added id to treasure file: {}", poolName);
                id = poolName;
            } else {
                log.error("Cant load treasure pool without id");
                return null;
            }
        }
        List<Map<String, Object>> treasureList = (List<Map<String, Object>>) configuration.getList("treasures");
        if (treasureList == null) {
            log.error("Cant load treasure pool for id {}", id);
            return new TreasurePool(id);
        }

        TreasurePool treasurePool = new TreasurePool(id);

        int count = 0;
        for (var map : treasureList) {
            try {
                String type = (String) map.get("type");
                Map<String, Object> attributes = (Map<String, Object>) map.getOrDefault("attributes", new HashMap<>());
                int weight = (int) map.getOrDefault("weight", 1);

                Treasure treasure;

                if ("command".equals(type)) {
                    String command = (String) map.get("command");
                    if (command == null) throw new RuntimeException();
                    treasure = new TreasureCommand.TreasureCommandBuilder()
                            .command(command)
                            .attributes(attributes)
                            .weight(weight)
                            .build();
                } else if ("item".equals(type)) {
                    int quantity = (int) map.getOrDefault("quantity", 1);
                    GaussData gaussData = null;
                    if (map.containsKey("gaussData")) {
                        Map<String, Double> gaussMap = (Map<String, Double>) map.get("gaussData");
                        gaussData = GaussData.deserialize(gaussMap);
                    }
                    ItemStack stack = ItemStack.deserialize((Map<String, Object>) map.get("stack"));
                    treasure = new TreasureItem.TreasureItemBuilder()
                            .stack(stack)
                            .quantity(quantity)
                            .attributes(attributes)
                            .weight(weight)
                            .gaussData(gaussData)
                            .build();
                } else {
                    log.error("Unknown treasure type: {}", type);
                    continue;
                }
                treasurePool.add(treasure);
                count++;
            } catch (Exception e) {
                e.printStackTrace();
                log.error("Cant load treasure in {} with index: {}", id, count);
            }
        }

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
            log.info("Saving treasure {}", treasurePool.getId());
            configuration.save(file);
            treasurePool.setDirty(false);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static TreasurePool getTreasurePool(String id) {
        return pools.get(id);
    }

    public static Collection<TreasurePool> getTreasurePools() {
        return pools.values();
    }

}
