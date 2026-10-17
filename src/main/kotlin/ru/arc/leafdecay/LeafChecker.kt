package ru.arc.leafdecay

import com.jeff_media.customblockdata.CustomBlockData
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.util.Logging.info
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadLocalRandom

internal data class LeafPosition(val x: Int, val y: Int, val z: Int)
internal data class LeafCell(val material: Material, val passable: Boolean, val hasData: Boolean = false)

/** Advancing the iterator performs at most one native cell read; no traversal is run inline. */
internal class LeafWork<T>(private val steps: Iterator<Unit>, private val completed: () -> T) {
    fun step(): Boolean {
        if (!steps.hasNext()) return true
        steps.next()
        return false
    }

    fun result(): T = completed()
}

class LeafChecker(
    config: Config,
    val leafMaterials: Set<Material>,
    val trunkMaterials: Set<Material>,
) {
    private val scanHeight = config.integer("scan-min-height", 0)
    private val maxHeight = config.integer("scan-max-height", 256)
    private val leafDistance = config.integer("leaf-distance", 8)
    private val diagonalScan = config.bool("diagonal-scan", false)
    private val removeFloatingBlobs = config.bool("remove-floating-blobs", true)
    private val maxBlobSize = config.integer("max-blob-size", 20)
    private val maxTrunkBlocks = config.integer("max-trunk-blocks", 3)
    private val decayChance = config.real("decay-chance", 0.01)

    init {
        info("LeafChecker loaded: scanHeight={}, maxHeight={}, leafDistance={}", scanHeight, maxHeight, leafDistance)
    }

    internal fun beginChunk(chunk: Chunk): LeafWork<Sequence<Location>> {
        val world = chunk.world
        val scan = beginScan(chunk.x, chunk.z, maxOf(scanHeight, world.minHeight), minOf(maxHeight, world.maxHeight - 1)) {
            nativeCell(world, it, chunk.x, chunk.z)
        }
        return LeafWork(sequence {
            while (!scan.step()) yield(Unit)
        }.iterator()) { scan.result().asSequence().map { Location(world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } }
    }

    /** Separate from Bukkit so tests exercise the actual traversal's per-step read bound. */
    internal fun beginScan(
        chunkX: Int,
        chunkZ: Int,
        minY: Int,
        maxY: Int,
        random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
        read: (LeafPosition) -> LeafCell,
    ): LeafWork<Set<LeafPosition>> {
        var result: Set<LeafPosition>? = null
        val steps = sequence {
            val leafData = linkedSetOf<LeafPosition>()
            val blobVisited = hashSetOf<LeafPosition>()
            scan@ for (y in minY..maxY) for (x in 0..15) for (z in 0..15) {
                val position = LeafPosition((chunkX shl 4) + x, y, (chunkZ shl 4) + z)
                val cell = readCell(position, read)
                if (cell.material !in leafMaterials && cell.material !in trunkMaterials) continue
                if (random() > decayChance || position in leafData) continue
                if (removeFloatingBlobs && position !in blobVisited) {
                    // Preserve the existing policy: a discovered blob marks its sampled origin.
                    if (floatingBlob(position, read, maxBlobSize, maxTrunkBlocks, blobVisited).isNotEmpty()) {
                        leafData.add(position)
                        continue
                    }
                }
                if (cell.material in leafMaterials && !cell.hasData && notConnected(position, read)) leafData.add(position)
                if (leafData.size > 100) break@scan
            }
            result = java.util.Collections.unmodifiableSet(leafData)
        }.iterator()
        return LeafWork(steps) { checkNotNull(result) { "Leaf scan has not completed" } }
    }

    internal fun beginInspection(location: Location): LeafWork<Pair<Boolean, Int>> {
        val world = requireNotNull(location.world)
        val origin = LeafPosition(location.blockX, location.blockY, location.blockZ)
        // The debug command historically ignores player-data tags.
        val read: (LeafPosition) -> LeafCell = { nativeCell(world, it, null, null) }
        var result: Pair<Boolean, Int>? = null
        val steps = sequence {
            val cell = readCell(origin, read)
            val decay = cell.material in leafMaterials && notConnected(origin, read)
            val floating = floatingBlob(origin, read, 100, 10, hashSetOf())
            result = decay to floating.size
        }.iterator()
        return LeafWork(steps) { checkNotNull(result) { "Leaf inspection has not completed" } }
    }

    private fun nativeCell(world: World, position: LeafPosition, dataChunkX: Int?, dataChunkZ: Int?): LeafCell {
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
            throw CancellationException("Leaf scan reached an unloaded chunk")
        }
        if (position.y !in world.minHeight until world.maxHeight) return LeafCell(Material.AIR, true)
        val block = world.getBlockAt(position.x, position.y, position.z)
        val material = block.type
        val candidate = material in leafMaterials || material in trunkMaterials
        val hasData = candidate && dataChunkX == (position.x shr 4) && dataChunkZ == (position.z shr 4) &&
            CustomBlockData.hasCustomBlockData(block, ARC.instance)
        return LeafCell(material, block.isPassable, hasData)
    }

    private suspend fun SequenceScope<Unit>.readCell(position: LeafPosition, read: (LeafPosition) -> LeafCell): LeafCell {
        val cell = read(position)
        yield(Unit)
        return cell
    }

    private suspend fun SequenceScope<Unit>.floatingBlob(
        origin: LeafPosition,
        read: (LeafPosition) -> LeafCell,
        maximumBlocks: Int,
        maximumTrunks: Int,
        visited: MutableSet<LeafPosition>,
    ): Set<LeafPosition> {
        val local = hashSetOf<LeafPosition>()
        val floating = linkedSetOf<LeafPosition>()
        val queue = ArrayDeque<LeafPosition>()
        queue.add(origin)
        var trunks = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in local) { yield(Unit); continue }
            val cell = readCell(current, read)
            if (cell.hasData) return emptySet()
            if (cell.material !in leafMaterials) {
                if (cell.material !in trunkMaterials || ++trunks > maximumTrunks) return emptySet()
            }
            floating.add(current)
            if (floating.size > maximumBlocks) return emptySet()
            for (neighbor in neighbors(current)) {
                if (neighbor in floating) continue
                if (neighbor in visited) return emptySet()
                if (readCell(neighbor, read).passable) continue
                queue.add(neighbor)
                visited.add(current)
                local.add(current)
            }
        }
        return floating
    }

    private suspend fun SequenceScope<Unit>.notConnected(origin: LeafPosition, read: (LeafPosition) -> LeafCell): Boolean {
        val queue = ArrayDeque<Pair<LeafPosition, Int>>()
        val visited = hashMapOf<LeafPosition, Int>()
        queue.add(origin to 0)
        while (queue.isNotEmpty()) {
            val (current, distance) = queue.removeFirst()
            if (distance > leafDistance) { yield(Unit); continue }
            val cell = readCell(current, read)
            if (cell.material !in leafMaterials && !cell.passable) return false
            for (neighbor in neighbors(current)) {
                if (readCell(neighbor, read).passable) continue
                val previous = visited[neighbor]
                visited[neighbor] = distance + 1
                if (previous != null && previous <= distance + 1) continue
                queue.add(neighbor to distance + 1)
            }
        }
        return true
    }

    private fun neighbors(position: LeafPosition): Sequence<LeafPosition> = sequence {
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            if (x == 0 && y == 0 && z == 0) continue
            if (!diagonalScan && (x != 0 && y != 0 || x != 0 && z != 0 || y != 0 && z != 0)) continue
            yield(LeafPosition(position.x + x, position.y + y, position.z + z))
        }
    }
}
