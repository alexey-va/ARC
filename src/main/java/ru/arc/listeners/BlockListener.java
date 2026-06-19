package ru.arc.listeners;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jeff_media.customblockdata.CustomBlockData;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.entity.Bee;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.arc.ARC;
import ru.arc.autobuild.BuildingManager;
import ru.arc.autobuild.ConstructionSite;
import ru.arc.autobuild.ConstructionState;
import ru.arc.bschests.PersonalLootModule;
import ru.arc.common.locationpools.LocationPoolManager;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.farm.FarmManager;
import ru.arc.leafdecay.LeafDecayManager;
import ru.arc.treasure.core.TreasurePool;
import ru.arc.treasure.core.Treasures;
import ru.arc.treasurechests.ActiveHunt;
import ru.arc.treasurechests.TreasureHuntManager;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class BlockListener implements Listener {

    private static final ConcurrentHashMap<UUID, Long> TREASURE_USE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long TREASURE_USE_COOLDOWN_MS = 500L;

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        LocationPoolManager.processLocationPool(event);
        processPlaceForLeaves(event);
        processPlaceBees(event);
    }

    private static final NamespacedKey BEE_KEY = new NamespacedKey(ARC.getInstance(), "bee");
    private static final Config beeConfig = ConfigManager.of(ARC.getInstance().getDataFolder().toPath(), "misc.yml");

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        processTreasureHunt(event);
        processBuildingEvent(event);
        processBees(event);
        processTreasureItemUse(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreakLow(BlockBreakEvent event) {
        processFarmBreak(event);
        PersonalLootModule.processChestBreak(event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreakHigh(BlockBreakEvent event) {
        CustomBlockData data = new CustomBlockData(event.getBlock(), ARC.getInstance());
        data.clear();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChestClick(InventoryOpenEvent event) {
        //if (HookRegistry.bsHook == null) return;
        //info("Chest open event");
        PersonalLootModule.processChestOpen(event);
    }

    private void processTreasureItemUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        if (hand != EquipmentSlot.HAND) return;

        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long lastUse = TREASURE_USE_COOLDOWN.get(playerId);
        if (lastUse != null && now - lastUse < TREASURE_USE_COOLDOWN_MS) {
            event.setCancelled(true);
            return;
        }

        NBT.get(item, data -> {
            if (!data.hasTag("arc:treasure_key")) {
                return;
            }

            String treasureKey = data.getString("arc:treasure_key");
            TreasurePool pool = Treasures.INSTANCE.getPool(treasureKey);
            if (pool == null) {
                debug("[treasure] pool {} not found for player {}", treasureKey, event.getPlayer().getName());
                error("Treasure pool {} not found", treasureKey);
                return;
            }
            if (pool.isEmpty()) {
                debug("[treasure] pool {} is empty for player {}", treasureKey, event.getPlayer().getName());
                error("Treasure pool {} is empty", treasureKey);
                return;
            }

            ItemStack handItem = event.getPlayer().getInventory().getItemInMainHand();
            if (handItem.getType() == Material.AIR || handItem.getAmount() < 1) {
                return;
            }

            TREASURE_USE_COOLDOWN.put(playerId, now);
            event.setCancelled(true);

            if (handItem.getAmount() > 1) {
                handItem.setAmount(handItem.getAmount() - 1);
            } else {
                event.getPlayer().getInventory().setItemInMainHand(null);
            }

            var treasure = pool.random();
            if (treasure != null) {
                Treasures.INSTANCE.getService().give(treasure, event.getPlayer());
            }
            event.getPlayer().playSound(event.getPlayer().getLocation(), "ui.loom.take_result", 1, 1);
        });
    }

    private void processPlaceBees(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BEEHIVE && block.getType() != Material.BEEHIVE) return;
        CustomBlockData cbd = new CustomBlockData(block, ARC.getInstance());
        cbd.set(BEE_KEY, PersistentDataType.BOOLEAN, true);
    }

    private void processBees(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.BEE_NEST && block.getType() != Material.BEEHIVE) return;
        if (CustomBlockData.hasCustomBlockData(block, ARC.getInstance())) {
            return;
        }
        if (!beeConfig.bool("bees.enabled", false)) return;
        World world = block.getWorld();
        Set<String> worlds = new HashSet<>(beeConfig.stringList("bees.worlds"));
        if (!worlds.contains(world.getName())) return;
        if (block.getState() instanceof Beehive beehive) {
            int amount = beeConfig.integer("bees.amount", 2);
            for (int i = 0; i < amount; i++) {
                block.getWorld().spawn(beehive.getLocation().add(0.5, 1, 0.5), Bee.class, beehive::addEntity);
            }
            beehive.update();
            CustomBlockData cbd = new CustomBlockData(block, ARC.getInstance());
            cbd.set(BEE_KEY, PersistentDataType.BOOLEAN, true);
        }
    }

    private void processPlaceForLeaves(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        if (event.getPlayer().hasPermission("arc.leafdecay.bypass")) return;
        LeafDecayManager.markAsPlayerPlaced(event.getBlock());
    }

    @SuppressWarnings("deprecation")
    private void processBuildingEvent(PlayerInteractEvent event) {
        if (!event.hasItem()) return;
        ItemStack hand = event.getItem();
        if (hand == null || hand.getType() != Material.BOOK) return;
        if (!event.getPlayer().hasPermission("arc.buildings.build")) {
            event.getPlayer().sendMessage(TextUtil.noPermissions());
            return;
        }

        if (!event.hasBlock() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            ConstructionSite site = BuildingManager.getPendingConstruction(event.getPlayer().getUniqueId());
            if (site != null && site.getState() == ConstructionState.DisplayingOutline.INSTANCE) {
                BuildingManager.cancelConstruction(site);
                return;
            }
        }
        if (event.getClickedBlock() == null) return;
        Location center = event.getClickedBlock().getLocation().add(0, 1, 0);
        NBTItem nbtItem = new NBTItem(hand);
        String buildingId = nbtItem.getString("arc:building_key");

        if (buildingId == null || buildingId.isEmpty()) return;

        String rotation = null;
        if (nbtItem.hasKey("arc:rotation")) rotation = nbtItem.getString("arc:rotation");
        String yOff = null;
        if (nbtItem.hasKey("arc:y_offset")) yOff = nbtItem.getString("arc:y_offset");

        event.setCancelled(true);
        BuildingManager.processPlayerClick(event.getPlayer(), center, buildingId, rotation, yOff);
    }


    private void processFarmBreak(BlockBreakEvent event) {
        FarmManager.processEvent(event);
    }

    private void processTreasureHunt(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        // get attched blocks to check for treasure hunt
        List<Block> blocks = new ArrayList<>();
        blocks.add(block);
        blocks.add(block.getRelative(1, 0, 0));
        blocks.add(block.getRelative(-1, 0, 0));
        blocks.add(block.getRelative(0, 0, 1));
        blocks.add(block.getRelative(0, 0, -1));
        blocks.add(block.getRelative(0, 1, 0));
        blocks.add(block.getRelative(0, -1, 0));

        ActiveHunt treasureHunt = null;
        for (Block b : blocks) {
            treasureHunt = TreasureHuntManager.getByBlock(b);
            if (treasureHunt != null) {
                block = b;
                break;
            }
        }
        if(treasureHunt == null) return;
        info("Player {} found treasure hunt chest at {}", event.getPlayer().getName(), block.getLocation());
        event.setCancelled(true);
        TreasureHuntManager.popChest(block, treasureHunt, event.getPlayer());
    }

}
