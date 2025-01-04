package arc.arc.treasurechests;

import arc.arc.ARC;
import arc.arc.common.WeightedRandom;
import arc.arc.common.locationpools.LocationPool;
import arc.arc.common.locationpools.LocationPoolManager;
import arc.arc.common.treasure.TreasurePool;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.HookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
public class TreasureHuntManager {


    private static final Deque<TreasureHunt> treasureHunts = new ConcurrentLinkedDeque<>();
    private static final Map<Location, TreasureHunt> blockMap = new ConcurrentHashMap<>();
    private static final Map<String, TreasureHuntType> treasureHuntTypes = new ConcurrentHashMap<>();

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasure-hunt.yml");

    public static Optional<TreasureHunt> getByLocationPool(LocationPool locationPool) {
        return treasureHunts.stream().filter(th -> th.treasureHuntType.getLocationPool() == locationPool).findAny();
    }

    public static List<String> getTreasureHuntTypes() {
        return new ArrayList<>(treasureHuntTypes.keySet());
    }

    public static TreasureHuntType getTreasureHuntType(String id) {
        return treasureHuntTypes.get(id);
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

        if (namespaceId.equals("vanilla")) {
            WeightedRandom<ChestType> weightedRandom = new WeightedRandom<>();
            weightedRandom.add(ChestType.builder()
                    .particlePath("default")
                    .type(Type.VANILLA)
                    .treasurePoolId(treasurePoolId)
                    .build(), 1);
            treasureHunt = new TreasureHunt(chests, TreasureHuntType.builder()
                    .locationPoolId(locationPool.getId())
                    .entries(weightedRandom)
                    .build());
        } else {
            if (HookRegistry.itemsAdderHook == null) throw new IllegalArgumentException("ItemsAdder is not loaded!");
            WeightedRandom<ChestType> weightedRandom = new WeightedRandom<>();
            weightedRandom.add(ChestType.builder()
                    .particlePath("default")
                    .type(Type.IA)
                    .namespaceId(namespaceId)
                    .treasurePoolId(treasurePoolId)
                    .build(), 1);
            treasureHunt = new TreasureHunt(chests, TreasureHuntType.builder()
                    .locationPoolId(locationPool.getId())
                    .entries(weightedRandom)
                    .build());
        }

        treasureHunt.generateLocations();
        treasureHunt.clearChests();
        Set<Location> blocks = treasureHunt.start();
        treasureHunt.displayLocations();

        treasureHunts.add(treasureHunt);
        blocks.forEach(loc -> blockMap.put(loc, treasureHunt));
    }

    public static void startHunt(String type, int chests) {
        TreasureHuntType treasureHuntType = treasureHuntTypes.get(type);
        if (treasureHuntType == null) {
            System.out.println("Could not find treasure hunt type with id: " + type);
            return;
        }
        if (chests <= 0) {
            chests = treasureHuntType.getLocationPool().getLocations().size();
        }
        TreasureHunt treasureHunt = new TreasureHunt(chests, treasureHuntType);
        treasureHunt.generateLocations();
        treasureHunt.clearChests();
        Set<Location> blocks = treasureHunt.start();
        treasureHunt.displayLocations();

        treasureHunts.add(treasureHunt);
        blocks.forEach(loc -> blockMap.put(loc, treasureHunt));
    }

    public static void stopHunt(TreasureHunt treasureHunt) {
        treasureHunt.stop(false);
        removeHunt(treasureHunt);
    }

    public static void removeHunt(TreasureHunt treasureHunt) {
        blockMap.entrySet().removeIf(e -> e.getValue() == treasureHunt);
        treasureHunts.remove(treasureHunt);
    }

    public static void popChest(Block block, TreasureHunt treasureHunt, Player player) {
        TreasureHunt th = blockMap.remove(block.getLocation().toCenterLocation());
        if (th == null || treasureHunt != th) {
            player.sendMessage("This chest is not part of the current hunt!");
            return;
        }

        treasureHunt.popChest(block, player);
    }

    public static void stopAll() {
        for (TreasureHunt treasureHunt : treasureHunts) {
            treasureHunt.stop(false);
        }
        treasureHunts.clear();
        blockMap.clear();
    }

