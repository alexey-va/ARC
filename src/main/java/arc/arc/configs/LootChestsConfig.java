package arc.arc.configs;

import arc.arc.ARC;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class LootChestsConfig {

    private static YamlConfiguration config;
    private static File file;

    private static List<String> bossFiles;

    public static void load() {
        file = new File(ARC.plugin.getDataFolder() + File.separator + "lootchests/bosses.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            ARC.plugin.saveResource("lootchests/bosses.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        bossFiles = config.getStringList("bosses");
    }

    public static String randomBoss(){
        if(bossFiles == null) return null;
        return bossFiles.get(ThreadLocalRandom.current().nextInt(0, bossFiles.size()));
    }

}
