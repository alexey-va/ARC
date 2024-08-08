package arc.arc.treasurechests;

import arc.arc.hooks.HookRegistry;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.generic.treasure.Treasure;
import arc.arc.generic.treasure.TreasurePool;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TreasureHuntManager {


    private static List<TreasureHunt> treasureHunts = new ArrayList<>();
    private static Map<Location, TreasureHunt> blockMap = new ConcurrentHashMap<>();

    public static Optional<TreasureHunt> getByLocationPool(LocationPool locationPool) {
        return treasureHunts.stream().filter(th -> th.locationPool == locationPool).findAny();
    }


    public static TreasureHunt getByBlock(Block block) {
        return blockMap.get(block.getLocation().toCenterLocation());
    }

    public static void startHunt(LocationPool locationPool, int chests, String namespaceId, String treasurePoolId) {
        getByLocationPool(locationPool)
                .ifPresent(TreasureHuntManager::stopHunt);
        TreasureHunt treasureHunt;

        TreasurePool treasurePool = TreasurePool.getTreasurePool(treasurePoolId);
        if (treasurePool == null) {
            System.out.println("Could not find treasure pool with id: " + treasurePoolId);
            return;
        }

        if (namespaceId.equals("vanilla"))
            treasureHunt = new TreasureHunt(locationPool, chests, namespaceId, TreasureHunt.Type.VANILLA, treasurePool);
        else {
            if (HookRegistry.itemsAdderHook == null) throw new IllegalArgumentException("ItemsAdder is not loaded!");
            treasureHunt = new TreasureHunt(locationPool, chests, namespaceId, TreasureHunt.Type.IA, treasurePool);
        }

        treasureHunt.generateLocations();
        treasureHunt.clearChests();
        treasureHunt.displayLocations();
        Set<Location> blocks = treasureHunt.start();

        treasureHunts.add(treasureHunt);
        blocks.forEach(loc -> blockMap.put(loc, treasureHunt));
    }

    public static void stopHunt(TreasureHunt treasureHunt) {
        treasureHunt.stop();
        blockMap.entrySet().removeIf(e -> e.getValue() == treasureHunt);
        treasureHunts.remove(treasureHunt);
    }

    public static void popChest(Block block, TreasureHunt treasureHunt, Player player) {
        TreasureHunt th = blockMap.remove(block.getLocation().toCenterLocation());
        if (th == null || treasureHunt != th) {
            System.out.println("SOMETHING WRONG! " + th + " " + treasureHunt);
            return;
        }

        treasureHunt.popChest(block, player);
    }

    public static void stopAll() {
        for (TreasureHunt treasureHunt : treasureHunts) {
            treasureHunt.stop();
        }
        treasureHunts.clear();
        blockMap.clear();
    }

    public static Collection<TreasurePool> getTreasurePools() {
        return TreasurePool.getTreasurePools();
    }

    public static void addTreasure(Treasure treasure, String treasurePoolId) {
        TreasurePool treasurePool = TreasurePool.getOrCreate(treasurePoolId);
        treasurePool.add(treasure);
    }
}
