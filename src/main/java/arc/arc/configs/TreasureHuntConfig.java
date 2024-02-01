package arc.arc.configs;

import arc.arc.ARC;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class TreasureHuntConfig {

    public static Map<String, String> treasureHuntAliases;
    public static Map<String, String> treasureHuntCommands;
    public static double treasureHuntParticleOffsetIdle;
    public static double treasureHuntParticleExtraIdle;
    public static int treasureHuntParticleCountIdle;
    public static Particle treasureHuntParticleIdle;
    public static Particle treasureHuntParticleClaimed;
    public static double treasureHuntParticleOffsetClaimed;
    public static double treasureHuntParticleExtraClaimed;
    public static int treasureHuntParticleCountClaimed;
    public static long particleDelay;

    public TreasureHuntConfig(){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"treasure-hunt.yml");
        ARC.plugin.saveResource("treasure-hunt.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        loadConfig(configuration);
    }

    private void loadConfig(YamlConfiguration configuration){
        treasureHuntAliases = new HashMap<>();
        treasureHuntCommands = new HashMap<>();

        ConfigurationSection aliases = configuration.getConfigurationSection("aliases");
        if(aliases != null) {
            for (String key : aliases.getKeys(false)){
                String value = aliases.getString(key);
                treasureHuntAliases.put(key, value);
            }
        }

        ConfigurationSection commands = configuration.getConfigurationSection("commands");
        if(commands != null) {
            for (String key : commands.getKeys(false)){
                String value = commands.getString(key);
                treasureHuntCommands.put(key, value);
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
    }

}
