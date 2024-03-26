package arc.arc.listeners;

import arc.arc.autobuild.BuildingManager;
import arc.arc.autobuild.ConstructionSite;
import arc.arc.hooks.HookRegistry;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.treasurechests.TreasureHunt;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.util.TextUtil;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class BlockListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        processLocationPool(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        processFarmBreak(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        processTreasureHunt(event);
        processBuildingEvent(event);
    }


    private void processBuildingEvent(PlayerInteractEvent event) {
        if(!event.hasItem()) return;
        ItemStack hand = event.getItem();
        if(hand == null || hand.getType() != Material.BOOK) return;
        if(!event.getPlayer().hasPermission("arc.buildings.build")){
            event.getPlayer().sendMessage(TextUtil.noPermissions());
            return;
        }

        if(!event.hasBlock() || event.getAction() != Action.RIGHT_CLICK_BLOCK){
            ConstructionSite site = BuildingManager.getConstruction(event.getPlayer().getUniqueId());
            if(site != null && site.getState() == ConstructionSite.State.DISPLAYING_OUTLINE){
                BuildingManager.cancelConstruction(site);
                return;
            }
        }
        if(event.getClickedBlock() == null) return;
        Location center = event.getClickedBlock().getLocation().add(0,1,0);
        String buildingId = new NBTItem(hand).getString("arc:building_key");
        if(buildingId == null) return;
        BuildingManager.processPlayerClick(event.getPlayer(), center, buildingId);
    }


    private void processFarmBreak(BlockBreakEvent event) {
        if (HookRegistry.farmManager == null) return;
        HookRegistry.farmManager.processEvent(event);
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
