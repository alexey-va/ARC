package ru.arc.listeners;

import com.jeff_media.customblockdata.CustomBlockData;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.NBTItem;
import lombok.extern.slf4j.Slf4j;
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
import ru.arc.bschests.PersonalLootManager;
import ru.arc.common.locationpools.LocationPoolManager;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.farm.FarmManager;
import ru.arc.leafdecay.LeafDecayManager;
import ru.arc.treasurechests.TreasureHunt;
import ru.arc.treasurechests.TreasureHuntManager;
import ru.arc.util.TextUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@Slf4j
public class BlockListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        LocationPoolManager.processLocationPool(event);
        processPlaceForLeaves(event);
        processPlaceBees(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreakLow(BlockBreakEvent event) {
        processFarmBreak(event);
        PersonalLootManager.processChestBreak(event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreakHigh(BlockBreakEvent event) {
        CustomBlockData data = new CustomBlockData(event.getBlock(), ARC.plugin);
        data.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        processTreasureHunt(event);
        processBuildingEvent(event);
        processBees(event);
        processTreasureItemUse(event);
    }


    @EventHandler(priority = EventPriority.LOW)
    public void onChestClick(InventoryOpenEvent event) {
        //if (HookRegistry.bsHook == null) return;
        //info("Chest open event");
        PersonalLootManager.processChestOpen(event);
    }

    private static final NamespacedKey BEE_KEY = new NamespacedKey(ARC.plugin, "bee");

    private void processTreasureItemUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        if (hand != EquipmentSlot.HAND) return;
        NBT.get(item, data -> {
            //info("NBT data: {}", data);
            if (data.hasTag("arc:treasure_key")) {
                String treasureKey = data.getString("arc:treasure_key");
                TreasurePool pool = TreasurePool.getTreasurePool(treasureKey);
                if (pool == null) {
                    error("Treasure pool {} not found", treasureKey);
                    return;
                }
                int itemAmount = item.getAmount();
                if (itemAmount > 1) {
                    item.setAmount(itemAmount - 1);
                } else {
                    int heldItemSlot = event.getPlayer().getInventory().getHeldItemSlot();
                    event.getPlayer().getInventory().setItem(heldItemSlot, null);
                }
                pool.random().give(event.getPlayer());
                event.getPlayer().playSound(event.getPlayer().getLocation(), "ui.loom.take_result", 1, 1);
                event.setCancelled(true);
            }
        });
    }

    private void processPlaceBees(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BEEHIVE && block.getType() != Material.BEEHIVE) return;
        CustomBlockData cbd = new CustomBlockData(block, ARC.plugin);
        cbd.set(BEE_KEY, PersistentDataType.BOOLEAN, true);
    }

    private static final Config beeConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    private void processBees(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.BEE_NEST && block.getType() != Material.BEEHIVE) return;
        if (CustomBlockData.hasCustomBlockData(block, ARC.plugin)) return;
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
            CustomBlockData cbd = new CustomBlockData(block, ARC.plugin);
            cbd.set(BEE_KEY, PersistentDataType.BOOLEAN, true);
        }
    }

    private void processPlaceForLeaves(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        if (event.getPlayer().hasPermission("arc.leafdecay.bypass")) return;
        LeafDecayManager.markAsPlayerPlaced(event.getBlock());
    }

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
            if (site != null && site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE) {
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

        TreasureHunt treasureHunt = null;
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
