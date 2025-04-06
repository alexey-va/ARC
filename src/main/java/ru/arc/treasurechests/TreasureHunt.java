package ru.arc.treasurechests;

import ru.arc.ARC;
import ru.arc.common.ServerLocation;
import ru.arc.common.chests.CustomChest;
import ru.arc.common.chests.ItemsAdderCustomChest;
import ru.arc.common.chests.VanillaChest;
import ru.arc.common.locationpools.LocationPool;
import ru.arc.common.treasure.Treasure;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.ParticleManager;
import ru.arc.util.Utils;
import ru.arc.xserver.announcements.AnnounceManager;
import ru.arc.xserver.playerlist.PlayerManager;
import com.destroystokyo.paper.ParticleBuilder;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static ru.arc.util.TextUtil.mm;

@RequiredArgsConstructor
@Log4j2
public class TreasureHunt {

    final int chests;
    final TreasureHuntType treasureHuntType;

    int max;
    int left;
    Set<Location> locations;
    Map<Location, CustomChest> customChests = new HashMap<>();
    Map<CustomChest, ChestType> customChestChestTypeMap = new HashMap<>();
    BukkitTask displayTask;
    BossBar bossBar;
    Set<Player> bossBarAudience = new HashSet<>();
    World world;
    long timestamp;
    AtomicInteger counter = new AtomicInteger(0);

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasure-hunt.yml");

    public static Map<String, String> aliases() {
        return config.map("aliases");
    }

    private static final Color[] colors = new Color[]{Color.WHITE, Color.AQUA, Color.PURPLE, Color.YELLOW, Color.MAROON, Color.GREEN, Color.TEAL,
            Color.OLIVE, Color.FUCHSIA, Color.LIME, Color.RED, Color.ORANGE};

    public Set<Location> start() {
        for (Location location : locations) {
            ChestType randomChestType = treasureHuntType.getRandomChestType();
            Type type = randomChestType.getType();
            String namespaceId = randomChestType.getNamespaceId();
            Map<String, String> aliases = aliases();
            if (aliases.containsKey(namespaceId)) namespaceId = aliases.get(namespaceId);

            CustomChest customChest;
            Block block = location.getBlock();
            if (type == Type.IA) customChest = new ItemsAdderCustomChest(block, namespaceId);
            else if (type == Type.VANILLA) customChest = new VanillaChest(block);
            else throw new IllegalArgumentException("No such chest type: " + type);
            customChest.create();
            customChests.put(block.getLocation().toCenterLocation(), customChest);
            customChestChestTypeMap.put(customChest, randomChestType);
        }
        max = locations.size();
        left = max;
        timestamp = System.currentTimeMillis();

        if (treasureHuntType.announceStart) {
            String message = treasureHuntType.getStartMessage();
            if (message == null)
                message = config.string("messages.default-start-message", "<gold>Охота за сокровищами началась!");
            if (!(message == null || message.isBlank())) {
                if (treasureHuntType.announceStartGlobally) {
                    Set<UUID> playerUuids = PlayerManager.getPlayerUuids();
                    for (UUID uuid : playerUuids) {
                        AnnounceManager.sendMessageGlobally(uuid, message);
                    }
                } else {
                    List<Player> worldPlayers = world.getPlayers();
                    Component component = mm(message);
                    for (Player player : worldPlayers) player.sendMessage(component);
                }
            }
        }

        return customChests.keySet();
    }

    public void stop(boolean timeout) {
        if (treasureHuntType.announceStop) {
            String message = treasureHuntType.getStopMessage();
            if (message == null)
                message = config.string("messages.default-stop-message", "<gold>Охота за сокровищами завершена!");

            if (!(message == null || message.isBlank())) {
                List<Player> worldPlayers = world.getPlayers();
                Component component = mm(message);
                for (Player player : worldPlayers) player.sendMessage(component);
            }
        }

        clearChests();
        stopDisplayBossbar();
        stopDisplayingLocations();

        TreasureHuntManager.removeHunt(this);
    }

