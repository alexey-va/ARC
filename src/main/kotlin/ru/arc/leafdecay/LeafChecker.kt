package ru.arc.leafdecay

import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.ArrayDeque
import java.util.concurrent.ThreadLocalRandom

class LeafChecker(
    private val config: Config,
    val leafMaterials: Set<Material>,
    val trunkMaterials: Set<Material>
) {
    private var scanHeight = 0
    private var maxHeight = 256
    private var leafDistance = 8
    private var diagonalScan = false
    private var removeFloatingBlobs = true
    private var maxBlobSize = 100
    private var maxTrunkBlocks = 100

    init {
        loadConfig()
        info("LeafChecker loaded: scanHeight={}, maxHeight={}, leafDistance={}", scanHeight, maxHeight, leafDistance)
    }

    fun loadConfig() {
        scanHeight = config.integer("scan-min-height", 0)
        maxHeight = config.integer("scan-max-height", 256)
        leafDistance = config.integer("leaf-distance", 8)
        diagonalScan = config.bool("diagonal-scan", false)
        removeFloatingBlobs = config.bool("remove-floating-blobs", true)
        maxBlobSize = config.integer("max-blob-size", 20)
        maxTrunkBlocks = config.integer("max-trunk-blocks", 3)
    }

    fun checkChunk(chunk: Chunk): Collection<Location> {
        val blocksWithCustomData = CustomBlockData.getBlocksWithCustomData(ARC.instance, chunk)
            .mapTo(HashSet()) { it.location }
        val leafData = HashSet<Location>()
        val blobVisited = HashSet<Location>()
        val decayChance = config.real("decay-chance", 0.01)

        for (y in scanHeight..maxHeight) {
            if (chunk.world.maxHeight < y) {
                warn("World height {} is less than the scan height {}. Stopping scan.", chunk.world.maxHeight, y)
                break
            }
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    val block = chunk.getBlock(x, y, z)
                    val location = block.location
                    if (!leafMaterials.contains(block.type) && !trunkMaterials.contains(block.type)) continue
                    if (ThreadLocalRandom.current().nextDouble() > decayChance) continue
                    if (leafData.contains(location)) continue
                    if (removeFloatingBlobs && !blobVisited.contains(location)) {
                        findFloatingBlobs(location, blocksWithCustomData, maxBlobSize, maxTrunkBlocks, blobVisited, false)
                            .forEach { _ -> leafData.add(location) }
                        if (leafData.contains(location)) continue
                    }
                    if (shouldDecay(location, blocksWithCustomData)) {
                        leafData.add(location)
                    }
                    if (leafData.size > 100) {
                        warn("Too many leaf blocks in chunk {}. Stopping scan.", chunk)
                        return leafData
                    }
                }
            }
        }
        return leafData
    }

    fun shouldDecay(location: Location, dataBlocks: Set<Location>): Boolean {
        return try {
            val block = location.block
            if (!leafMaterials.contains(block.type)) return false
            if (dataBlocks.contains(location)) return false
            isNotConnected(location)
        } catch (e: Exception) {
            error("Error while checking leaf decay for block {}", location, e)
            false
        }
    }

    fun findFloatingBlobs(
        origin: Location,
        withPlayerData: Set<Location>,
        maxBlocks: Int,
        maxTrunkBlocksLimit: Int,
        visited: MutableSet<Location>,
        isLog: Boolean
    ): Collection<Location> {
        val visitedLocal = HashSet<Location>()
        val floatingBlobs = HashSet<Location>()
        val queue = ArrayDeque<Location>()
        queue.add(origin.toBlockLocation())
        var trunkCount = 0

        while (queue.isNotEmpty()) {
            if (isLog) info("----")
            if (isLog) info("Floating blobs: {}", floatingBlobs)
            val current = queue.poll()
            if (visitedLocal.contains(current)) {
                if (isLog) info("A Block {} already visited", current)
                continue
            }
            if (withPlayerData.contains(current)) return emptySet()
            val block = current.block
            if (isLog) info("Checking block {} {}", current, block.type)
            if (!leafMaterials.contains(block.type)) {
                if (isLog) info("Block {} is not leaf", block.type)
                if (trunkMaterials.contains(block.type)) {
                    trunkCount++
                    if (isLog) info("Block {} is trunk {}", block.type, trunkCount)
                    if (trunkCount > maxTrunkBlocksLimit) return emptySet()
                } else {
                    if (isLog) info("Block {} is not trunk", block.type)
                    return emptySet()
                }
            }
            floatingBlobs.add(current)
            if (floatingBlobs.size > maxBlocks) {
                if (isLog) info("Too many blocks in floating blob")
                return emptySet()
            }
            for (neighbor in getNeighbors(current, diagonalScan, 1)) {
                val neighborBlock = neighbor.block
                if (floatingBlobs.contains(neighbor)) {
                    if (isLog) info("Block {} already in floating blob", neighbor)
                    continue
                }
                if (visited.contains(neighbor)) {
                    if (isLog) info("Kill Block {} already visited", neighbor)
                    return emptySet()
                }
                if (neighborBlock.isPassable) {
                    if (isLog) info("Block {} is passable", neighbor)
                    continue
                }
                if (isLog) info("Adding block {} to queue", neighbor)
                queue.add(neighbor)
                visited.add(current)
                visitedLocal.add(current)
            }
        }
        if (isLog) info("Stats: {} {} {} {}", floatingBlobs.size, trunkCount, visitedLocal.size, visited)
        return floatingBlobs
    }

    private fun isNotConnected(block: Location): Boolean {
        data class BlockData(val block: Location, val distance: Int)

        val queue = ArrayDeque<BlockData>()
        val visited = HashMap<Location, Int>()
        queue.add(BlockData(block, 0))

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val currentBlock = current.block.block
            if (current.distance > leafDistance) continue
            if (!leafMaterials.contains(currentBlock.type) && !currentBlock.isPassable) return false
            for (neighbor in getNeighbors(current.block, diagonalScan, 1)) {
                val neighborBlock = neighbor.block
                if (neighborBlock.isPassable) continue
                val existingDistance = visited[neighbor]
                if (existingDistance != null && existingDistance <= current.distance + 1) {
                    visited[neighbor] = current.distance + 1
                    continue
                }
                visited[neighbor] = current.distance + 1
                queue.add(BlockData(neighbor, current.distance + 1))
            }
        }
        return true
    }

    private fun getNeighbors(block: Location, diagonal: Boolean, gap: Int): List<Location> {
        val neighbors = mutableListOf<Location>()
        val b = block.block
        for (x in -gap..gap) {
            for (y in -gap..gap) {
                for (z in -gap..gap) {
                    if (x == 0 && y == 0 && z == 0) continue
                    if (!diagonal && (x != 0 && y != 0 || x != 0 && z != 0 || y != 0 && z != 0)) continue
                    neighbors.add(b.getRelative(x, y, z).location)
                }
            }
        }
        return neighbors
    }
}
