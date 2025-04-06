package ru.arc.leafdecay;

import org.bukkit.Location;
import ru.arc.ARC;
import ru.arc.configs.Config;
import com.jeff_media.customblockdata.CustomBlockData;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@ToString
public class LeafChecker {

    final Config config;
    int scanHeight;
    int maxHeight;
    int leafDistance;
    Set<Material> leafMaterials;
    Set<Material> trunkMaterials;
    boolean diagonalScan;
    boolean removeFloatingBlobs;
    int maxBlobSize = 100;
    int maxTrunkBlocks = 100;

    public LeafChecker(Config config, Set<Material> leafMaterials, Set<Material> trunkMaterials) {
        this.config = config;
        this.leafMaterials = leafMaterials;
        this.trunkMaterials = trunkMaterials;
        loadConfig();
        log.info("LeafChecker loaded: {}", this);
    }

    public void loadConfig() {
        scanHeight = config.integer("scan-min-height", 0);
        maxHeight = config.integer("scan-max-height", 256);
        leafDistance = config.integer("leaf-distance", 8);
        diagonalScan = config.bool("diagonal-scan", false);
        removeFloatingBlobs = config.bool("remove-floating-blobs", true);
        maxBlobSize = config.integer("max-blob-size", 20);
        maxTrunkBlocks = config.integer("max-trunk-blocks", 3);
    }

    public Collection<Location> checkChunk(Chunk chunk) {
        Set<Block> blocksWithCustomData2 = CustomBlockData.getBlocksWithCustomData(ARC.plugin, chunk);
        Set<Location> blocksWithCustomData = new HashSet<>();
        blocksWithCustomData2.forEach(b -> blocksWithCustomData.add(b.getLocation()));
        Set<Location> leafData = new HashSet<>();
        Set<Location> blobVisited = new HashSet<>();
        for (int y = scanHeight; y <= maxHeight; y++) {
            if (chunk.getWorld().getMaxHeight() < y) {
                log.warn("World height {} is less than the scan height {}. Stopping scan.", chunk.getWorld().getMaxHeight(), y);
                break;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    Location location = block.getLocation();
                    if (!leafMaterials.contains(block.getType()) && !trunkMaterials.contains(block.getType())) continue;
                    double v = ThreadLocalRandom.current().nextDouble();
                    double chance = config.real("decay-chance", 0.01);
                    if (v > chance) continue;
                    if (leafData.contains(block.getLocation())) continue;
                    if (removeFloatingBlobs && !blobVisited.contains(location)) {
                        findFloatingBlobs(location, blocksWithCustomData, maxBlobSize, maxTrunkBlocks, blobVisited, false)
                                .forEach(b -> leafData.add(location));
                        if (leafData.contains(location)) continue;
                    }
                    if (shouldDecay(location, blocksWithCustomData)) {
                        leafData.add(location);
                    }
                    if (leafData.size() > 100) {
                        log.warn("Too many leaf blocks in chunk {}. Stopping scan.", chunk);
                        return leafData;
                    }
                }
            }
        }
        //log.info("Found {} leaves in chunk {}", leafData.size(), chunk);
        return leafData;
    }

    public boolean shouldDecay(Location location, Set<Location> dataBlocks) {
        try {
            Block block = location.getBlock();
            if (!leafMaterials.contains(block.getType())) return false;
            if (dataBlocks.contains(location)) return false;
            return isNotConnected(location);
        } catch (Exception e) {
            log.error("Error while checking leaf decay for block {}", location, e);
            return false;
        }
    }

    public Collection<Location> findFloatingBlobs(Location origin, Set<Location> withPlayerData, int maxBlocks, int maxTrunkBlocks,
                                                  Set<Location> visited, boolean isLog) {
        Set<Location> visitedLocal = new HashSet<>();
        Set<Location> floatingBlobs = new HashSet<>();
        Deque<Location> queue = new ArrayDeque<>();
        queue.add(origin.toBlockLocation());
        int trunkBlocks = 0;
        while (!queue.isEmpty()) {
            if (isLog) log.info("----");
            if (isLog) log.info("Floating blobs: {}", floatingBlobs);
            Location current = queue.poll();
            if (visitedLocal.contains(current)) {
                if (isLog) log.info("A Block {} already visited", current);
                continue;
            }
            if (withPlayerData.contains(current)) return Set.of();
            Block block = current.getBlock();
            if (isLog) log.info("Checking block {} {}", current, block.getType());
            if (!leafMaterials.contains(block.getType())) {
                if (isLog) log.info("Block {} is not leaf", block.getType());
                if (trunkMaterials.contains(block.getType())) {
                    trunkBlocks++;
                    if (isLog) log.info("Block {} is trunk {}", block.getType(), trunkBlocks);
                    if (trunkBlocks > maxTrunkBlocks) return Set.of();
                } else {
                    if (isLog) log.info("Block {} is not trunk", block.getType());
                    return Set.of();
                }
            }
            floatingBlobs.add(current);
            if (floatingBlobs.size() > maxBlocks) {
                if (isLog) log.info("Too many blocks in floating blob");
                return Set.of();
            }
            for (Location neighbor : getNeighbors(current, diagonalScan, 1)) {
                Block neighborBlock = neighbor.getBlock();
                if (floatingBlobs.contains(neighbor)) {
                    if (isLog) log.info("Block {} already in floating blob", neighbor);
                    continue;
                }
                if (visited.contains(neighbor)) {
                    if (isLog) log.info("Kill Block {} already visited", neighbor);
                    return Set.of();
                }
                if (neighborBlock.isPassable()) {
                    if (isLog) log.info("Block {} is passable", neighbor);
                    continue;
                }
                if (isLog) log.info("Adding block {} to queue", neighbor);
                queue.add(neighbor);
                visited.add(current);
                visitedLocal.add(current);
            }
        }
        if (isLog) log.info("Stats: {} {} {} {}", floatingBlobs.size(), trunkBlocks, visitedLocal.size(), visited);
        return floatingBlobs;
    }

    private boolean isNotConnected(Location block) {
        record BlockData(Location block, int distance) {
        }
        Deque<BlockData> queue = new ArrayDeque<>();
        Map<Location, Integer> visited = new HashMap<>();
        queue.add(new BlockData(block, 0));
        while (!queue.isEmpty()) {
            BlockData current = queue.poll();
            Block currentBlock = current.block.getBlock();
            if (current.distance > leafDistance) continue;
            if (!leafMaterials.contains(currentBlock.getType()) && !currentBlock.isPassable()) return false;
            for (Location neighbor : getNeighbors(current.block, diagonalScan, 1)) {
                Block neighborBlock = neighbor.getBlock();
                if (neighborBlock.isPassable()) continue;
                Integer distance = visited.get(neighbor);
                if (distance != null) {
                    if (distance <= current.distance + 1) {
                        visited.put(neighbor, current.distance + 1);
                        continue;
                    }
                }
                visited.put(neighbor, current.distance + 1);
                queue.add(new BlockData(neighbor, current.distance + 1));
            }
        }
        return true;
    }

    private List<Location> getNeighbors(Location block, boolean diagonal, int gap) {
        List<Location> neighbors = new ArrayList<>();
        for (int x = -gap; x <= gap; x++) {
            for (int y = -gap; y <= gap; y++) {
                for (int z = -gap; z <= gap; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (!diagonal && (x != 0 && y != 0 || x != 0 && z != 0 || y != 0 && z != 0)) continue;
                    Block b = block.getBlock();
                    Location location = b.getRelative(x, y, z).getLocation();
                    neighbors.add(location);
                }
            }
        }

        return neighbors;
    }

}
