package ru.arc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.CooldownManager;
import ru.arc.util.ParticleManager;
import ru.arc.xserver.playerlist.PlayerManager;

import static org.bukkit.Material.SNOW;
import static org.bukkit.Material.TRIPWIRE;
import static org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME;
import static org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN;
import static org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT;
import static org.bukkit.potion.PotionEffectType.BLINDNESS;
import static ru.arc.PortalData.ActionType.COMMAND;
import static ru.arc.PortalData.ActionType.HUSK;
import static ru.arc.PortalData.ActionType.TELEPORT;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class Portal {

    private static final Set<Block> occupiedBlocks = new ConcurrentSkipListSet<>(Comparator.comparingInt(Block::hashCode));
    private static final Map<UUID, Portal> portals = new ConcurrentHashMap<>();
    private static final Set<Material> empties = Set.of(SNOW, TRIPWIRE, Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.ACACIA_SLAB, Material.ANDESITE_SLAB, Material.BRICK_SLAB, Material.BIRCH_SLAB, Material.BLACKSTONE_SLAB,
            Material.COBBLED_DEEPSLATE_SLAB, Material.COBBLESTONE_SLAB, Material.CRIMSON_SLAB, Material.CUT_COPPER_SLAB,
            Material.DIORITE_SLAB, Material.END_STONE_BRICK_SLAB, Material.DARK_OAK_SLAB, Material.JUNGLE_SLAB);

    List<Location> borderLocations = new ArrayList<>();
    List<Location> reducedBorderLocations = new ArrayList<>();
    Set<Block> seenBlocks = new HashSet<>();
    Set<UUID> blockChangePlayers = new ConcurrentSkipListSet<>(Comparator.comparing(UUID::toString));

    AtomicInteger phase = new AtomicInteger();
    AtomicBoolean success = new AtomicBoolean();

    Block centerBlock;
    Player player;
    PortalData portalData;
    BukkitTask task;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");

    public Portal(UUID uuid, PortalData portalData) {
        this.portalData = portalData;
        this.player = Bukkit.getPlayer(uuid);
        if (player == null) {
            error("Player is null");
            return;
        }

        centerBlock = findPortalLocation();

        if (centerBlock == null) {
            executeAction(player);
            info("Could not find suitable location for portal near {}", player.getName());
            return;
        }

        if (portals.get(player.getUniqueId()) != null) portals.get(player.getUniqueId()).removePortal();

        task = createTask();
        portals.put(player.getUniqueId(), this);
        player.sendMessage(config.component("portal.message"));
    }

    public static boolean isOccupied(Block block) {
        return occupiedBlocks.contains(block);
    }

    private boolean isSuitable(Block block) {
        if (block == null || seenBlocks.contains(block)) return false;
        Block blockUp = block.getRelative(0, 1, 0);
        Block blockUp2 = blockUp.getRelative(0, 1, 0);
        seenBlocks.add(block);

        if (occupiedBlocks.contains(block) || occupiedBlocks.contains(blockUp) || occupiedBlocks.contains(blockUp2))
            return false;

        return ((block.isSolid()) || block.getType().equals(Material.WATER))
                && (blockUp.isEmpty() || empties.contains(blockUp.getType()))
                && (blockUp2.isEmpty() || empties.contains(blockUp.getType()));
    }

    private BukkitTask createTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (phase.get() > 400 || success.get()) {
                    removePortal();
                    return;
                }
                double particleDistance = config.real("portal.particle-distance", 50.0);
                final Set<Player> nearbyPlayers = new HashSet<>();
                for (Player p : centerBlock.getWorld().getPlayers()) {
                    if (p.getLocation().distance(centerBlock.getLocation()) < particleDistance) {
                        nearbyPlayers.add(p);
                    }
                }
                if (phase.get() >= 58 && config.bool("portal.blindness", true)) {
                    double radius = config.real("portal.blindness-radius", 2);
                    int duration = config.integer("portal.blindness-duration", 40);
                    List<Player> closePlayers = nearbyPlayers.stream()
                            .filter(p -> p.getLocation().distance(centerBlock.getLocation().clone().add(0.5, 0, 0.5)) < radius)
                            .toList();
                    PotionEffect potionEffect = new PotionEffect(BLINDNESS, duration, 0, false, false, false);
                    for (Player player : closePlayers) {
                        if (!player.hasPotionEffect(BLINDNESS)) player.addPotionEffect(potionEffect);
                    }
                }
                addLocations();
                if (phase.get() == 58)
                    centerBlock.getWorld().playSound(centerBlock.getLocation(), BLOCK_END_PORTAL_SPAWN, 1f, 1f);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        displayParticles(nearbyPlayers);
                        if (phase.get() >= 58 && (phase.get() == 58 || phase.get() % 10 == 0))
                            placeBlocksPackets(nearbyPlayers);
                        if (phase.get() >= 61) {
                            Player player = getEnteredPlayer(nearbyPlayers);
                            if (player != null && !success.getAndSet(true)) executeAction(player);
                        }
                    }
                }.runTaskAsynchronously(ARC.plugin);
                phase.incrementAndGet();
            }
        }.runTaskTimer(ARC.plugin, 1L, 1L);
    }

    private Player getEnteredPlayer(Collection<Player> nearby) {
        for (Player p : nearby) {
            if (!inPortal(p, centerBlock.getLocation())) continue;
            if (p == player) return p;
            if (!p.hasPermission("arc.portal.tp-by-other")) {
                CooldownManager.onCooldown(p.getUniqueId(), "portal_tp_by_other_message", 60, () -> {
                    p.sendMessage(config.componentDef("portal.tp-by-other-disabled.message",
                            "<red>Вы не можете телепортироваться через порталы других игроков!"));
                });
            }
            if (!player.hasPermission("arc.portal.tp-other")) {
                CooldownManager.onCooldown(player.getUniqueId(), "portal_tp_other_message", 60, () -> {
                    p.sendMessage(config.componentDef("portal.tp-other-disabled.message",
                            "<red>Этот игрок не разрешил другим игрокам телепортироваться через его порталы!"));
                });
            }
            return p;
        }
        return null;
    }

    private void executeAction(Player player) {
        var actionType = portalData.getActionType();
        if (actionType == COMMAND) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.getWorld().playSound(player.getLocation(), ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.performCommand(portalData.getCommand());
                }
            }.runTask(ARC.plugin);
        } else if (actionType == HUSK) {
            if (HookRegistry.huskHomesHook == null) {
                error("HuskHomes hook is not active!");
                return;
            }
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.getWorld().playSound(player.getLocation(), ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    HookRegistry.huskHomesHook.teleport(portalData.getHuskTeleport(), player);
                }
            }.runTask(ARC.plugin);
        } else if (actionType == TELEPORT) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.getWorld().playSound(player.getLocation(), ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.teleport(portalData.getLocation());
                }
            }.runTask(ARC.plugin);
        }
    }

    @SuppressWarnings("all")
    private void placeBlocksPackets(Set<Player> players) {
        if (players == null || players.isEmpty()) return;
        BlockData blockData = Bukkit.createBlockData(Material.END_GATEWAY);
        Map<Location, BlockData> map = Map.of(
                centerBlock.getRelative(0, 1, 0).getLocation(), blockData,
                centerBlock.getRelative(0, 2, 0).getLocation(), blockData);
        for (Player player : players) {
            if (player.getWorld() != centerBlock.getWorld()) continue;
            player.sendMultiBlockChange(map);
            blockChangePlayers.add(player.getUniqueId());
        }
    }

    @SuppressWarnings("all")
    private void clearBlockPackets() {
        Map<Location, BlockData> map = Map.of(
                centerBlock.getRelative(0, 1, 0).getLocation(), centerBlock.getRelative(0, 1, 0).getBlockData(),
                centerBlock.getRelative(0, 2, 0).getLocation(), centerBlock.getRelative(0, 2, 0).getBlockData()
        );
        for (Player p : PlayerManager.getOnlinePlayersThreadSafe()) {
            if (p == null || !blockChangePlayers.contains(p.getUniqueId()) || p.getWorld() != centerBlock.getWorld())
                continue;
            p.sendMultiBlockChange(map);
        }
    }

    private void addLocations() {
        double x = centerBlock.getX();
        double y = centerBlock.getY() + 1;
        double z = centerBlock.getZ();
        World world = centerBlock.getWorld();
        if (phase.get() % 10 == 0 && phase.get() <= 40)
            centerBlock.getWorld().playSound(centerBlock.getLocation(), BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f);
        if (phase.get() <= 10) {
            Location loc1 = new Location(world, x + phase.get() / 10.0, y, z);
            Location loc2 = new Location(world, x, y + phase.get() / 10.0, z);
            Location loc3 = new Location(world, x, y, z + phase.get() / 10.0);
            Collections.addAll(borderLocations, loc1, loc2, loc3);
            if (phase.get() % 2 == 0 || phase.get() % 10 == 0)
                Collections.addAll(reducedBorderLocations, loc1, loc2, loc3);
        } else if (phase.get() <= 20) {
            Location loc1 = new Location(world, x + 1, y + (phase.get() - 10) / 10.0, z);
            Location loc2 = new Location(world, x, y + phase.get() / 10.0, z);
            Location loc3 = new Location(world, x, y + (phase.get() - 10) / 10.0, z + 1);
            Location loc4 = new Location(world, x + 1, y, z + (phase.get() - 10) / 10.0);
            Location loc5 = new Location(world, x + (phase.get() - 10) / 10.0, y, z + 1);
            Collections.addAll(borderLocations, loc1, loc2, loc3, loc4, loc5);
            if (phase.get() % 2 == 0 || phase.get() % 10 == 0)
                Collections.addAll(reducedBorderLocations, loc1, loc2, loc3, loc4, loc5);
        } else if (phase.get() <= 30) {
            Location loc1 = new Location(world, x + 1, y + (phase.get() - 10) / 10.0, z);
            Location loc2 = new Location(world, x + (phase.get() - 20) / 10.0, y + 2, z);
            Location loc3 = new Location(world, x, y + 2, z + (phase.get() - 20) / 10.0);
            Location loc4 = new Location(world, x, y + (phase.get() - 10) / 10.0, z + 1);
            Location loc5 = new Location(world, x + 1, y + (phase.get() - 20) / 10.0, z + 1);
            Collections.addAll(borderLocations, loc1, loc2, loc3, loc4, loc5);
            if (phase.get() % 2 == 0 || phase.get() % 10 == 0)
                Collections.addAll(reducedBorderLocations, loc1, loc2, loc3, loc4, loc5);
        } else if (phase.get() <= 40) {
            Location loc1 = new Location(world, x + 1, y + 2, z + (phase.get() - 30) / 10.0);
            Location loc2 = new Location(world, x + (phase.get() - 30) / 10.0, y + 2, z + 1);
            Location loc3 = new Location(world, x + 1, y + 1 + (phase.get() - 30) / 10.0, z + 1);
            Collections.addAll(borderLocations, loc1, loc2, loc3);
            if (phase.get() % 2 == 0 || phase.get() % 10 == 0)
                Collections.addAll(reducedBorderLocations, loc1, loc2, loc3);
        }
    }

    private void displayParticles(Collection<Player> nearbyPlayers) {
        Collection<Player> nearPlayers = nearbyPlayers.stream()
                .filter(p -> p != null && p.isOnline() && p.getWorld() == centerBlock.getWorld())
                .toList();

        Collection<Player> fullPlayers = new ArrayList<>();
        Collection<Player> reducedParticles = new ArrayList<>();
        for (Player player : nearPlayers) {
            if (player.hasPermission("myhome.reduce-particles")) reducedParticles.add(player);
            else fullPlayers.add(player);
        }

        int redStart = config.integer("portal.border.color.red-start", 121);
        int greenStart = config.integer("portal.border.color.green-start", 56);
        int blueStart = config.integer("portal.border.color.blue-start", 163);

        int redEnd = config.integer("portal.border.color.red-end", 0);
        int greenEnd = config.integer("portal.border.color.green-end", 0);
        int blueEnd = config.integer("portal.border.color.blue-end", 0);

        float size = (float) config.real("portal.border.color.size", 0.5f);

        float offset = (float) config.real("portal.border.offset", 0.015f);
        int count = config.integer("portal.border.count", 2);
        Particle particle = config.particle("portal.border.particle", Particle.DUST_COLOR_TRANSITION);

        var data = new Particle.DustTransition(Color.fromRGB(redStart, greenStart, blueStart),
                Color.fromRGB(redEnd, greenEnd, blueEnd), size);

        if (!fullPlayers.isEmpty()) {
            for (Location location : new ArrayList<>(borderLocations)) {
                new ParticleBuilder(particle)
                        .count(count)
                        .location(location)
                        .receivers(fullPlayers)
                        .offset(offset, offset, offset)
                        .data(data).spawn();
            }
        }
        if (!reducedParticles.isEmpty()) {
            for (Location location : new ArrayList<>(reducedBorderLocations)) {
                new ParticleBuilder(particle)
                        .count(count)
                        .location(location)
                        .receivers(reducedParticles)
                        .offset(offset, offset, offset)
                        .data(data).spawn();
            }
        }

        if (phase.get() >= 41 && (phase.get() - 41) % 10 == 0) {
            int portalParticleCount = config.integer("portal.portal-particle.count", 5);
            Particle portalParticle = config.particle("portal.portal-particle.particle", Particle.PORTAL);
            double portalParticleExtra = config.real("portal.portal-particle.extra", 0.2);
            float portalParticleOffset = (float) config.real("portal.portal-particle.offset", 0.3);
            ParticleManager.queue(new ParticleBuilder(portalParticle)
                    .count(portalParticleCount)
                    .location(centerBlock.getRelative(0, 1, 0).getLocation().add(0.5, 0.5, 0.5))
                    .receivers(nearPlayers)
                    .extra(portalParticleExtra)
                    .offset(portalParticleOffset, portalParticleOffset, portalParticleOffset)
                    .spawn());
            ParticleManager.queue(new ParticleBuilder(portalParticle)
                    .count(portalParticleCount)
                    .location(centerBlock.getRelative(0, 2, 0).getLocation().add(0.5, 0.5, 0.5))
                    .receivers(nearPlayers)
                    .extra(portalParticleExtra)
                    .offset(portalParticleOffset, portalParticleOffset, portalParticleOffset)
                    .spawn());
        }
    }

    private boolean inPortal(Player player, Location location) {
        return (player.getLocation().toBlockLocation().getX() == location.toBlockLocation().getX()
                && player.getLocation().toBlockLocation().getZ() == location.toBlockLocation().getZ()
                && player.getLocation().getY() - location.getY() < 1.5
                && player.getLocation().getY() - location.getY() > -1);
    }

    private void removePortal() {
        if (this.task != null && !this.task.isCancelled()) this.task.cancel();
        occupiedBlocks.remove(centerBlock);
        occupiedBlocks.remove(centerBlock.getRelative(0, 1, 0));
        occupiedBlocks.remove(centerBlock.getRelative(0, 2, 0));
        occupiedBlocks.remove(centerBlock.getRelative(0, 3, 0));
        portals.remove(player.getUniqueId());
        clearBlockPackets();
    }

    private Block findPortalLocation() {
        Block targetBlock = player.getTargetBlockExact(4);
        if (isSuitable(targetBlock)) return targetBlock;
        if (targetBlock == null) {
            switch ((int) Math.round(player.getLocation().getYaw() / 90.00)) {
                case -1 -> targetBlock = player.getLocation().add(0, -0.35, 0).getBlock().getRelative(2, 0, 0);
                case 0 -> targetBlock = player.getLocation().add(0, -0.35, 0).getBlock().getRelative(0, 0, 2);
                case 1 -> targetBlock = player.getLocation().add(0, -0.35, 0).getBlock().getRelative(-2, 0, 0);
                default -> targetBlock = player.getLocation().add(0, -0.35, 0).getBlock().getRelative(0, 0, -2);
            }
        }
        boolean found = false;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    if (i == 0 && j == 0 && k == 0) continue;
                    Block newBlock = targetBlock.getRelative(i, j, k);
                    if (isSuitable(newBlock)) {
                        found = true;
                        targetBlock = newBlock;
                        break;
                    }
                }
                if (found) break;
            }
            if (found) break;
        }
        if (!found) targetBlock = getCloseSuitable(player.getLocation());
        return targetBlock;
    }

    private Block getCloseSuitable(Location location) {
        Block b = location.getBlock();
        int radiusInitial = config.integer("portal.radius-initial", 3);
        for (int x = -radiusInitial; x <= radiusInitial; x++) {
            for (int y = -radiusInitial; y <= radiusInitial; y++) {
                for (int z = -radiusInitial; z <= radiusInitial; z++) {
                    Block testBlock = b.getRelative(x, y, z);
                    if (isSuitable(testBlock)) return testBlock;
                }
            }
        }

        int radiusSecondary = config.integer("portal.radius-secondary", 6);
        for (int x = -radiusSecondary; x <= radiusSecondary; x++) {
            for (int y = -radiusSecondary; y <= radiusSecondary; y++) {
                for (int z = -radiusSecondary; z <= radiusSecondary; z++) {
                    if (Math.abs(x) <= radiusInitial && Math.abs(y) <= radiusInitial && Math.abs(z) <= radiusInitial)
                        continue;
                    Block testBlock = b.getRelative(x, y, z);
                    if (isSuitable(testBlock)) return testBlock;
                }
            }
        }
        return null;
    }
}
