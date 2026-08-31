package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

internal data class OriginChunkKey(
    val x: Int,
    val z: Int,
)

private data class OriginChunkLeaseKey(
    val worldId: UUID,
    val chunk: OriginChunkKey,
)

internal object OriginChunkPlanner {
    fun plan(region: OriginChunkRegion): List<OriginChunkKey> {
        val centerX = (region.minX + region.maxX) / 2.0
        val centerZ = (region.minZ + region.maxZ) / 2.0
        return buildList {
            for (x in region.minX..region.maxX) {
                for (z in region.minZ..region.maxZ) add(OriginChunkKey(x, z))
            }
        }.sortedWith(
            compareBy<OriginChunkKey>(
                { squaredDistance(it, centerX, centerZ) },
                { it.x },
                { it.z },
            ),
        )
    }

    private fun squaredDistance(key: OriginChunkKey, centerX: Double, centerZ: Double): Double =
        (key.x - centerX) * (key.x - centerX) + (key.z - centerZ) * (key.z - centerZ)
}

/** Loads the configured Origin region gradually and keeps one shared ARC lease per chunk. */
internal class OriginSpawnChunkManager(
    private val ticketRegistry: PaperChunkTicketRegistry,
) {
    private val leases = linkedMapOf<OriginChunkLeaseKey, PaperChunkTicketLease>()
    private val inFlight = linkedSetOf<OriginChunkKey>()
    private val queue = ArrayDeque<OriginChunkKey>()
    private val failedAttempts = mutableMapOf<OriginChunkKey, Int>()

    private var desired: Set<OriginChunkKey> = emptySet()
    private var worldName: String = ""
    private var maxInFlight = 8
    private var stopped = false

    fun apply(config: OriginSpawnConfig) {
        stopped = false
        worldName = config.worldName
        maxInFlight = config.chunkRegion.maxInFlight
        desired = if (config.enabled) OriginChunkPlanner.plan(config.chunkRegion).toSet() else emptySet()
        val desiredWorldId = world()?.uid

        queue.clear()
        if (desired.isNotEmpty()) {
            OriginChunkPlanner.plan(config.chunkRegion)
                .filter { key ->
                    key in desired &&
                        leases.keys.none { it.worldId == desiredWorldId && it.chunk == key } &&
                        key !in inFlight
                }
                .forEach(queue::addLast)
        }
        pump()
        releaseObsoleteWhenSettled()

        if (config.enabled) {
            info(
                "Origin spawn chunk plan: world={}, requested={}, resident={}, loading={}",
                worldName,
                desired.size,
                leases.size,
                inFlight.size,
            )
        }
    }

    fun shutdown() {
        stopped = true
        desired = emptySet()
        queue.clear()
        inFlight.clear()
        failedAttempts.clear()
        leases.values.forEach(PaperChunkTicketLease::close)
        leases.clear()
    }

    private fun pump() {
        if (stopped || desired.isEmpty()) return
        val world = world()
        if (world == null) {
            warn("Origin spawn chunk region is enabled, but world '{}' is unavailable", worldName)
            queue.clear()
            return
        }
        while (inFlight.size < maxInFlight && queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val alreadyLeased = leases.keys.any { it.worldId == world.uid && it.chunk == key }
            if (key !in desired || alreadyLeased || !inFlight.add(key)) continue
            load(world, key)
        }
    }

    private fun load(world: World, key: OriginChunkKey) {
        world.getChunkAtAsync(key.x, key.z, false).whenComplete { chunk, failure ->
            inFlight.remove(key)
            if (failure != null || chunk == null) {
                warn("Failed to load Origin spawn chunk {}:{},{}", world.name, key.x, key.z, failure)
                val attempts = failedAttempts.merge(key, 1, Int::plus) ?: 1
                if (!stopped && key in desired && attempts < MAX_LOAD_ATTEMPTS) queue.addLast(key)
            } else if (!stopped && key in desired && world.name.equals(worldName, ignoreCase = true)) {
                failedAttempts.remove(key)
                acquire(OriginChunkLeaseKey(world.uid, key), chunk)
            } else if (!stopped && key in desired) {
                queue.addLast(key)
            }
            pump()
            releaseObsoleteWhenSettled()
            if (!stopped && queue.isEmpty() && inFlight.isEmpty()) {
                info("Origin spawn chunks resident: world={}, pinned={}", worldName, leases.size)
                val missing = desired.size - leases.size
                if (missing > 0) warn("Origin spawn chunk plan finished with {} missing ticket(s)", missing)
            }
        }
    }

    private fun acquire(key: OriginChunkLeaseKey, chunk: Chunk) {
        when (val result = ticketRegistry.acquire(chunk)) {
            is PaperChunkTicketAcquireResult.Acquired -> leases.put(key, result.lease)?.close()
            is PaperChunkTicketAcquireResult.Failed ->
                warn(
                    "Failed to acquire Origin spawn chunk ticket {}:{},{}",
                    worldName,
                    key.chunk.x,
                    key.chunk.z,
                    result.failure,
                )
            PaperChunkTicketAcquireResult.RegistryClosed -> Unit
            PaperChunkTicketAcquireResult.WorldUnavailable ->
                warn("Cannot acquire Origin spawn chunk ticket because world '{}' is unavailable", worldName)
        }
    }

    private fun releaseObsoleteWhenSettled() {
        if (queue.isNotEmpty() || inFlight.isNotEmpty()) return
        val desiredWorldId = world()?.uid
        leases.keys
            .filter { it.worldId != desiredWorldId || it.chunk !in desired }
            .forEach { key -> leases.remove(key)?.close() }
    }

    private fun world(): World? =
        Bukkit.getWorld(worldName)
            ?: Bukkit.getWorlds().firstOrNull { it.name.lowercase(Locale.ROOT) == worldName.lowercase(Locale.ROOT) }

    private companion object {
        const val MAX_LOAD_ATTEMPTS = 3
    }
}
