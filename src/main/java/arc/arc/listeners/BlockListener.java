package arc.arc.listeners;

import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.treasurechests.TreasureHunt;
import arc.arc.treasurechests.TreasureHuntManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class BlockListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event){
        processLocationPool(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event){
        processTreasureHunt(event);
    }

    private void processTreasureHunt(PlayerInteractEvent event){
        Block block = event.getClickedBlock();
        if(block == null){
            //System.out.println("Target is null");
            return;
        }
        TreasureHunt treasureHunt = TreasureHuntManager.getByBlock(block);
        if(treasureHunt == null){
            //System.out.println("Treasure hunt is null");
            return;
        }
        //System.out.println(block.getLocation());
        event.setCancelled(true);
        TreasureHuntManager.popChest(block, treasureHunt, event.getPlayer());
    }

    private void processLocationPool(BlockPlaceEvent event){
        boolean add = event.getBlockPlaced().getType() == Material.GOLD_BLOCK;
        boolean remove = event.getBlockPlaced().getType() == Material.REDSTONE_BLOCK;
        if(!add && !remove) return;

        String poolId = LocationPoolManager.getEditing(event.getPlayer().getUniqueId());
        if(poolId == null) return;

        event.setCancelled(true);
        if(add) {
            LocationPoolManager.addLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            event.getPlayer().sendMessage("Block added!");
        } else{
            boolean res = LocationPoolManager.removeLocation(poolId, event.getBlock().getLocation().toCenterLocation());
            if(res) event.getPlayer().sendMessage("Block removed!");
            else event.getPlayer().sendMessage("Not in pool!");
        }

    }

}
