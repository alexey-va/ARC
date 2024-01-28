package arc.arc;

import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.HuskHomesHook;
import com.destroystokyo.paper.ParticleBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Portal {

    private static final Set<Block> occupiedBlocks = new HashSet<>();
    private static final Map<Player, Portal> portals = new HashMap<>();
    private static final HashSet<Material> empties = new HashSet<>() {{
        add(Material.SNOW);
        add(Material.TRIPWIRE);
        add(Material.GRASS);
        add(Material.TALL_GRASS);
        add(Material.ACACIA_SLAB);
        add(Material.ANDESITE_SLAB);
        add(Material.BRICK_SLAB);
        add(Material.BIRCH_SLAB);
        add(Material.BLACKSTONE_SLAB);
        add(Material.COBBLED_DEEPSLATE_SLAB);
        add(Material.COBBLESTONE_SLAB);
        add(Material.CRIMSON_SLAB);
        add(Material.CUT_COPPER_SLAB);
        add(Material.DIORITE_SLAB);
        add(Material.END_STONE_BRICK_SLAB);
        add(Material.DARK_OAK_SLAB);
        add(Material.JUNGLE_SLAB);
    }};
    List<Location> locations = new ArrayList<>();
    List<Location> reducedLocations = new ArrayList<>();
    volatile double phase = 0;
    AtomicBoolean success = new AtomicBoolean();
    Block block;
    Player p;
    String command = null;
    Action action;
    BukkitTask task;
    Set<String> blockChangePlayers = new HashSet<>();
    String ownerUuid;
    HuskHomesHook.MyTeleport teleport;
    double cost;


    public Portal(String uuid, HuskHomesHook.MyTeleport teleport) {
        this.teleport = teleport;
        this.p = Bukkit.getPlayer(UUID.fromString(uuid));
        this.action = Action.TPA;
        if (p == null) {
            //System.out.println("No player found!");
            return;
        }

        block = findPortalLocation();

        if (block == null) {
            executeAction(p);
            return;
        }
        if (portals.get(p) != null) portals.get(p).removePortal();
        portals.put(p, this);
        task = createTask();
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aНедалеко появляется портал..."));
    }

    public Portal(String uuid, HuskHomesHook.MyTeleport teleport, double cost) {
        this.teleport = teleport;
        this.cost = cost;
        this.p = Bukkit.getPlayer(UUID.fromString(uuid));
        this.action = Action.TPA;
        if (p == null) return;
        block = findPortalLocation();
        if (block == null) {
            executeAction(p);
            return;
        }
        if (portals.get(p) != null) portals.get(p).removePortal();
        portals.put(p, this);
        task = createTask();
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aНедалеко появляется портал..."));
    }

    public Portal(String ownerUuid, String command){
        this.action = Action.COMMAND;
        this.ownerUuid = ownerUuid;
        this.command = command;
        this.p = Bukkit.getPlayer(UUID.fromString(ownerUuid));
        if (p == null) return;
        block = findPortalLocation();
        if (block == null) {
            executeAction(p);
            return;
        }
        if (portals.get(p) != null) portals.get(p).removePortal();
        portals.put(p, this);
        task = createTask();
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aНедалеко появляется портал..."));
    }

    public static boolean occupied(Block block) {
        return occupiedBlocks.contains(block);
    }

    public static void removeAllPortals() {
        for (Portal portal : portals.values()) {
            portal.removePortal();
        }
    }

    private static boolean isSuitable(Block block) {
        if (block == null) return false;
        Block blockUp = block.getRelative(0, 1, 0);
        Block blockUp2 = blockUp.getRelative(0, 1, 0);

        if (occupiedBlocks.contains(block) || occupiedBlocks.contains(blockUp) || occupiedBlocks.contains(blockUp2))
            return false;

        return ((block.isSolid()) || block.getType().equals(Material.WATER)) && (blockUp.isEmpty() || empties.contains(blockUp.getType())) && (blockUp2.isEmpty() || empties.contains(blockUp.getType()));
    }

    private BukkitTask createTask() {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (phase > 400 || success.get()) {
                    removePortal();
                    cancel();
                    return;
                }
                final Collection<Player> nearby = block.getWorld().getPlayers();
                if (phase >= 58) {
                    List<Player> closePlayers = nearby.stream().filter(p -> p.getLocation().distance(block.getLocation().clone().add(0.5, 0, 0.5)) < 2).toList();
                    PotionEffect potionEffect = new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, false, false);
                    for (Player player : closePlayers) {
                        if (!player.hasPotionEffect(PotionEffectType.BLINDNESS)) player.addPotionEffect(potionEffect);
                    }
                }
                addLocations();
                if (phase == 58)
                    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1f, 1f);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        displayParticles(nearby);
                        Set<Player> players = new HashSet<>(nearby);
                        if (phase >= 58 && (phase == 58 || phase % 10 == 0)) {
                            placeBlocks(players);
                        }
                        if (phase >= 61) {
                            Player player = getEnteredPlayer(nearby);
                            if (player != null && !success.get()) {
                                success.set(true);
                                //System.out.println(locations.size());
                                //System.out.println(portal);
                                executeAction(player);
                                removePortal();
                            }
                        }

                    }
                }.runTaskAsynchronously(ARC.plugin);

                double currPhase = phase;
                phase = currPhase + 1;
            }
        }.runTaskTimer(ARC.plugin, 1L, 1L);
    }

    private Player getEnteredPlayer(Collection<Player> nearby) {
        if (p.hasPermission("myhome.portal.tp-other")) {
            for (Player player : nearby) {
                if (ifInPortal(player, block.getLocation()) && player.hasPermission("myhome.portal.tp-by-other"))
                    return player;
            }
        } else {
            if (ifInPortal(p, block.getRelative(0, 1, 0).getLocation())) return p;
        }
        return null;
    }

    private void executeAction(Player player) {
        if (action == Action.COMMAND) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.performCommand(command);
                }
            }.runTask(ARC.plugin);
        } else if (action == Action.TPA) {
            if(HookRegistry.huskHomesHook == null) return;
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        if (cost > 0) {
                            OfflinePlayer offlinePlayer = teleport.getPlayer();
                            if (ARC.getEcon().getBalance(offlinePlayer) < cost) {
                                Player p = offlinePlayer.getPlayer();
                                if (p != null && p.isOnline())
                                    p.sendMessage(Component.text("Недостаточно денег!", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
                                return;
                            }
                            ARC.getEcon().withdrawPlayer(offlinePlayer, cost);
                        }
                        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                        HookRegistry.huskHomesHook.teleport(teleport);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }.runTask(ARC.plugin);
        }
    }

    private void placeBlocks(Set<Player> players) {
        if (players == null || players.size() == 0) return;
        BlockData blockData = Bukkit.createBlockData(Material.END_GATEWAY);
        Map<Location, BlockData> map = Map.of(block.getRelative(0, 1, 0).getLocation(), blockData, block.getRelative(0, 2, 0).getLocation(), blockData);
        for (Player player : players) {
            if (player.getLocation().distance(block.getLocation()) < 50) player.sendMultiBlockChange(map);
        }
        blockChangePlayers.addAll(players.stream().map(pl -> pl.getUniqueId().toString()).toList());
    }

    private void clearBlocks() {
        BlockData blockData = Bukkit.createBlockData(Material.AIR);
        Map<Location, BlockData> map = Map.of(block.getRelative(0, 1, 0).getLocation(), blockData, block.getRelative(0, 2, 0).getLocation(), blockData);
        for (String s : blockChangePlayers) {
            Player player = Bukkit.getPlayer(UUID.fromString(s));
            if (player == null) continue;
            player.sendMultiBlockChange(map);
        }
    }

    private void addLocations() {
        double x = block.getX();
        double y = block.getY() + 1;
        double z = block.getZ();
        World world = block.getWorld();

        if (phase <= 10) {
            if (phase == 1) block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f);
            Location loc1 = new Location(world, x + phase / 10.0, y, z);
            Location loc2 = new Location(world, x, y + phase / 10.0, z);
            Location loc3 = new Location(world, x, y, z + phase / 10.0);
            locations.add(loc1);
            locations.add(loc2);
            locations.add(loc3);
            if (phase % 3 == 0 || phase % 10 == 0) {
                reducedLocations.add(loc1);
                reducedLocations.add(loc2);
                reducedLocations.add(loc3);
            }
        } else if (phase <= 20) {
            if (phase == 20) block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f);
            Location loc1 = new Location(world, x + 1, y + (phase - 10) / 10.0, z);
            Location loc2 = new Location(world, x, y + phase / 10.0, z);
            Location loc3 = new Location(world, x, y + (phase - 10) / 10.0, z + 1);
            Location loc4 = new Location(world, x + 1, y, z + (phase - 10) / 10.0);
            Location loc5 = new Location(world, x + (phase - 10) / 10.0, y, z + 1);
            locations.add(loc1);
            locations.add(loc2);
            locations.add(loc3);
            locations.add(loc4);
            locations.add(loc5);
            if (phase % 3 == 0 || phase % 10 == 0) {
                reducedLocations.add(loc1);
                reducedLocations.add(loc2);
                reducedLocations.add(loc3);
                reducedLocations.add(loc4);
                reducedLocations.add(loc5);
            }
        } else if (phase <= 30) {
            if (phase == 30) block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f);
            Location loc1 = new Location(world, x + 1, y + (phase - 10) / 10.0, z);
            Location loc2 = new Location(world, x + (phase - 20) / 10.0, y + 2, z);
            Location loc3 = new Location(world, x, y + 2, z + (phase - 20) / 10.0);
            Location loc4 = new Location(world, x, y + (phase - 10) / 10.0, z + 1);
            Location loc5 = new Location(world, x + 1, y + (phase - 20) / 10.0, z + 1);
            locations.add(loc1);
            locations.add(loc2);
            locations.add(loc3);
            locations.add(loc4);
            locations.add(loc5);
            if (phase % 3 == 0 || phase % 10 == 0) {
                reducedLocations.add(loc1);
                reducedLocations.add(loc2);
                reducedLocations.add(loc3);
                reducedLocations.add(loc4);
                reducedLocations.add(loc5);
            }
        } else if (phase <= 40) {
            if (phase == 40) block.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f);
            Location loc1 = new Location(world, x + 1, y + 2, z + (phase - 30) / 10.0);
            Location loc2 = new Location(world, x + (phase - 30) / 10.0, y + 2, z + 1);
            Location loc3 = new Location(world, x + 1, y + 1 + (phase - 30) / 10.0, z + 1);
            locations.add(loc1);
            locations.add(loc2);
            locations.add(loc3);
            if (phase % 3 == 0 || phase % 10 == 0) {
                reducedLocations.add(loc1);
                reducedLocations.add(loc2);
                reducedLocations.add(loc3);
            }
        }
    }

    private void displayParticles(Collection<Player> players) {
        Collection<Player> nearPlayers = players.stream().filter(p -> {
            try {
                return (p.getLocation().distance(block.getLocation()) < 50);
            } catch (Exception e) {
                return false;
            }
        }).toList();

        Collection<Player> fullParticles = new ArrayList<>();
        Collection<Player> reducedParticles = new ArrayList<>();
        for (Player player : nearPlayers) {
            if (player.hasPermission("myhome.reduce-particles")) reducedParticles.add(player);
            else fullParticles.add(player);
        }

        ParticleBuilder builder = new ParticleBuilder(Particle.DUST_COLOR_TRANSITION).offset(0.015f,
                0.015f, 0.015f).data(new Particle.DustTransition(Color.fromRGB(121, 56, 163),
                Color.fromRGB(0, 0, 0), 0.5f));
        if (fullParticles.size() > 0) {
            for (Location location : new ArrayList<>(locations)) {
                try {
                    builder.count(2).location(location).receivers(fullParticles).spawn();
                } catch (Exception ignored) {
                }
            }
        }
        if (reducedParticles.size() > 0) {
            for (Location location : new ArrayList<>(reducedLocations)) {
                try {
                    builder.count(2).location(location).receivers(reducedParticles).spawn();
                } catch (Exception ignored) {
                }
            }
        }
        if (phase >= 41 && (phase - 41) % 10 == 0) {
            new ParticleBuilder(Particle.PORTAL).count(5).receivers(nearPlayers).location(block.getRelative(0, 1, 0).getLocation().add(0.5, 0.5, 0.5)).spawn();
            new ParticleBuilder(Particle.PORTAL).count(5).receivers(nearPlayers).location(block.getRelative(0, 2, 0).getLocation().add(0.5, 0.5, 0.5)).spawn();
        }
    }

    private boolean ifInPortal(Player player, Location location) {
        return (player.getLocation().toBlockLocation().getX() == location.toBlockLocation().getX() && player.getLocation().toBlockLocation().getZ() == location.toBlockLocation().getZ() && player.getLocation().getY() - location.getY() < 1.5 && player.getLocation().getY() - location.getY() > -1);
    }

    private void removePortal() {
        if (this.task != null && !this.task.isCancelled()) this.task.cancel();
        occupiedBlocks.remove(block);
        occupiedBlocks.remove(block.getRelative(0, 1, 0));
        occupiedBlocks.remove(block.getRelative(0, 2, 0));
        occupiedBlocks.remove(block.getRelative(0, 3, 0));
        portals.remove(this.p);
        clearBlocks();
    }

    private void noLocationMessage() {
        p.sendMessage(Component.text("Не удалось найти место под портал!", NamedTextColor.RED));
    }

    private Block findPortalLocation() {
        Block targetBlock = p.getTargetBlockExact(7);
        if (targetBlock != null && !isSuitable(targetBlock)) {
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
            if (!found) targetBlock = null;
        }
        if (targetBlock == null) {
            switch ((int) Math.round(p.getLocation().getYaw() / 90.00)) {
                case -1 -> targetBlock = p.getLocation().add(0, -0.35, 0).getBlock().getRelative(2, 0, 0);
                case 0 -> targetBlock = p.getLocation().add(0, -0.35, 0).getBlock().getRelative(0, 0, 2);
                case 1 -> targetBlock = p.getLocation().add(0, -0.35, 0).getBlock().getRelative(-2, 0, 0);
                case 2, -2 -> targetBlock = p.getLocation().add(0, -0.35, 0).getBlock().getRelative(0, 0, -2);
            }
            if (!isSuitable(targetBlock)) {
                targetBlock = getCloseSuitable(p.getLocation());
            }
        }
        return targetBlock;
    }

    private Block getCloseSuitable(Location location) {
        Block b = location.getBlock();
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    Block testBlock = b.getRelative(x, y, z);
                    if (isSuitable(testBlock)) return testBlock;
                }
            }
        }

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    if (Math.abs(x) <= 3 && Math.abs(y) <= 3 && Math.abs(z) <= 3) continue;
                    Block testBlock = b.getRelative(x, y, z);
                    if (isSuitable(testBlock)) return testBlock;
                }
            }
        }
        return null;
    }

    public enum Action {
        HOME_TELEPORT, COMMAND, TPA, WARP_COMMAND
    }


}