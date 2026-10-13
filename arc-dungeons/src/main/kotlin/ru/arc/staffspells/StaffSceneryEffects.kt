package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.display.PacketBlockDisplay
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import java.util.logging.Level

private const val STAFF_SCENERY_GRAVITY_PER_TICK = .018

/**
 * Client-only debris and temporary fake-air packets for bounded scenery impacts.
 * All Bukkit reads and packet sends stay on the server thread; no block, drop or
 * native entity is changed or created. [PaperPacketDisplays] is owned by this
 * effect and is closed with it.
 */
internal class StaffSceneryEffects(
    private val displays: PaperPacketDisplays,
    allowedWorlds: Set<String>,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private data class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int)
    private data class ChunkKey(val x: Int, val z: Int)
    private data class SceneryBlock(val key: BlockKey, val data: BlockData)
    private data class Debris(
        val display: PacketBlockDisplay,
        val origin: Location,
        val angle: Double,
        val speed: Double,
        val lift: Double,
    )
    private data class Scene(
        val casterId: UUID,
        val world: World,
        val worldId: UUID,
        val center: Location,
        val viewers: List<Player>,
        val blocks: List<SceneryBlock>,
        val debris: MutableList<Debris>,
        val displayViewers: MutableSet<UUID>,
        var age: Int = 0,
        var restoring: Boolean = false,
        var restoreRetries: Int = 0,
        var failureLogged: Boolean = false,
    )

    private val allowed = allowedWorlds.toSet()
    private val scenes = mutableListOf<Scene>()
    private val maskedBlocks = mutableSetOf<BlockKey>()
    private var closed = false

    init {
        if (allowed.isNotEmpty()) tasks.runTimer(1L, 1L, ::tick)
    }

    fun impact(casterId: UUID, at: Location, radius: Double) {
        check(Bukkit.isPrimaryThread())
        if (closed || allowed.isEmpty()) return
        val world = at.world ?: return
        if ("*" !in allowed && world.name !in allowed) return
        if (scenes.size >= MAX_SCENES || scenes.count { it.casterId == casterId } >= MAX_SCENES_PER_CASTER) return
        val safeRadius = radius.takeIf(Double::isFinite)?.coerceIn(0.0, MAX_RADIUS) ?: return
        val center = at.clone()
        val nearby = world.getNearbyPlayers(center, VIEW_RANGE)
            .asSequence()
            .filter { it.isOnline && it.world.uid == world.uid && it.location.distanceSquared(center) <= VIEW_RANGE * VIEW_RANGE }
            .sortedWith(compareBy<Player>({ it.location.distanceSquared(center) }, { it.uniqueId.toString() }))
            .toList()
        val blocks = selectBlocks(world, center, safeRadius)
        if (blocks.isEmpty()) return
        val receivedChunks = blocks.asSequence()
            .map { ChunkKey(it.key.x shr 4, it.key.z shr 4) }
            .distinct()
            .associateWith { chunk ->
                world.getPlayersSeeingChunk(chunk.x, chunk.z).mapTo(hashSetOf()) { it.uniqueId }
            }
        val viewers = nearby.asSequence()
            .filter { viewer -> receivedChunks.values.all { viewer.uniqueId in it } }
            .take(MAX_VIEWERS)
            .toList()
        if (viewers.isEmpty()) return

        val debris = mutableListOf<Debris>()
        try {
            blocks.forEachIndexed { index, block ->
                val origin = Location(world, block.key.x.toDouble(), block.key.y.toDouble(), block.key.z.toDouble())
                val display = displays.spawnBlock(origin, block.data.clone()).apply {
                    isVisibleByDefault = false
                    viewRange = (VIEW_RANGE / 64.0).toFloat()
                    teleportDuration = DEBRIS_FRAME_TICKS
                    viewers.forEach(::showTo)
                }
                val dx = block.key.x + .5 - center.x
                val dz = block.key.z + .5 - center.z
                val angle = if (dx * dx + dz * dz < .01) index * GOLDEN_ANGLE else atan2(dz, dx)
                debris += Debris(display, origin, angle, .10 + index % 4 * .02, .16 + index % 3 * .025)
            }
        } catch (failure: RuntimeException) {
            debris.forEach { it.display.remove() }
            warn(casterId, center, "display creation", failure)
            return
        }

        val scene = Scene(casterId, world, world.uid, center, viewers, blocks, debris, viewers.mapTo(mutableSetOf()) { it.uniqueId })
        scenes += scene
        maskedBlocks += blocks.map { it.key }
        try {
            sendBlocks(scene, blocks.associateWith { Bukkit.createBlockData(Material.AIR) })
        } catch (failure: RuntimeException) {
            finish(scene)
            warn(scene, "mask send", failure)
        }
    }

    fun cancel(casterId: UUID) {
        check(Bukkit.isPrimaryThread())
        if (closed) return
        scenes.filter { it.casterId == casterId }.toList().forEach(::finish)
    }

    private fun tick() {
        if (closed) return
        scenes.toList().forEach { scene ->
            if (scene.restoring) {
                finish(scene)
                return@forEach
            }
            pruneDisplayAudience(scene)
            scene.age++
            if (scene.age < DEBRIS_LIFETIME && scene.age % DEBRIS_FRAME_TICKS == 0) {
                scene.debris.forEach { debris ->
                    val offset = staffSceneryBallisticOffset(scene.age, debris.angle, debris.speed, debris.lift)
                    try {
                        debris.display.teleport(debris.origin.clone().add(offset))
                    } catch (failure: RuntimeException) {
                        debris.display.remove()
                        warn(scene, "debris update", failure)
                    }
                }
            }
            if (scene.age >= DEBRIS_LIFETIME) removeDebris(scene)
            if (scene.age >= BLOCK_MASK_LIFETIME) finish(scene)
        }
    }

    private fun pruneDisplayAudience(scene: Scene) {
        scene.viewers.asSequence()
            .filter { it.uniqueId in scene.displayViewers && (!it.isOnline || it.world.uid != scene.worldId) }
            .forEach { viewer ->
                scene.debris.forEach { debris ->
                    try {
                        debris.display.hideFrom(viewer)
                    } catch (failure: RuntimeException) {
                        warn(scene, "display audience update", failure)
                    }
                }
                scene.displayViewers.remove(viewer.uniqueId)
            }
    }

    private fun selectBlocks(world: World, center: Location, radius: Double): List<SceneryBlock> {
        val radiusSquared = radius * radius
        val minX = floor(center.x - radius).toInt()
        val maxX = floor(center.x + radius).toInt()
        val minY = maxOf(world.minHeight, floor(center.y - radius).toInt())
        val maxY = minOf(world.maxHeight - 1, floor(center.y + radius).toInt())
        val minZ = floor(center.z - radius).toInt()
        val maxZ = floor(center.z + radius).toInt()
        val candidates = mutableListOf<SceneryBlock>()

        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val dx = x + .5 - center.x
            val dy = y + .5 - center.y
            val dz = z + .5 - center.z
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue
            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
            val block = world.getBlockAt(x, y, z)
            if (!block.type.isSolid || block.isPassable || block.isLiquid || block.state is TileState) continue
            val key = BlockKey(world.uid, x, y, z)
            if (key in maskedBlocks || !exposed(world, x, y, z)) continue
            candidates += SceneryBlock(key, block.blockData.clone())
        }
        return candidates.sortedWith(compareBy<SceneryBlock>(
            { distanceSquared(center, it.key) }, { it.key.x }, { it.key.y }, { it.key.z },
        )).take(MAX_BLOCKS_PER_SCENE)
    }

    private fun exposed(world: World, x: Int, y: Int, z: Int): Boolean = NEIGHBORS.any { (dx, dy, dz) ->
        val nx = x + dx
        val ny = y + dy
        val nz = z + dz
        if (ny !in world.minHeight until world.maxHeight || !world.isChunkLoaded(nx shr 4, nz shr 4)) return@any false
        val neighbor = world.getBlockAt(nx, ny, nz)
        (neighbor.type.isAir || neighbor.isPassable) && !neighbor.isLiquid
    }

    @Suppress("DEPRECATION")
    private fun sendBlocks(scene: Scene, states: Map<SceneryBlock, BlockData>): Boolean {
        var sent = true
        states.entries.groupBy { ChunkKey(it.key.key.x shr 4, it.key.key.z shr 4) }.forEach { (chunk, entries) ->
            if (!scene.world.isChunkLoaded(chunk.x, chunk.z)) return@forEach
            val sentTo = scene.world.getPlayersSeeingChunk(chunk.x, chunk.z).mapTo(hashSetOf()) { it.uniqueId }
            if (sentTo.isEmpty()) return@forEach
            val changes = entries.associate { (block, data) ->
                Location(scene.world, block.key.x.toDouble(), block.key.y.toDouble(), block.key.z.toDouble()) to data.clone()
            }
            scene.viewers.filter { it.uniqueId in sentTo && it.isOnline && it.world.uid == scene.worldId }
                .forEach { player ->
                    try {
                        player.sendMultiBlockChange(changes)
                    } catch (failure: RuntimeException) {
                        var fallbackSent = true
                        changes.forEach { (location, data) ->
                            try {
                                player.sendBlockChange(location, data)
                            } catch (fallbackFailure: RuntimeException) {
                                failure.addSuppressed(fallbackFailure)
                                fallbackSent = false
                            }
                        }
                        if (!fallbackSent) sent = false
                        warn(scene, "block packet send", failure)
                    }
                }
        }
        return sent
    }

    private fun finish(scene: Scene) {
        if (scene !in scenes) return
        if (!scene.restoring) {
            scene.restoring = true
            removeDebris(scene)
        }
        val otherMasks = scenes.asSequence()
            .filter { it !== scene }
            .flatMap { it.blocks.asSequence() }
            .mapTo(hashSetOf()) { it.key }
        val restored = try {
            val current = scene.blocks.mapNotNull { block ->
                val key = block.key
                if (key in otherMasks || !scene.world.isChunkLoaded(key.x shr 4, key.z shr 4)) return@mapNotNull null
                val live = scene.world.getBlockAt(key.x, key.y, key.z)
                block to live.blockData.clone()
            }.toMap()
            current.isEmpty() || sendBlocks(scene, current)
        } catch (failure: RuntimeException) {
            warn(scene, "block restore", failure)
            false
        }
        if (!restored) {
            scene.restoreRetries++
            if (scene.restoreRetries <= MAX_RESTORE_RETRIES) return
        }
        release(scene)
    }

    private fun release(scene: Scene) {
        scenes.remove(scene)
        maskedBlocks.removeAll(scene.blocks.map { it.key }.toSet())
    }

    private fun removeDebris(scene: Scene) {
        scene.debris.forEach { it.display.remove() }
        scene.debris.clear()
    }

    override fun close() {
        check(Bukkit.isPrimaryThread())
        if (closed) return
        closed = true
        try {
            tasks.close()
        } finally {
            try {
                scenes.toList().forEach { scene ->
                    repeat(MAX_RESTORE_RETRIES + 1) {
                        if (scene in scenes) finish(scene)
                    }
                }
            } finally {
                displays.close()
            }
        }
    }

    private fun warn(scene: Scene, operation: String, failure: RuntimeException) {
        if (scene.failureLogged) return
        scene.failureLogged = true
        warn(scene.casterId, scene.center, operation, failure)
    }

    private fun warn(casterId: UUID, center: Location, operation: String, failure: RuntimeException) {
        Bukkit.getLogger().log(Level.WARNING,
            "Staff scenery $operation failed for caster=$casterId at ${center.world?.name}:${center.blockX},${center.blockY},${center.blockZ}; visual effect was bounded or skipped",
            failure)
    }

    private fun distanceSquared(center: Location, key: BlockKey): Double {
        val dx = key.x + .5 - center.x
        val dy = key.y + .5 - center.y
        val dz = key.z + .5 - center.z
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        private const val MAX_RADIUS = 3.0
        private const val MAX_BLOCKS_PER_SCENE = 12
        private const val MAX_VIEWERS = 16
        private const val MAX_SCENES_PER_CASTER = 2
        private const val MAX_SCENES = 8
        private const val VIEW_RANGE = 32.0
        private const val DEBRIS_FRAME_TICKS = 4
        private const val DEBRIS_LIFETIME = 20
        private const val BLOCK_MASK_LIFETIME = 40
        private const val MAX_RESTORE_RETRIES = 3
        private const val GOLDEN_ANGLE = 2.399963229728653
        private val NEIGHBORS = listOf(
            Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0),
            Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
        )
    }
}

/** Pure deterministic ballistic offset in blocks, with [ageTicks] measured at 20 TPS. */
internal fun staffSceneryBallisticOffset(
    ageTicks: Int,
    angle: Double,
    horizontalSpeed: Double,
    initialVelocity: Double,
): Vector {
    val seconds = ageTicks.coerceAtLeast(0).toDouble()
    return Vector(
        cos(angle) * horizontalSpeed * seconds,
        initialVelocity * seconds - .5 * STAFF_SCENERY_GRAVITY_PER_TICK * seconds * seconds,
        sin(angle) * horizontalSpeed * seconds,
    )
}
