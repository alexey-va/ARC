package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.farm.Farm;
import arc.arc.farm.FarmManager;
import arc.arc.farm.Lumbermill;
import arc.arc.farm.Mine;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FarmConfig {

    FarmManager farmManager;
    public static String adminPermission;
    public static Set<Material> lumberMaterials = new HashSet<>();
    public static Set<Material> farmMaterials = new HashSet<>();

    public FarmConfig(FarmManager farmManager) {
        this.farmManager = farmManager;

        File file = new File(ARC.plugin.getDataFolder() + File.separator + "farms.yml");
        if(!file.exists()) ARC.plugin.saveResource("farms.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);

        loadConfig(configuration);
    }


    private void loadConfig(YamlConfiguration configuration) {
        adminPermission = configuration.getString("admin-permission", "arc.farm-admin");

        ConfigurationSection mines = configuration.getConfigurationSection("mines");
        if(mines != null )loadMines(mines);

        ConfigurationSection farm = configuration.getConfigurationSection("farm");
        if(farm != null )loadFarm(farm);

        ConfigurationSection lumbermill = configuration.getConfigurationSection("lumbermill");
        if(lumbermill != null )loadLumbermill(lumbermill);
    }


    private void loadFarm(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        if(!enabled){
            System.out.println("Farm is disabled in config! Skipping...");
            return;
        }

        int maxBlocksPerHour = section.getInt("blocks-per-hour", 512);
        boolean particles = section.getBoolean("particles", true);
        String permission = section.getString("permission", null);
        String regionName = section.getString("region");
        String worldName = section.getString("world");
        if (worldName == null || regionName == null || permission == null) {
            System.out.print("Farm is misconfigured! Missing world-name or region!");
            return;
        }

        for (String s : section.getStringList("blocks")) {
            Material material = Material.matchMaterial(s.toUpperCase());
            farmMaterials.add(material);
        }

        Farm farm = new Farm(worldName, regionName, particles, permission, maxBlocksPerHour);
        farmManager.addFarm(farm);
    }

    private void loadLumbermill(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        if(!enabled){
            System.out.println("Farm is disabled in config! Skipping...");
            return;
        }

        boolean particles = section.getBoolean("particles", true);
        String permission = section.getString("permission", null);
        String regionName = section.getString("region");
        String worldName = section.getString("world");
        if (worldName == null || regionName == null || permission == null) {
            System.out.print("Farm is misconfigured! Missing world-name or region!");
            return;
        }

        for (String s : section.getStringList("blocks")) {
            Material material = Material.matchMaterial(s.toUpperCase());
            lumberMaterials.add(material);
        }

        Lumbermill lumbermill = new Lumbermill(worldName, regionName, particles, permission);
        farmManager.addLumber(lumbermill);
    }

    private void loadMines(ConfigurationSection configuration) {
        for(String mineId : configuration.getKeys(false)) {
            ConfigurationSection section = configuration.getConfigurationSection(mineId);
            if (section == null) {
                System.out.print("Config is not set up! " + mineId);
                return;
            }

            boolean enabled = section.getBoolean("enabled", true);
            if(!enabled){
                System.out.println("Mine "+mineId+" is disabled is config! Skipping...");
                continue;
            }

            Map<Material, Integer> materialMap = new HashMap<>();
            for (String s : section.getStringList("blocks")) {
                String[] strings = s.split(":");
                Material material = Material.matchMaterial(strings[0].toUpperCase());
                int weight = Integer.parseInt(strings[1]);

                materialMap.put(material, weight);
            }

            for (String s : section.getStringList("blocks")) {
                Material material = Material.matchMaterial(s.toUpperCase());
                farmMaterials.add(material);
            }

            int maxBlocksPerHour = section.getInt("blocks-per-hour", 256);
            boolean particles = section.getBoolean("particles", true);
            String permission = section.getString("permission", null);
            String regionName = section.getString("region");
            String worldName = section.getString("world");
            if (worldName == null || regionName == null || permission == null) {
                System.out.print(mineId + " is misconfigured! Missing world-name or region!");
                return;
            }

            Material tempBlock = Material.matchMaterial(section.getString("temp-material", "cobblestone").toUpperCase());
            int priority = section.getInt("priority", 1);
            Material baseBlock = Material.matchMaterial(section.getString("base-block", "stone").toUpperCase());

            Mine mine = new Mine(mineId, materialMap, regionName, worldName, tempBlock,
                    priority, baseBlock, permission, particles, maxBlocksPerHour);
            farmManager.addMine(mine);
        }
    }

}