    public void clearChests() {
        NamespacedKey key = new NamespacedKey(ARC.plugin, "custom_chest");
        Collection<Location> locs = treasureHuntType.getLocationPool().getLocations().values().stream()
                .filter(ServerLocation::isSameServer)
                .map(ServerLocation::toLocation)
                .toList();
        for (Location location : locs) {
            try {
                CustomBlockData data = new CustomBlockData(location.getBlock(), ARC.plugin);
                if (data.has(key)) {
                    String type = data.get(key, PersistentDataType.STRING);
                    CustomChest customChest = null;
                    if ("ia".equals(type)) customChest = new ItemsAdderCustomChest(location.getBlock(), null);
                    else if ("vanilla".equals(type)) customChest = new VanillaChest(location.getBlock());
                    if (customChest != null) customChest.destroy();
                }
            } catch (Exception e) {
                log.error("Error while clearing chest at {}", location, e);
            }
        }
    }

    void popChest(Block block, Player player) {
        CustomChest customChest = customChests.get(block.getLocation().toCenterLocation());
        ChestType chestType = customChestChestTypeMap.get(customChest);

        locations.remove(block.getLocation().toCenterLocation());
        customChestChestTypeMap.remove(customChest);
        customChests.remove(block.getLocation().toCenterLocation());

        left--;
        if (left == 0) stop(false);

        customChest.destroy();
        String particlePath = chestType.getParticlePath();

        Particle particle = config.particle("claimed." + particlePath + ".particle", Particle.END_ROD);
        int count = config.integer("claimed." + particlePath + ".count", 15);
        double offset = config.real("claimed." + particlePath + ".offset", 0.1);
        double extra = config.real("claimed." + particlePath + ".extra", 0.05);
        String sound = config.string("claimed." + particlePath + ".sound", "ENTITY_PLAYER_LEVELUP").toUpperCase();

        executeAction(player, chestType);

        ParticleManager.queue(new ParticleBuilder(particle)
                .location(block.getLocation().toCenterLocation())
                .count(count)
                .extra(extra)
                .offset(offset, offset, offset)
                .receivers(block.getWorld().getPlayers()));
        block.getWorld().playSound(block.getLocation(), Sound.valueOf(sound), 1.0f, 1.0f);
        if (treasureHuntType.launchFireworks) {
            block.getWorld().spawnEntity(block.getLocation().toCenterLocation().add(0, 1, 0),
                    EntityType.FIREWORK_ROCKET, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                        Firework firework = (Firework) e;
                        FireworkEffect effect = FireworkEffect.builder()
                                .flicker(ThreadLocalRandom.current().nextBoolean())
                                .trail(ThreadLocalRandom.current().nextBoolean())
                                .with(Utils.random(FireworkEffect.Type.values()))
                                .withColor(Utils.random(colors, 3))
                                .withFade(Utils.random(colors, 3))
                                .build();

                        FireworkMeta meta = firework.getFireworkMeta();
                        meta.setPower(ThreadLocalRandom.current().nextInt(1, 3));
                        meta.addEffect(effect);
                        firework.setFireworkMeta(meta);
                    });
        }
    }

    private void executeAction(Player player, ChestType chestType) {
        TreasurePool treasurePool = chestType.getTreasurePool();

        Treasure treasure = treasurePool.random();
        treasure.give(player);
    }

    public void generateLocations() {
        LocationPool locationPool = treasureHuntType.getLocationPool();
        Set<ServerLocation> nRandom = locationPool.getNRandom(chests);
        locations = nRandom.stream()
                .filter(ServerLocation::isSameServer)
                .map(ServerLocation::toLocation)
                .collect(Collectors.toSet());
        locations.stream().limit(1).map(Location::getWorld).findAny().ifPresent(w -> this.world = w);
    }

    public void displayLocations() {
        stopDisplayingLocations();

        displayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (locations.isEmpty()) return;

                long seconds = (System.currentTimeMillis() - timestamp) / 1000;
                if (treasureHuntType.secondsTTL != 0) {
                    if (seconds >= treasureHuntType.secondsTTL) {
                        stop(true);
                        return;
                    }
                }

                int playerSoundEach = config.integer("idle.player-sound-each", 1);

                int soundCount = counter.getAndIncrement();
                if (soundCount >= playerSoundEach) {
                    counter.set(0);
                }
                Collection<Player> players = world.getPlayers();

                Map<Player, CustomChest> closestChests = new HashMap<>();

                for (var entry : customChestChestTypeMap.entrySet()) {
                    CustomChest customChest = entry.getKey();
                    ChestType chestType = entry.getValue();
                    String particlePath = chestType.getParticlePath();

                    Particle particle = config.particle("idle." + particlePath + ".particle", Particle.FLAME);
                    int count = config.integer("idle." + particlePath + ".count", 5);
                    double offset = config.real("idle." + particlePath + ".offset", 0.1);
                    double extra = config.real("idle." + particlePath + ".extra", 0.05);
                    int radius = config.integer("idle." + particlePath + ".radius", 30);

                    List<Player> receivers = new ArrayList<>();
                    for (Player player : players) {
                        double distance = player.getLocation().distance(customChest.getBlockLocation());
                        closestChests.compute(player, (k, v) -> {
                            if (v == null) return customChest;
                            if (distance < player.getLocation().distance(v.getBlockLocation())) return customChest;
                            return v;
                        });
                        if (distance <= radius) {
                            receivers.add(player);
                        }
                    }

                    ParticleManager.queue(new ParticleBuilder(particle)
                            .location(customChest.getBlockLocation().toCenterLocation())
                            .count(count)
                            .extra(extra)
                            .offset(offset, offset, offset)
                            .receivers(receivers));
                }
                if (soundCount == 0) {
                    try {
                        for (var entry : closestChests.entrySet()) {
                            CustomChest customChest = entry.getValue();
                            ChestType chestType = customChestChestTypeMap.get(customChest);
                            Player player = entry.getKey();
                            String particlePath = chestType.getParticlePath();
                            int soundRadius = config.integer("idle." + particlePath + ".sound-radius", 30);

                            String sound = config.string("idle." + particlePath + ".sound", "block_amethyst_cluster_hit").toUpperCase();
                            Sound soundEnum = Sound.valueOf(sound);

                            double distance = player.getLocation().distance(customChest.getBlockLocation());
                            if (distance > soundRadius) continue;

                            player.playSound(customChest.getBlockLocation().toCenterLocation(), soundEnum, 1.0f, 1.0f);
                        }
                    } catch (Exception e) {
                        log.error("Error while playing sound", e);
                    }
                }

                displayBossbar();
            }
        }.runTaskTimer(ARC.plugin, config.integer("idle.ticks", 5), config.integer("idle.ticks", 5));
    }

    public void displayBossbar() {
        String message = treasureHuntType.getBossBarMessage();
        if (message == null)
            message = config.string("messages.default-bossbar-message", "Охота за сокровищами! Осталось %left%");
        if (message == null || message.isBlank()) return;

        if (!treasureHuntType.isBossBarVisible()) {
            return;
        }

        message = message.replace("%left%", String.valueOf(left));

        String color = treasureHuntType.getBossBarColor();
        String overlay = treasureHuntType.getBossBarOverlay();

        BossBar.Color barColor;
        BossBar.Overlay barOverlay;
        try {
            barColor = BossBar.Color.valueOf(color.toUpperCase());
            barOverlay = BossBar.Overlay.valueOf(overlay.toUpperCase());
        } catch (Exception e) {
            log.error("Invalid bossbar color or overlay: {} {}", color, overlay);
            barColor = BossBar.Color.RED;
            barOverlay = BossBar.Overlay.NOTCHED_6;
        }

        List<Player> players = world.getPlayers();
        float newProgress = ((float) left) / max;
        if (bossBar == null) {
            final Component name = mm(message);
            bossBar = BossBar.bossBar(name, newProgress, barColor, barOverlay);
        } else if (bossBar.progress() != newProgress) {
            bossBar.name(mm(message));
            bossBar.progress(newProgress);
        }

        Iterator<Player> iterator = bossBarAudience.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();
            if (player.getWorld() != world) {
                player.hideBossBar(bossBar);
                iterator.remove();
            }
        }

        for (Player player : players) {
            if (!bossBarAudience.contains(player)) {
                player.showBossBar(bossBar);
                bossBarAudience.add(player);
            }
        }

    }

    public void stopDisplayBossbar() {
        if (bossBar == null) return;
        for (Player player : bossBarAudience) {
            player.hideBossBar(bossBar);
        }
    }

    public void stopDisplayingLocations() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
    }

}
