package ru.arc.util;

import ru.arc.ARC;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class HeadTextureCache {

    Path path;
    Data data;
    Gson gson = new GsonBuilder().setPrettyPrinting().create();


    public HeadTextureCache() {
        try {
            path = ARC.plugin.getDataFolder().toPath().resolve("head-cache.json");
            if (!Files.exists(path)) Files.createFile(path);
            data = gson.fromJson(Files.newBufferedReader(path), Data.class);
        } catch (Exception e) {
            e.printStackTrace();
            data = new Data();
            log.error("Could not create head cache file");
        }
    }

    public void save() {
        try {
            Files.write(path, gson.toJson(data).getBytes(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Could not save head cache file");
        }
    }

    public String getHeadTexture(String name) {
        try {
            TextureData td = data.byName.get(name);
            if (td == null) return null;
            if(System.currentTimeMillis()-td.timestamp > 1000L*60*60*24*30L) {
                data.byName.remove(name);
                return null;
            }
            return data.byName.get(name).string;
        } catch (Exception e) {
            return null;
        }
    }

    public void setHeadTexture(String name, String texture) {
        data.byName.put(name, new TextureData(texture, System.currentTimeMillis()));
    }


    static class Data {
        @SerializedName("n")
        ConcurrentHashMap<String, TextureData> byName = new ConcurrentHashMap<>();
    }

    @AllArgsConstructor
    static class TextureData {
        @SerializedName("s")
        String string;
        @SerializedName("t")
        long timestamp;
    }
}
