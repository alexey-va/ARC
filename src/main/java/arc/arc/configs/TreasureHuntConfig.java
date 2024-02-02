package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.rewards.ArcCommand;
import arc.arc.treasurechests.rewards.ArcItem;
import arc.arc.treasurechests.rewards.Treasure;
import arc.arc.treasurechests.rewards.TreasurePool;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TreasureHuntConfig {

    public static Map<String, String> treasureHuntAliases;
    public static double treasureHuntParticleOffsetIdle;
    public static double treasureHuntParticleExtraIdle;
    public static int treasureHuntParticleCountIdle;
    public static Particle treasureHuntParticleIdle;
    public static Particle treasureHuntParticleClaimed;
    public static double treasureHuntParticleOffsetClaimed;
    public static double treasureHuntParticleExtraClaimed;
    public static int treasureHuntParticleCountClaimed;
    public static long particleDelay;


    BukkitTask saveTreasuresTask;

    public TreasureHuntConfig() {
        File file = new File(ARC.plugin.getDataFolder() + File.separator + "treasure-hunt.yml");
        ARC.plugin.saveResource("treasure-hunt.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        loadConfig(configuration);
        startTasks();
    }

    private void loadConfig(YamlConfiguration configuration) {
        treasureHuntAliases = new HashMap<>();

        ConfigurationSection aliases = configuration.getConfigurationSection("aliases");
        if (aliases != null) {
            for (String key : aliases.getKeys(false)) {
                String value = aliases.getString(key);
                treasureHuntAliases.put(key, value);
            }
        }

        treasureHuntParticleOffsetClaimed = configuration.getDouble("particles.offset-claimed", 0.3);
        treasureHuntParticleExtraClaimed = configuration.getDouble("particles.extra-claimed", 0.1);
        treasureHuntParticleClaimed = Particle.valueOf(configuration.getString("particles.particle-claimed", "CRIT").toUpperCase());
        treasureHuntParticleCountClaimed = configuration.getInt("particles.count-claimed", 15);

        treasureHuntParticleOffsetIdle = configuration.getDouble("particles.offset-idle", 0.3);
        treasureHuntParticleExtraIdle = configuration.getDouble("particles.extra-idle", 0.1);
        treasureHuntParticleIdle = Particle.valueOf(configuration.getString("particles.particle-idle", "FLAME").toUpperCase());
        treasureHuntParticleCountIdle = configuration.getInt("particles.count-idle", 5);

        particleDelay = configuration.getLong("particle-ticks", 10);

        loadTreasures();
    }

    public void cancelTasks(){
        if(saveTreasuresTask != null && !saveTreasuresTask.isCancelled()) saveTreasuresTask.cancel();
    }

    private void startTasks(){
        cancelTasks();

        saveTreasuresTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveTreasurePools(true);
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 1200L, 1200L);
    }

    private void loadTreasures() {
        File example = new File(ARC.plugin.getDataFolder() + File.separator + "treasures" + File.separator + "easter.yml");
        if (!example.exists()) {
            example.getParentFile().mkdirs();
            ARC.plugin.saveResource("treasures/easter.yml", false);
        }

        try (var stream = Files.walk(Paths.get(ARC.plugin.getDataFolder().toString(), "treasures"), 3)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().endsWith(".yml"))
                    .map(Path::toFile)
                    .map(YamlConfiguration::loadConfiguration)
                    .forEach(this::loadTreasurePool);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadTreasurePool(YamlConfiguration configuration) {
        String id = configuration.getString("id");
        if(id == null){
            System.out.println("Id is not defined in treasure file!");
            return;
        }
        List<Map<String, Object>> treasureList = (List<Map<String, Object>>) configuration.getList("treasures");
        if (treasureList == null) {
            System.out.println("Cant load treasure pool!");
            return;
        }

        TreasurePool treasurePool = new TreasurePool(id);

        int count = 0;
        for (var map : treasureList) {
            try {
                String type = (String) map.get("type");
                boolean rare = (boolean) map.getOrDefault("rare", false);
                String rareMessage = (String) map.getOrDefault("rare-message", null);
                String message = (String) map.getOrDefault("message", null);
                int weight = (int) map.getOrDefault("weight", 1);

                Treasure treasure = null;

                if ("command".equals(type)) {
                    String command = (String) map.get("command");
                    if(command == null) throw new RuntimeException();
                    treasure = new ArcCommand(command, rare, rareMessage,message, weight);
                } else if("item".equals(type)){
                    int quantity = (int) map.getOrDefault("quantity", 1);
                    ItemStack stack = ItemStack.deserialize((Map<String, Object>) map.get("stack"));
                    treasure = new ArcItem(stack, quantity, rare, rareMessage, message, weight);
                }
                treasurePool.add(treasure);
                count++;
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Cant load treasure in "+id+" with index: "+count);
            }
        }

        TreasureHuntManager.addTreasurePool(treasurePool);
    }

    public void saveTreasurePools(boolean onlyDirty){
        TreasureHuntManager.getTreasurePools().stream()
                .filter(TreasurePool::isDirty)
                .forEach(this::saveTreasurePool);
    }

    private void saveTreasurePool(TreasurePool treasurePool){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"treasures"+File.separator+treasurePool.getId()+".yml");
        if(!file.exists()){
            try {
                file.mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        Map<String, Object> map = treasurePool.serialize();
        map.forEach(configuration::set);

        try {
            configuration.save(file);
            treasurePool.setDirty(false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
