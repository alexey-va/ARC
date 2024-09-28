package arc.arc.eliteloot;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Setter
public class EliteLootManager {

    @Getter
    private static EliteLootProcessor eliteLootProcessor;
    @Getter
    private static EliteLootConfigParser eliteLootConfigParser;
    @Getter
    private static Map<LootType, DecorPool> map = new ConcurrentHashMap<>();


    public static void init() {
        Path path = ARC.plugin.getDataFolder().toPath();
        Config config = ConfigManager.of(path, "elite-loot.yml");
        eliteLootConfigParser = new EliteLootConfigParser(config);
        map = eliteLootConfigParser.load();

        eliteLootProcessor = new EliteLootProcessor(config);
    }


    public static LootType toLootType(ItemStack stack) {
        if (stack == null) return null;
        return toLootType(stack.getType());
    }

    public static LootType toLootType(Material material) {
        return switch (material) {
            case WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE -> LootType.AXE;
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD -> LootType.SWORD;
            case BOW -> LootType.BOW;
            case LEATHER_HELMET, CHAINMAIL_HELMET, GOLDEN_HELMET, IRON_HELMET, DIAMOND_HELMET, NETHERITE_HELMET ->
                    LootType.HELMET;
            case LEATHER_CHESTPLATE, CHAINMAIL_CHESTPLATE, GOLDEN_CHESTPLATE, IRON_CHESTPLATE, DIAMOND_CHESTPLATE,
                 NETHERITE_CHESTPLATE -> LootType.CHESTPLATE;
            case LEATHER_LEGGINGS, CHAINMAIL_LEGGINGS, GOLDEN_LEGGINGS, IRON_LEGGINGS, DIAMOND_LEGGINGS,
                 NETHERITE_LEGGINGS -> LootType.LEGGINGS;
            case LEATHER_BOOTS, CHAINMAIL_BOOTS, GOLDEN_BOOTS, IRON_BOOTS, DIAMOND_BOOTS, NETHERITE_BOOTS ->
                    LootType.BOOTS;
            case CROSSBOW -> LootType.CROSSBOW;
            default -> null;
        };
    }

}
