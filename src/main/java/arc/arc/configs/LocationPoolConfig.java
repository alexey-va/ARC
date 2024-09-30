package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.util.Common;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

@Slf4j
public class LocationPoolConfig {

    BukkitTask saveTask;
    Gson gson = Common.prettyGson;

    public LocationPoolConfig() {
        loadConfig();
        startSaveTask();
    }

    public void loadConfig() {
        LocationPoolManager.clear();
        try (var stream = Files.walk(creteFolder(), 1)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            log.error("Error reading file: {}", path.getFileName());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .map(str -> gson.fromJson(str, LocationPool.class))
                    .forEach(lp -> {
                        if (LocationPoolManager.getPool(lp.getId()) != null) {
                            System.out.println("Id " + lp.getId() + " is taken by more than 1 location pool!");
                        }
                        LocationPoolManager.addPool(lp);
                    });
        } catch (Exception e) {
            log.error("Error reading location pools", e);
        }
    }

    public void saveLocationPools(boolean onlyDirty) {
        LocationPoolManager.getAll().forEach(lp -> {
            if (onlyDirty && !lp.isDirty()) return;
            Path path = creteFolder().resolve(lp.getId() + ".yml");
            lp.setDirty(false);
            try {
                String json = gson.toJson(lp);
                Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("Error saving location pool: {}", lp.getId(), e);
            }
        });
    }

    public void deleteFile(String id) {
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "location_pools", id + ".yml");
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private Path creteFolder() {
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "location_pools");
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return path;
    }

    public void startSaveTask() {
        cancelTasks();

        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveLocationPools(true);
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 1200L, 1200L);
    }

    public void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
    }

}