    public static Collection<TreasurePool> getTreasurePools() {
        return TreasurePool.getTreasurePools();
    }

    public static void loadTreasureHuntTypes() {
        treasureHuntTypes.clear();
        List<String> keys = config.keys("treasure-hunt-types");
        for (String key : keys) {
            try {
                String locationPoolId = config.string("treasure-hunt-types." + key + ".location-pool-id", null);
                if (locationPoolId == null) {
                    log.error("Missing location pool id for: {}", key);
                    continue;
                }
                List<String> chestTypeKeys = config.keys("treasure-hunt-types." + key + ".chest-types");
                WeightedRandom<ChestType> weightedRandom = new WeightedRandom<>();
                for (String chestTypeKey : chestTypeKeys) {
                    String type = config.string("treasure-hunt-types." + key + ".chest-types." + chestTypeKey + ".type", "VANILLA");
                    String namespaceId = config.string("treasure-hunt-types." + key + ".chest-types." + chestTypeKey + ".ia-namespace-id", null);
                    String treasurePoolId = config.string("treasure-hunt-types." + key + ".chest-types." + chestTypeKey + ".treasure-pool-id", null);
                    String particlePath = config.string("treasure-hunt-types." + key + ".chest-types." + chestTypeKey + ".particle-path", "default");

                    int weight = config.integer("treasure-hunt-types." + key + ".chest-types." + chestTypeKey + ".weight", 1);
                    if (treasurePoolId == null || type == null || particlePath == null) {
                        log.error("Missing required field for chest type: {}", chestTypeKey);
                        continue;
                    }
                    ChestType chestType = ChestType.builder()
                            .type(Type.valueOf(type))
                            .namespaceId(namespaceId)
                            .treasurePoolId(treasurePoolId)
                            .particlePath(particlePath)
                            .weight(weight)
                            .build();
                    weightedRandom.add(chestType, weight);
                }
                LocationPool locationPool = LocationPoolManager.getPool(locationPoolId);
                if (locationPool == null) {
                    log.warn("Could not find location pool with id: {}", locationPoolId);
                }
                String bossBarMessage = config.string("treasure-hunt-types." + key + ".boss-bar-message", "");
                boolean bossBarVisible = config.bool("treasure-hunt-types." + key + ".boss-bar-visible", true);
                String bossBarColor = config.string("treasure-hunt-types." + key + ".boss-bar-color", "WHITE");
                String bossBarOverlay = config.string("treasure-hunt-types." + key + ".boss-bar-overlay", "PROGRESS");
                long secondsTTL = config.longValue("treasure-hunt-types." + key + ".seconds-ttl", 60 * 60);
                boolean announceStop = config.bool("treasure-hunt-types." + key + ".announce-stop", true);
                String stopMessage = config.string("treasure-hunt-types." + key + ".stop-message", "");
                boolean announceStart = config.bool("treasure-hunt-types." + key + ".announce-start", true);
                String startMessage = config.string("treasure-hunt-types." + key + ".start-message", "");
                boolean announceStartGlobally = config.bool("treasure-hunt-types." + key + ".announce-start-globally", false);
                boolean launchFireworks = config.bool("treasure-hunt-types." + key + ".launch-fireworks", true);
                TreasureHuntType treasureHuntType = TreasureHuntType.builder()
                        .locationPoolId(locationPoolId)
                        .entries(weightedRandom)
                        .bossBarMessage(bossBarMessage)
                        .bossBarVisible(bossBarVisible)
                        .secondsTTL(secondsTTL)
                        .announceStop(announceStop)
                        .stopMessage(stopMessage)
                        .announceStart(announceStart)
                        .startMessage(startMessage)
                        .announceStartGlobally(announceStartGlobally)
                        .launchFireworks(launchFireworks)
                        .bossBarColor(bossBarColor)
                        .bossBarOverlay(bossBarOverlay)
                        .build();
                log.info("Loaded treasure hunt type: {}", key);
                treasureHuntTypes.put(key, treasureHuntType);
            } catch (Exception e) {
                log.error("Error loading treasure hunt type: {}", key, e);
            }
        }

    }
}
