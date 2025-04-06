package ru.arc.bschests;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.network.repos.ItemList;
import ru.arc.network.repos.RedisRepo;
import ru.arc.util.GuiUtils;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ru.arc.util.Utils.*;

@Slf4j
public class PersonalLootManager {

    private static final Set<Material> chests = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
    private static final NamespacedKey key = new NamespacedKey(ARC.plugin, "ploot");
    private static final NamespacedKey uuidKey = new NamespacedKey(ARC.plugin, "ploot_uuid");
    private static final NamespacedKey poolKey = new NamespacedKey(ARC.plugin, "ploot_pool");
    private static final NamespacedKey breakKey = new NamespacedKey(ARC.plugin, "ploot_break");
    private static Config config;
    private static Set<InventoryType> inventories;
    private static int maxPlayers;
    private static boolean useBsLoot = false;
    private static final String SEPARATOR = ":::";

    private static RedisRepo<CustomLootData> repo;
    private static ChestGenerator chestGenerator;

    public static void init() {
        reload();
        if (repo == null) {
            repo = RedisRepo.builder(CustomLootData.class)
                    .saveBackups(false)
                    .saveInterval(20L)
                    .storageKey("arc." + ARC.serverName + "-ploot")
                    .redisManager(ARC.redisManager)
                    .updateChannel("arc." + ARC.serverName + "-ploot-update")
                    .id(ARC.serverName + "-ploot")
                    .loadAll(true)
                    .build();
        }
    }

    public static void shutdown() {
        repo.forceSave();
    }

    public static void reload() {
        config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "personalloot.yml");
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
        breaks = breaks == null ? 1 : breaks + 1;
        if (breaks >= config.integer("max-breaks", 3)) {
            Inventory itemStacks = extractInventory(block);
            itemStacks.clear();
            data.clear();
        } else {
            data.set(breakKey, PersistentDataType.INTEGER, breaks);
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    config.componentDef("messages.break",
                            "<red>Этот сундук нужно сломать еще <amount> раз",
                            "<amount>", String.valueOf(config.integer("max-breaks", 3) - breaks))
            );
        }
    }

    public static void processChestOpen(InventoryOpenEvent event) {
        if (inventories == null || !inventories.contains(event.getInventory().getType())) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Location location = event.getInventory().getLocation();
        if (location == null) return;
        //log.info("Opening inventory: {}", location);

        Block block = event.getInventory().getLocation().getBlock();
        List<Block> blocks = connectedChests(block);

        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        if (!data.has(uuidKey)) return;
        UUID chestUuid = UUID.fromString(data.get(uuidKey, PersistentDataType.STRING));
        String playerListString = data.get(key, PersistentDataType.STRING);
        if (playerListString == null) {
            log.warn("Player list string is null");
            return;
        }

        Set<UUID> players = Arrays.stream(playerListString.split(SEPARATOR))
                .filter(s -> !s.isEmpty())
                .filter(s -> s.length() == 36)
                .map(UUID::fromString)
                .collect(Collectors.toSet());

        //log.info("Chest data: uuid {} players {} thisPlayer {}", chestUuid, players, player.getName());
        event.setCancelled(true);

        if (players.size() >= maxPlayers && !players.contains(player.getUniqueId())) {
            player.sendMessage(config.componentDef("messages.max-players",
                    "<red>Слишком много игроков уже открыли этот сундук!",
                    "%amount%", String.valueOf(players.size())));
            //log.info("Too many players already opened this!");
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

        repo.getOrCreate(player.getUniqueId() + SEPARATOR + chestUuid, () -> CustomLootData.builder()
                        .playerUuid(player.getUniqueId())
                        .chestUuid(chestUuid)
                        .timestamp(System.currentTimeMillis())
                        .build())
                .thenApply(cl -> {
                    if (cl == null || cl.isExhausted()) {
                        player.sendMessage(config.componentDef("messages.already-opened", "<red>Вы уже открывали этот сундук"));
                        return null;
                    } else if (!cl.isFilled() && (cl.items == null || cl.items.isEmpty())) {
                        ItemList generated = new ItemList();
                        if (useBsLoot) generated.addAll(currentItems);
                        else generated = chestGenerator.generate(poolName, player, 5, 27);

                        if (cl.items == null) cl.items = new ItemList();
                        cl.items.addAll(generated);
                        cl.setFilled(true);
                        cl.setDirty(true);
                    }
                    return cl;
                }).thenAccept(cl -> {
                    if (cl == null) return;
                    GuiUtils.constructAndShowAsync(() -> new LootGui(player, cl), player);
                });
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
}
