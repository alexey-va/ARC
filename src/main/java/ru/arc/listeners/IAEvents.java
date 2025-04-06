package ru.arc.listeners;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

@Slf4j
public class IAEvents implements Listener {

    private static final String BOOTS = "items=leather_boots";
    private static final String REPLACE_BOOTS = "items=leather_boots iron_boots golden_boots chainmail_boots diamond_boots netherite_boots";

    private static final String LEGGINGS = "items=leather_leggings";
    private static final String REPLACE_LEGGINGS = "items=leather_leggings iron_leggings golden_leggings chainmail_leggings diamond_leggings netherite_leggings";

    private static final String CHESTPLATE = "items=leather_chestplate";
    private static final String REPLACE_CHESTPLATE = "items=leather_chestplate iron_chestplate golden_chestplate chainmail_chestplate diamond_chestplate netherite_chestplate";

    private static final String HELMET = "items=leather_helmet";
    private static final String REPLACE_HELMET = "items=leather_helmet iron_helmet golden_helmet chainmail_helmet diamond_helmet netherite_helmet";

    private static final String layer1key = "texture.leather_layer_1";
    private static final String layer2key = "texture.leather_layer_2";
    List<String> allLayers1Keys = List.of(
            "texture.iron_layer_1",
            "texture.gold_layer_1",
            "texture.chainmail_layer_1",
            "texture.diamond_layer_1",
            "texture.netherite_layer_1"
    );
    List<String> allLayers2Keys = List.of(
            "texture.iron_layer_2",
            "texture.gold_layer_2",
            "texture.chainmail_layer_2",
            "texture.diamond_layer_2",
            "texture.netherite_layer_2"
    );

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRpCompress(ItemsAdderPackCompressedEvent event) throws Exception {
        Path path = ARC.plugin.getDataFolder().toPath();
        Config emHookConfig = ConfigManager.of(path, "ia-hooks.yml");
        String pathToZip = emHookConfig.string("path-to-zip", "ItemsAdder/output/generated.zip");
        Path zipPath = path.getParent().resolve(pathToZip);
        if (!Files.exists(zipPath)) {
            return;
        }

        Path tempDir = Files.createTempDirectory("ia-hooks-temp");
        unzip(zipPath, tempDir);

        try (var walk = Files.walk(tempDir)) {
            walk.filter(p -> p.toString().endsWith(".properties"))
                    .filter(p -> p.toString().contains("ia_generated"))
                    .forEach(p -> {
                        try {
                            replaceLineInFile(p, BOOTS, REPLACE_BOOTS);
                            replaceLineInFile(p, LEGGINGS, REPLACE_LEGGINGS);
                            replaceLineInFile(p, CHESTPLATE, REPLACE_CHESTPLATE);
                            replaceLineInFile(p, HELMET, REPLACE_HELMET);
                        } catch (Exception e) {
                            log.error("Error processing property file: {}", p, e);
                        }
                    });
        }

        zip(tempDir, zipPath.toString());
    }

    private void unzip(Path zipPath, Path destDir) throws Exception {
        URI zipUri = URI.create("jar:file:" + zipPath.toUri().getPath());
        try (FileSystem zipFs = FileSystems.newFileSystem(zipUri, Collections.emptyMap())) {
            Path root = zipFs.getPath("/");
            try (var walk = Files.walk(root)) {
                walk.forEach(p -> {
                    try {
                        Path dest = destDir.resolve(root.relativize(p).toString());
                        if (Files.isDirectory(p)) Files.createDirectories(dest);
                        else Files.copy(p, dest);
                    } catch (Exception e) {
                        log.error("Error unzipping file: {}", p, e);
                    }
                });
            }
        }
    }

