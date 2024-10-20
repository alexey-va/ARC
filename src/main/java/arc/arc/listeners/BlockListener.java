package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.autobuild.BuildingManager;
import arc.arc.autobuild.ConstructionSite;
import arc.arc.bschests.PersonalLootManager;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.farm.FarmManager;
import arc.arc.leafdecay.LeafDecayManager;
import arc.arc.treasurechests.TreasureHunt;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.util.TextUtil;
import com.jeff_media.customblockdata.CustomBlockData;
import com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

@Slf4j
public class BlockListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        processLocationPool(event);
        processPlaceForLeaves(event);
        processPlaceBees(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreakLow(BlockBreakEvent event) {
        processFarmBreak(event);
        PersonalLootManager.processChestBreak(event);
    }

    @EventHandler
    public void onBlockBreakHigh(BlockBreakEvent event) {
        //processBreakForLeaves(event);

        CustomBlockData data = new CustomBlockData(event.getBlock(), ARC.plugin);
        data.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        processTreasureHunt(event);
        processBuildingEvent(event);
        processBees(event);
    }


    @EventHandler(priority = EventPriority.LOW)
    public void onChestClick(InventoryOpenEvent event) {
        //if (HookRegistry.bsHook == null) return;
        //log.info("Chest open event");
        PersonalLootManager.processChestOpen(event);
    }

    private static final NamespacedKey BEE_KEY = new NamespacedKey(ARC.plugin, "bee");

    private void processPlaceBees(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BEE_NEST && block.getType() != Material.BEEHIVE) return;
        CustomBlockData cbd = new CustomBlockData(block, ARC.plugin);
        cbd.set(BEE_KEY, PersistentDataType.BOOLEAN, true);
    }

    private static Config beeConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

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
            ConstructionSite site = BuildingManager.getConstruction(event.getPlayer().getUniqueId());
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
            //System.out.println("Target is null");
            return;
        }
        TreasureHunt treasureHunt = TreasureHuntManager.getByBlock(block);
        if (treasureHunt == null) {
            //System.out.println("Treasure hunt is null");
            return;
        }
        //System.out.println(block.getLocation());
        event.setCancelled(true);
        TreasureHuntManager.popChest(block, treasureHunt, event.getPlayer());
    }

    private void processLocationPool(BlockPlaceEvent event) {
        boolean add = event.getBlockPlaced().getType() == Material.GOLD_BLOCK;
        boolean remove = event.getBlockPlaced().getType() == Material.REDSTONE_BLOCK;
        if (!add && !remove) return;

        String poolId = LocationPoolManager.getEditing(event.getPlayer().getUniqueId());
        if (poolId == null) return;

        event.setCancelled(true);
        if (add) {
            LocationPoolManager.addLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            event.getPlayer().sendMessage("Block added!");
        } else {
            boolean res = LocationPoolManager.removeLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            if (res) event.getPlayer().sendMessage("Block removed!");
            else event.getPlayer().sendMessage("Not in pool!");
        }

    }

}
