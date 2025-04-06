package ru.arc.network.repos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RequiredArgsConstructor @Log4j2
public class BackupService {

    final String id;
    final Path folder;
    Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();

    public void saveBackup(Map<String, ?> map) {

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
        String fileName = id + "_backup_" + now.format(formatter) + ".json";
        log.info("Saving backup to {}", fileName);
        log.trace("Backup data: {}", map);
        try {
            if (!Files.exists(folder)) Files.createDirectories(folder);
            Files.writeString(folder.resolve(fileName), gson.toJson(map),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(map);
        }
    }

}