    private void zip(Path sourceDir, String zipFilePath) throws Exception {
        Path zipPath = Paths.get(zipFilePath);
        Files.deleteIfExists(zipPath);

        URI zipUri = URI.create("jar:file:" + zipPath.toUri().getPath());
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        try (FileSystem zipFs = FileSystems.newFileSystem(zipUri, env)) {
            try(var walk =Files.walk(sourceDir)) {
                walk.filter(path -> !Files.isDirectory(path))
                        .forEach(path -> {
                            Path destPath = zipFs.getPath("/" + sourceDir.relativize(path).toString());
                            try {
                                Files.createDirectories(destPath.getParent());
                                Files.copy(path, destPath, StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception e) {
                                log.error("Error zipping file: {}", path, e);
                            }
                        });
            }
        }
    }

    private void replaceLineInFile(Path path, String target, String replace) throws Exception {
        if (updateBaseArmor(path)) return;

        List<String> list = Files.readAllLines(path);
        boolean found = false;
        String layer1 = null;
        String layer2 = null;
        String namespace = null;
        String id = null;
        for (int i = 0; i < list.size(); i++) {
            String line = list.get(i);
            if (line.contains(layer1key)) {
                String[] split = line.split("=");
                layer1 = split[1];
            }
            if (line.contains(layer2key)) {
                String[] split = line.split("=");
                layer2 = split[1];
            }
            if (line.contains("nbt.itemsadder.namespace")) {
                String[] split = line.split("=");
                namespace = split[1];
            }
            if (line.contains("nbt.itemsadder.id")) {
                String[] split = line.split("=");
                id = split[1];
            }
            if (line.contains(target)) {
                String newString = line.replace(target, replace);
                list.set(i, newString);
                found = true;
            }
        }
        if (found && layer1 != null && layer2 != null) {
            for (String key : allLayers1Keys) {
                String newString = key + "=" + layer1;
                list.add(newString);
            }
            for (String key : allLayers2Keys) {
                String newString = key + "=" + layer2;
                list.add(newString);
            }
        }
        // if found create new file with _icon suffix
        if (found && false) {

            Path newPath = Paths.get(path.toString().replace(".properties", "_icon.properties"));
            CustomStack customStack = CustomStack.getInstance(namespace + ":" + id);

            if (customStack != null && !customStack.getTextures().isEmpty()) {
                String texturePath = customStack.getTextures().getFirst();
                String[] split = texturePath.split(":");
                if (split.length == 2) {
                    texturePath = "../../../../" + split[0] + "/textures/" + split[1];
                }
                String lines = """
                        nbt.itemsadder.namespace={namespace}
                        nbt.itemsadder.id={id}
                        argType=item
                        {replace_string}
                        weight=9999
                        texture={texture_path}
                        """;
                lines = lines.replace("{namespace}", namespace);
                lines = lines.replace("{id}", id);
                lines = lines.replace("{replace_string}", replace);
                lines = lines.replace("{texture_path}", texturePath);
                Files.write(newPath, lines.getBytes());
            }

        }
        Files.write(path, list);
    }

    private boolean updateBaseArmor(Path path) throws Exception {
        if (!path.toString().contains("base_leather")) return false;

        Path parent = path.getParent();
        List<Path> leatherTextures = new ArrayList<>();
        Path pluginFolder = ARC.plugin.getDataFolder().toPath();
        Path iaData = pluginFolder.resolve("ia_data");
        if (!Files.exists(iaData)) {
            Files.createDirectories(iaData);
        }
        Files.walk(iaData)
                .filter(p -> p.toString().endsWith(".png"))
                .filter(p -> p.toString().startsWith("leather_layer"))
                .forEach(leatherTextures::add);
        leatherTextures.forEach(p -> {
            try {
                Files.copy(p, parent.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log.error("Error copying file: {}", p, e);
            }
        });
        String fileString = """
                argType=armor
                items=leather_helmet leather_chestplate leather_leggings leather_boots
                texture.leather_layer_1_overlay=leather_layer_1_overlay.png
                texture.leather_layer_2_overlay=leather_layer_2_overlay.png
                texture.leather_layer_1=leather_layer_1.png
                texture.leather_layer_2=leather_layer_2.png
                """;
        Files.write(path, fileString.getBytes());
        return true;
    }

}
