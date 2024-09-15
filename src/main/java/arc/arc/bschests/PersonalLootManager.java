package arc.arc.bschests;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.configs.MainConfig;
import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.GuiUtils;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class PersonalLootManager {

    private static Config config;
    private static Set<InventoryType> inventories;
    private static Set<Material> chests = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
    private static int maxPlayers;
    private static boolean useBsLoot = false;

    private static NamespacedKey key = new NamespacedKey(ARC.plugin, "ploot");
    private static NamespacedKey uuidKey = new NamespacedKey(ARC.plugin, "ploot_uuid");
    private static NamespacedKey poolKey = new NamespacedKey(ARC.plugin, "ploot_pool");
    private static NamespacedKey breakKey = new NamespacedKey(ARC.plugin, "ploot_break");
    private static String SEPARATOR = ":::";
    private static int BREAKS = 5;

    private static RedisRepo<CustomLootData> repo;
    private static ChestGenerator chestGenerator;

    public static void init() {
        reload();
        if (repo == null) {
            repo = RedisRepo.builder(CustomLootData.class)
                    .saveBackups(false)
                    .saveInterval(20L)
                    .storageKey("arc." + MainConfig.server + "-ploot")
                    .redisManager(ARC.redisManager)
                    .updateChannel("arc." + MainConfig.server + "-ploot-update")
                    .id(MainConfig.server + "-ploot")
                    .loadAll(true)
                    .build();
        }
    }

    public static void shutdown() {
        repo.forceSave();
    }

    public static void reload() {
        config = ConfigManager.getOrCreate(ARC.plugin.getDataFolder().toPath(), "personalloot.yml", "personalloot");
        maxPlayers = config.integer("max-players", 5);
        inventories = config.stringList("inventories").stream()
                .map(String::toUpperCase)
                .map(InventoryType::valueOf)
                .collect(Collectors.toSet());
        chestGenerator = new ChestGenerator(config);
        useBsLoot = config.bool("use-bs-loot", false);
    }

    public static void processChestBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!chests.contains(block.getType())) return;
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        if (!data.has(uuidKey)) return;
        Integer breaks = data.get(breakKey, PersistentDataType.INTEGER);
        breaks = breaks == null ? 0 : breaks;
        breaks++;
        if (breaks > BREAKS) {
            data.clear();
            event.setDropItems(false);
        } else {
            data.set(breakKey, PersistentDataType.INTEGER, breaks);
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    config.component("break-message", "%amount%", String.valueOf(BREAKS - breaks))
            );
        }
    }

    public static void processChestOpen(InventoryOpenEvent event) {
        if (inventories == null || !inventories.contains(event.getInventory().getType())) {
            //log.warn("Inventory type not in list: {}", event.getInventory().getType());
            return;
        }
        Player player = (Player) event.getPlayer();
        Location location = event.getInventory().getLocation();
        if (location == null) return;
        //log.info("Opening inventory: {}", location);
        Block block = event.getInventory().getLocation().getBlock();
        List<Block> blocks = connectedChests(block);
        //log.info("Connected chests: {}", blocks);
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        if (!data.has(uuidKey)) return;
        UUID chestUuid = UUID.fromString(data.get(uuidKey, PersistentDataType.STRING));
        String playerListString = data.get(key, PersistentDataType.STRING);
        if (playerListString == null) {
            log.warn("Player list string is null");
            return;
        }
        String[] split = playerListString.split(SEPARATOR);
        Set<UUID> players = Arrays.stream(split)
                .filter(s -> !s.isEmpty())
                .filter(s -> s.length() == 36)
                .map(UUID::fromString)
                .collect(Collectors.toSet());
        boolean isPlayerInList = players.contains(player.getUniqueId());
        if (players.size() >= maxPlayers && !players.contains(player.getUniqueId())) {
            player.sendMessage(config.component("max-players-message", "%amount%", String.valueOf(players.size())));
            event.setCancelled(true);
            return;
        }
        players.add(player.getUniqueId());
        for (Block b : blocks) {
            CustomBlockData bData = new CustomBlockData(b, ARC.plugin);
            bData.set(key, PersistentDataType.STRING, players.stream().map(UUID::toString).collect(Collectors.joining(SEPARATOR)));
        }
        String poolName = data.get(poolKey, PersistentDataType.STRING);
        List<ItemStack> currentItems = blocks.stream()
                .flatMap(b -> extractItems(b).stream())
                .map(stack -> stack == null ? null : stack.clone())
                .toList();

        if (!useBsLoot) {
            Inventory inventory = extractInventory(block);
            if (inventory != null) inventory.clear();
        }

        event.setCancelled(true);
        //log.info("Player in list: {}", isPlayerInList);
        CompletableFuture<CustomLootData> future = isPlayerInList ?
                repo.getOrNull(player.getUniqueId() + SEPARATOR + chestUuid) :
                repo.getOrCreate(player.getUniqueId() + SEPARATOR + chestUuid, () -> CustomLootData.builder()
                        .playerUuid(player.getUniqueId())
                        .chestUuid(chestUuid)
                        .timestamp(System.currentTimeMillis())
                        .build());
        future.thenApply(cl -> {
            try {
                if (cl == null) {
                    player.sendMessage(
                            config.component("already-opened-message")
                    );
                    return null;
                }
                if (!cl.isFilled() && (cl.items == null || cl.items.isEmpty())) {
                    //log.info("Generating loot for player: {}", player);
                    ItemList generated = new ItemList();
                    if (useBsLoot) {
                        generated.addAll(currentItems);
                    } else {
                        generated = chestGenerator.generate(poolName, player, 5, 27);
                    }
                    if (cl.items == null) cl.items = new ItemList();
                    cl.items.addAll(generated);
                    cl.setFilled(true);
                    cl.setDirty(true);
                }
                return cl;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }).thenAccept(cl -> {
            if (cl == null) return;
            try {
                GuiUtils.constructAndShowAsync(() -> new LootGui(player, cl), player);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static List<ItemStack> extractItems(Block block) {
        Inventory inventory = extractInventory(block);
        if (inventory == null) return Collections.emptyList();
        return Arrays.stream(inventory.getContents())
                .toList();
    }

    private static Inventory extractInventory(Block block) {
        if (block.getState() instanceof DoubleChest doubleChest) {
            return doubleChest.getInventory();
        } else if (block.getState() instanceof Chest chest) {
            return chest.getInventory();
        } else if (block.getState() instanceof Barrel barrel) {
            return barrel.getInventory();
        } else {
            return null;
        }
    }

    public static void processChestGen(Block block) {
        List<Block> blocks = connectedChests(block);
        for (Block b : blocks) {
            //log.info("Processing chest: {}", b);
            CustomBlockData data = new CustomBlockData(b, ARC.plugin);
            data.set(key, PersistentDataType.STRING, "");
            data.set(uuidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            String treasurePool = genTreasurePool(b);
            data.set(poolKey, PersistentDataType.STRING, useBsLoot ? "default" : treasurePool);
            data.set(breakKey, PersistentDataType.INTEGER, 0);
        }
    }

    private static String genTreasurePool(Block block) {
        return "generic_bs";
    }


    private static List<Block> connectedChests(Block block) {
        Set<Block> blocks = new HashSet<>();
        BlockState state = block.getState();
        if (state instanceof Barrel) {
            blocks.add(block);
        }
        if (state instanceof DoubleChest doubleChest) {
            Chest left = (Chest) doubleChest.getLeftSide();
            Chest right = (Chest) doubleChest.getRightSide();
            //log.info("Left: {} Right: {}", left, right);
            if (left != null) blocks.add(left.getBlock());
            if (right != null) blocks.add(right.getBlock());
        }
        if (state instanceof Chest) {
            blocks.add(block);
        }
        return blocks.stream().toList();
    }

}
