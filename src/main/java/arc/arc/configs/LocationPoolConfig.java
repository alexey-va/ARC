package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class LocationPoolConfig {

    BukkitTask saveTask;

    public LocationPoolConfig() {
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
                            e.printStackTrace();
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .map(str -> {
                        ObjectMapper mapper = new ObjectMapper();
                        try {
                            return mapper.readValue(str, LocationPool.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .forEach(lp -> {
                        if (LocationPoolManager.getPool(lp.getId()) != null) {
                            System.out.println("Id " + lp.getId() + " is taken by more than 1 location pool!");
                        }
                        LocationPoolManager.addPool(lp);
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveConfig(boolean onlyDirty) {
        LocationPoolManager.getAll().forEach(lp -> {
            if (onlyDirty && !lp.isDirty()) return;
            Path path = creteFolder().resolve(lp.getId() + ".yml");
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            lp.setDirty(false);
            try {
                String json = mapper.writeValueAsString(lp);
                Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void deleteFile(String id){
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "location_pools", id+".yml");
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
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();

        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveConfig(true);
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 1200L, 1200L);
    }

}
