package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.autobuild.Building;
import arc.arc.autobuild.BuildingManager;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class BuildingConfig {

    public static int blocksPerCycle;
    public static long cycleDuration;
    public static boolean playSound;
    public static Particle placeParticle;
    public static boolean showParticles;
    public static int cancelModelData;
    public static int confirmModelData;
    public static String bookDisplay;
    public static List<String> bookLore;
    public boolean chestLoot;


    public BuildingConfig(){
        loadConfig();
        loadSchematics();
    }

    public void loadConfig(){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"buildings.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if(!file.exists()) ARC.plugin.saveResource("buildings.yml", false);

        blocksPerCycle = config.getInt("blocks-per-cycle", 2);
        cycleDuration = config.getLong("cycle-duration-ticks", 10L);
        playSound = config.getBoolean("play-sound", true);
        showParticles = config.getBoolean("show-place-particles", true);
        placeParticle = Particle.valueOf(config.getString("place-particle", "FLAME").toUpperCase());
        cancelModelData = config.getInt("cancel-model-data", 11002);
        confirmModelData = config.getInt("confirm-model-data", 11007);
        chestLoot = config.getBoolean("chest-loot", true);

        bookDisplay = config.getString("book.display", "Book");
        bookLore = config.getStringList("book.lore");
    }

    public void loadSchematics() {
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "schematics");
        if(!Files.exists(path)){
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "schematics"), 3,
                FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(Building::new)
                    .forEach(BuildingManager::addBuilding);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
