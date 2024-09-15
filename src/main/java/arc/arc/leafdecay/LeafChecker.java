package arc.arc.leafdecay;

import arc.arc.ARC;
import arc.arc.configs.Config;
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

    public Collection<LeafData> checkChunk(Chunk chunk) {
        Set<Block> blocksWithCustomData = CustomBlockData.getBlocksWithCustomData(ARC.plugin, chunk);
        //log.info("Found {} blocks with custom data in chunk {}", blocksWithCustomData.size(), chunk);
        Map<Block, LeafData> leafData = new HashMap<>();
        Set<Block> blobVisited = new HashSet<>();
        for (int y = scanHeight; y <= maxHeight; y++) {
            if (chunk.getWorld().getMaxHeight() < y) {
                log.warn("World height {} is less than the scan height {}. Stopping scan.", chunk.getWorld().getMaxHeight(), y);
                break;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (leafData.containsKey(block)) continue;
                    if (!leafMaterials.contains(block.getType()) && !trunkMaterials.contains(block.getType())) continue;
                    if (removeFloatingBlobs && !blobVisited.contains(block)) {
                        findFloatingBlobs(block, blocksWithCustomData, maxBlobSize, maxTrunkBlocks, false, blobVisited)
                                .forEach(b -> leafData.put(b, new LeafData(b, ThreadLocalRandom.current().nextInt(0, 1000))));
                    }
                    if (leafData.containsKey(block)) continue;
                    if (shouldDecay(block, blocksWithCustomData)) {
                        leafData.put(block, new LeafData(block, ThreadLocalRandom.current().nextInt(0, 1000)));
                    }
                }
            }
        }
        //log.info("Found {} leaves in chunk {}", leafData.size(), chunk);
        return leafData.values();
    }

    public boolean shouldDecay(Block block, Set<Block> dataBlocks) {
        try {
            if (!leafMaterials.contains(block.getType())) return false;
            if (dataBlocks.contains(block)) return false;
            return isNotConnected(block);
        } catch (Exception e) {
            log.error("Error while checking leaf decay for block {}", block, e);
            return false;
        }
    }

    // find all floating blobs of leaves/trunk blocks which are small and not connected to the ground
    // if from this block we can build such a blobs, return it. overwise return empy collection
    public Collection<Block> findFloatingBlobs(Block origin, Set<Block> withPlayerData, int maxBlocks, int maxTrunkBlocks,
                                               boolean logs, Set<Block> visited) {
        Set<Block> floatingBlobs = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        int trunkBlocks = 0;
        while (!queue.isEmpty()) {
            Block current = queue.poll();
            //if (visited.contains(current)) continue;
            //visited.add(current);
            if (withPlayerData.contains(current)) {
                if (logs) log.debug("Block {} has player data", current);
                return Set.of();
            }
            if (!leafMaterials.contains(current.getType())) {
                if (logs) log.debug("Block {} is not a leaf", current);
                if (trunkMaterials.contains(current.getType())) {
                    trunkBlocks++;
                    if (logs) log.debug("Block {} is a trunk block", current);
                    if (logs) log.debug("Total trunk blocks: {}", trunkBlocks);
                    if (trunkBlocks > maxTrunkBlocks) return Set.of();
                } else return Set.of();
            }
            floatingBlobs.add(current);
            if (logs) log.debug("Total floating blobs: {}", floatingBlobs.size());
            if (floatingBlobs.size() > maxBlocks) return Set.of();
            for (Block neighbor : getNeighbors(current, diagonalScan, 1)) {
                if (visited.contains(neighbor)) continue;
                if (neighbor.isPassable()) continue;
                queue.add(neighbor);
            }
        }
        return floatingBlobs;
    }

    private boolean isNotConnected(Block block) {
        record BlockData(Block block, int distance) {
        }
        Deque<BlockData> queue = new ArrayDeque<>();
        Map<Block, Integer> visited = new HashMap<>();

        queue.add(new BlockData(block, 0));
        while (!queue.isEmpty()) {
            BlockData current = queue.poll();
            if (current.distance > leafDistance) {
                continue;
            }
            if (!leafMaterials.contains(current.block.getType()) && !current.block.isPassable()) {
                return false;
            }
            for (Block neighbor : getNeighbors(current.block, diagonalScan, 1)) {
                Integer distance = visited.get(neighbor);
                if (distance != null && distance <= current.distance + 1) continue;
                if (neighbor.isPassable()) continue;
                visited.put(neighbor, current.distance + 1);
                queue.add(new BlockData(neighbor, current.distance + 1));
            }
        }
        return true;
    }

    private List<Block> getNeighbors(Block block, boolean diagonal, int gap) {
        List<Block> neighbors = new ArrayList<>();
        for (int x = -gap; x <= gap; x++) {
            for (int y = -gap; y <= gap; y++) {
                for (int z = -gap; z <= gap; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (!diagonal && (x != 0 && y != 0 || x != 0 && z != 0 || y != 0 && z != 0)) continue;
                    neighbors.add(block.getRelative(x, y, z));
                }
            }
        }
        return neighbors;
    }

}

// yaml list of all leaves and fences
// leaf-materials:
//   - OAK_LEAVES
//   - SPRUCE_LEAVES
//   - BIRCH_LEAVES
//   - JUNGLE_LEAVES
//   - ACACIA_LEAVES
//   - DARK_OAK_LEAVES
//   - AZALEA_LEAVES
//   - FLOWERING_AZALEA_LEAVES
//   - OAK_FENCE
//   - SPRUCE_FENCE
//   - BIRCH_FENCE
//   - JUNGLE_FENCE
//   - ACACIA_FENCE
//   - DARK_OAK_FENCE
//   - CRIMSON_FENCE
//   - WARPED_FENCE
//   - IRON_BARS
//   - NETHER_BRICK_FENCE

