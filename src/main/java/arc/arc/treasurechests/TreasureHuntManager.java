package arc.arc.treasurechests;

import arc.arc.treasurechests.locationpools.LocationPool;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TreasureHuntManager {


    private static List<TreasureHunt> treasureHunts = new ArrayList<>();
    private static Map<Location, TreasureHunt> blockMap = new ConcurrentHashMap<>();

    public static Optional<TreasureHunt> getByLocationPool(LocationPool locationPool){
        return treasureHunts.stream().filter(th->th.locationPool==locationPool).findAny();
    }


    public static TreasureHunt getByBlock(Block block){
        return blockMap.get(block.getLocation().toCenterLocation());
    }

    public static void startHunt(LocationPool locationPool, int chests, String namespaceId, String treasureHuntId){
        getByLocationPool(locationPool)
                .ifPresent(TreasureHuntManager::stopHunt);
        TreasureHunt treasureHunt;


        if(namespaceId.equals("vanilla")) treasureHunt = new TreasureHunt(locationPool, chests, namespaceId, TreasureHunt.Type.VANILLA, treasureHuntId);
        else treasureHunt = new TreasureHunt(locationPool, chests, namespaceId, TreasureHunt.Type.IA, treasureHuntId);

        treasureHunt.generateLocations();
        treasureHunt.clearChests();
        treasureHunt.displayLocations();
        Set<Location> blocks =  treasureHunt.start();

        treasureHunts.add(treasureHunt);
        blocks.forEach(loc -> blockMap.put(loc, treasureHunt));
    }

    public static void stopHunt(TreasureHunt treasureHunt){
        treasureHunt.stop();
        blockMap.entrySet().removeIf(e -> e.getValue() == treasureHunt);
        treasureHunts.remove(treasureHunt);
    }

    public static void popChest(Block block, TreasureHunt treasureHunt, Player player){
        TreasureHunt th = blockMap.remove(block.getLocation().toCenterLocation());
        if(th == null || treasureHunt != th){
            System.out.println("SOMETHING WRONG! "+th+" "+treasureHunt );
            return;
        }

        treasureHunt.popChest(block, player);
    }

    public static void stopAll() {
        for(TreasureHunt treasureHunt : treasureHunts){
            treasureHunt.stop();
        }
        treasureHunts.clear();
        blockMap.clear();
    }
}
