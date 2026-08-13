package me.dartanman.duels.recovery;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.dartanman.duels.model.DuelMode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public record PlayerSnapshot(
        int format,
        UUID playerId,
        String playerName,
        DuelMode mode,
        long capturedAt,
        String storage,
        String armor,
        String extra,
        String cursor,
        int level,
        float exp,
        int totalExperience,
        double health,
        double absorption,
        int food,
        float saturation,
        float exhaustion,
        int fireTicks,
        int remainingAir,
        GameMode gameMode,
        boolean allowFlight,
        boolean flying,
        boolean invulnerable,
        float walkSpeed,
        float flySpeed,
        LocationData location,
        List<EffectData> effects
) {
    public static final int CURRENT_FORMAT = 1;

    public PlayerSnapshot {
        effects = List.copyOf(effects);
        if (format != CURRENT_FORMAT) throw new IllegalArgumentException("Unsupported snapshot format: " + format);
    }

    public static PlayerSnapshot capture(Player player, DuelMode mode, long now) {
        return new PlayerSnapshot(
                CURRENT_FORMAT,
                player.getUniqueId(),
                player.getName(),
                mode,
                now,
                encode(player.getInventory().getStorageContents()),
                encode(player.getInventory().getArmorContents()),
                encode(player.getInventory().getExtraContents()),
                encode(new ItemStack[]{player.getItemOnCursor()}),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getFireTicks(),
                player.getRemainingAir(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isInvulnerable(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                LocationData.capture(player.getLocation()),
                player.getActivePotionEffects().stream().map(EffectData::capture).toList()
        );
    }

    public void apply(Player player) {
        if (!player.getUniqueId().equals(playerId)) {
            throw new IllegalArgumentException("Snapshot belongs to a different player");
        }
        ItemStack[] storageItems = decode(storage);
        ItemStack[] armorItems = decode(armor);
        ItemStack[] extraItems = decode(extra);
        ItemStack[] cursorItems = decode(cursor);
        if (storageItems.length != player.getInventory().getStorageContents().length) {
            throw new IllegalStateException("Storage inventory size changed");
        }
        if (armorItems.length != player.getInventory().getArmorContents().length) {
            throw new IllegalStateException("Armor inventory size changed");
        }
        if (extraItems.length != player.getInventory().getExtraContents().length) {
            throw new IllegalStateException("Extra inventory size changed");
        }

        player.closeInventory();
        player.getInventory().setStorageContents(storageItems);
        player.getInventory().setArmorContents(armorItems);
        player.getInventory().setExtraContents(extraItems);
        player.setItemOnCursor(cursorItems.length == 0 ? null : cursorItems[0]);

        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setInvulnerable(invulnerable);
        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);
        player.setLevel(level);
        player.setExp(exp);
        player.setTotalExperience(totalExperience);
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setFireTicks(fireTicks);
        player.setRemainingAir(remainingAir);
        player.setAbsorptionAmount(absorption);

        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        effects.stream().map(EffectData::toEffect).forEach(player::addPotionEffect);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double limit = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.max(0.1, Math.min(health, limit)));
        if (!player.teleport(location.resolve())) {
            throw new IllegalStateException("Recovery teleport was rejected for " + player.getUniqueId());
        }
        player.updateInventory();
    }

    public String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", format);
        yaml.set("player.uuid", playerId.toString());
        yaml.set("player.name", playerName);
        yaml.set("mode", mode.name());
        yaml.set("captured-at", capturedAt);
        yaml.set("items.storage", storage);
        yaml.set("items.armor", armor);
        yaml.set("items.extra", extra);
        yaml.set("items.cursor", cursor);
        yaml.set("state.level", level);
        yaml.set("state.exp", exp);
        yaml.set("state.total-experience", totalExperience);
        yaml.set("state.health", health);
        yaml.set("state.absorption", absorption);
        yaml.set("state.food", food);
        yaml.set("state.saturation", saturation);
        yaml.set("state.exhaustion", exhaustion);
        yaml.set("state.fire-ticks", fireTicks);
        yaml.set("state.remaining-air", remainingAir);
        yaml.set("state.game-mode", gameMode.name());
        yaml.set("state.allow-flight", allowFlight);
        yaml.set("state.flying", flying);
        yaml.set("state.invulnerable", invulnerable);
        yaml.set("state.walk-speed", walkSpeed);
        yaml.set("state.fly-speed", flySpeed);
        location.write(yaml, "location");
        List<java.util.Map<String, Object>> serializedEffects = effects.stream().map(EffectData::toMap).toList();
        yaml.set("effects", serializedEffects);
        return yaml.saveToString();
    }

    public static PlayerSnapshot fromYaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid recovery snapshot", exception);
        }
        int format = yaml.getInt("format", -1);
        UUID playerId = UUID.fromString(required(yaml, "player.uuid"));
        List<EffectData> effects = new ArrayList<>();
        for (java.util.Map<?, ?> value : yaml.getMapList("effects")) effects.add(EffectData.fromMap(value));
        return new PlayerSnapshot(
                format,
                playerId,
                required(yaml, "player.name"),
                DuelMode.valueOf(required(yaml, "mode")),
                yaml.getLong("captured-at"),
                required(yaml, "items.storage"),
                required(yaml, "items.armor"),
                required(yaml, "items.extra"),
                required(yaml, "items.cursor"),
                yaml.getInt("state.level"),
                (float) yaml.getDouble("state.exp"),
                yaml.getInt("state.total-experience"),
                yaml.getDouble("state.health"),
                yaml.getDouble("state.absorption"),
                yaml.getInt("state.food"),
                (float) yaml.getDouble("state.saturation"),
                (float) yaml.getDouble("state.exhaustion"),
                yaml.getInt("state.fire-ticks"),
                yaml.getInt("state.remaining-air"),
                GameMode.valueOf(required(yaml, "state.game-mode")),
                yaml.getBoolean("state.allow-flight"),
                yaml.getBoolean("state.flying"),
                yaml.getBoolean("state.invulnerable"),
                (float) yaml.getDouble("state.walk-speed"),
                (float) yaml.getDouble("state.fly-speed"),
                LocationData.read(yaml.getConfigurationSection("location")),
                effects
        );
    }

    private static String required(ConfigurationSection section, String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing snapshot value: " + path);
        return value;
    }

    private static String encode(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decode(String encoded) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    public record LocationData(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
        static LocationData capture(Location location) {
            return new LocationData(
                    location.getWorld().getUID(),
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
        }

        void write(ConfigurationSection section, String path) {
            section.set(path + ".world-uuid", worldId.toString());
            section.set(path + ".world", worldName);
            section.set(path + ".x", x);
            section.set(path + ".y", y);
            section.set(path + ".z", z);
            section.set(path + ".yaw", yaw);
            section.set(path + ".pitch", pitch);
        }

        static LocationData read(ConfigurationSection section) {
            if (section == null) throw new IllegalArgumentException("Missing snapshot location");
            return new LocationData(
                    UUID.fromString(required(section, "world-uuid")),
                    required(section, "world"),
                    section.getDouble("x"),
                    section.getDouble("y"),
                    section.getDouble("z"),
                    (float) section.getDouble("yaw"),
                    (float) section.getDouble("pitch")
            );
        }

        public Location resolve() {
            World world = Bukkit.getWorld(worldId);
            if (world == null) world = Bukkit.getWorld(worldName);
            if (world == null) throw new IllegalStateException("Recovery world is not loaded: " + worldName);
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    public record EffectData(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
        static EffectData capture(PotionEffect effect) {
            return new EffectData(
                    effect.getType().getKey().toString(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()
            );
        }

        PotionEffect toEffect() {
            NamespacedKey key = NamespacedKey.fromString(type);
            PotionEffectType resolved = key == null ? null : PotionEffectType.getByKey(key);
            if (resolved == null) throw new IllegalStateException("Unknown potion effect in snapshot: " + type);
            return new PotionEffect(resolved, duration, amplifier, ambient, particles, icon);
        }

        java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", type);
            map.put("duration", duration);
            map.put("amplifier", amplifier);
            map.put("ambient", ambient);
            map.put("particles", particles);
            map.put("icon", icon);
            return map;
        }

        static EffectData fromMap(java.util.Map<?, ?> map) {
            return new EffectData(
                    String.valueOf(map.get("type")),
                    number(map.get("duration")),
                    number(map.get("amplifier")),
                    bool(map.get("ambient")),
                    bool(map.get("particles")),
                    bool(map.get("icon"))
            );
        }

        private static int number(Object value) {
            return value instanceof Number number ? number.intValue() : 0;
        }

        private static boolean bool(Object value) {
            return value instanceof Boolean bool && bool;
        }
    }
}
