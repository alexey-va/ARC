package me.dartanman.duels.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import me.dartanman.duels.model.Arena;
import me.dartanman.duels.model.DuelMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public record DuelsConfig(
        boolean enabled,
        DuelMode mode,
        int requestExpirationSeconds,
        int countdownSeconds,
        Set<String> allowedCommands,
        List<Arena> arenas,
        KitDefinition kit
) {
    public DuelsConfig {
        allowedCommands = Set.copyOf(allowedCommands);
        arenas = List.copyOf(arenas);
        if (requestExpirationSeconds < 5) {
            throw new IllegalArgumentException("request-expiration-seconds must be at least 5");
        }
        if (countdownSeconds < 0 || countdownSeconds > 10) {
            throw new IllegalArgumentException("countdown-seconds must be between 0 and 10");
        }
        if (enabled && mode == DuelMode.DISABLED) {
            throw new IllegalArgumentException("enabled server cannot use DISABLED mode");
        }
        if (enabled && arenas.isEmpty()) {
            throw new IllegalArgumentException("enabled server requires at least one arena");
        }
        if (enabled && mode == DuelMode.KIT && kit.isEmpty()) {
            throw new IllegalArgumentException("KIT mode requires a non-empty kit");
        }
    }

    public static DuelsConfig load(File file, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean enabled = yaml.getBoolean("enabled", false);
        DuelMode mode = parseMode(yaml.getString("mode", "DISABLED"));
        int requestExpiration = yaml.getInt("request-expiration-seconds", 30);
        int countdown = yaml.getInt("countdown-seconds", 3);
        double radius = yaml.getDouble("arena-radius", 64.0);

        Set<String> allowed = new LinkedHashSet<>();
        for (String command : yaml.getStringList("allowed-commands")) {
            String normalized = command.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.matches("[a-z0-9:_-]+")) allowed.add(normalized);
        }

        List<Arena> arenas = new ArrayList<>();
        ConfigurationSection arenasSection = yaml.getConfigurationSection("arenas");
        if (arenasSection != null) {
            for (String id : arenasSection.getKeys(false)) {
                ConfigurationSection section = arenasSection.getConfigurationSection(id);
                if (section == null || !section.getBoolean("enabled", true)) continue;
                Arena.Point first = point(section.getConfigurationSection("first"));
                Arena.Point second = point(section.getConfigurationSection("second"));
                arenas.add(new Arena(id, first, second, section.getDouble("radius", radius)));
            }
        }

        KitDefinition kit = parseKit(yaml.getConfigurationSection("kit"), logger);
        return new DuelsConfig(enabled, mode, requestExpiration, countdown, allowed, arenas, kit);
    }

    private static DuelMode parseMode(String raw) {
        try {
            return DuelMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown duel mode: " + raw, exception);
        }
    }

    private static Arena.Point point(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Arena point is missing");
        String world = section.getString("world");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("Arena world is missing");
        return new Arena.Point(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    private static KitDefinition parseKit(ConfigurationSection section, Logger logger) {
        if (section == null) return new KitDefinition(Map.of(), null, null, null, null, null);
        Map<Integer, ItemStack> storage = new HashMap<>();
        for (Map<?, ?> entry : section.getMapList("items")) {
            Object slotValue = entry.get("slot");
            if (!(slotValue instanceof Number number)) throw new IllegalArgumentException("Kit item slot is missing");
            int slot = number.intValue();
            if (slot < 0 || slot > 35) throw new IllegalArgumentException("Kit slot out of range: " + slot);
            storage.put(slot, parseItem(entry, logger));
        }
        ConfigurationSection armor = section.getConfigurationSection("armor");
        return new KitDefinition(
                storage,
                parseOptionalItem(armor, "helmet", logger),
                parseOptionalItem(armor, "chestplate", logger),
                parseOptionalItem(armor, "leggings", logger),
                parseOptionalItem(armor, "boots", logger),
                parseOptionalItem(section, "offhand", logger)
        );
    }

    private static ItemStack parseOptionalItem(ConfigurationSection section, String key, Logger logger) {
        if (section == null || !section.isSet(key)) return null;
        Object raw = section.get(key);
        if (raw == null || "null".equalsIgnoreCase(String.valueOf(raw))) return null;
        if (raw instanceof String material) return item(material, 1, Map.of(), logger);
        if (raw instanceof ConfigurationSection nested) return parseItem(nested.getValues(false), logger);
        if (raw instanceof Map<?, ?> map) return parseItem(map, logger);
        throw new IllegalArgumentException("Invalid kit item at " + section.getCurrentPath() + "." + key);
    }

    private static ItemStack parseItem(Map<?, ?> values, Logger logger) {
        String material = String.valueOf(values.get("material"));
        int amount = values.get("amount") instanceof Number number ? number.intValue() : 1;
        Object enchantments = values.get("enchantments");
        Map<?, ?> enchantmentMap = enchantments instanceof Map<?, ?> map ? map : Map.of();
        return item(material, amount, enchantmentMap, logger);
    }

    private static ItemStack item(String materialName, int amount, Map<?, ?> enchantments, Logger logger) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown kit material: " + materialName);
        if (amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid amount " + amount + " for " + material);
        }
        ItemStack item = new ItemStack(material, amount);
        for (Map.Entry<?, ?> entry : enchantments.entrySet()) {
            Enchantment enchantment = Enchantment.getByName(String.valueOf(entry.getKey()).toUpperCase(Locale.ROOT));
            if (enchantment == null) {
                logger.warning("Ignoring unknown kit enchantment: " + entry.getKey());
                continue;
            }
            int level = entry.getValue() instanceof Number number ? number.intValue() : 1;
            item.addUnsafeEnchantment(enchantment, level);
        }
        return item;
    }
}
