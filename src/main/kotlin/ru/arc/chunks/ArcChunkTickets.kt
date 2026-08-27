package ru.arc.chunks

import org.bukkit.Bukkit
import org.bukkit.Chunk
import ru.arc.ARC
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class ArcChunkKey(
    val worldId: UUID,
    val x: Int,
    val z: Int,
)

internal enum class ChunkTicketAddResult {
    ADDED,
    ALREADY_PRESENT,
    UNAVAILABLE,
}

internal interface ArcChunkTicketBackend {
    fun add(key: ArcChunkKey): ChunkTicketAddResult

    fun remove(key: ArcChunkKey): Boolean
}

/**
 * Reference-counts ARC's single Paper plugin ticket per chunk.
 *
 * Paper permits only one ticket for each plugin/chunk pair. Every ARC feature
 * must therefore acquire a lease here instead of adding or removing that
 * ticket directly. Calls are expected on the Paper main thread. A lease is
 * idempotent and a ticket that predated this registry is borrowed, never
 * removed by it.
 */
internal class ArcChunkTicketRegistry(
    private val backend: ArcChunkTicketBackend,
) {
    private data class TicketState(
        val owned: Boolean,
        var leaseCount: Int,
    )

    private val tickets = linkedMapOf<ArcChunkKey, TicketState>()

    fun acquire(key: ArcChunkKey): ArcChunkTicketLease? {
        tickets[key]?.let { current ->
            current.leaseCount++
            return lease(key)
        }

        val result = runCatching { backend.add(key) }
            .getOrElse { failure ->
                warn("Failed to acquire ARC chunk ticket {}:{},{}", key.worldId, key.x, key.z, failure)
                return null
            }
        if (result == ChunkTicketAddResult.UNAVAILABLE) {
            warn("Cannot acquire ARC chunk ticket because world {} is unavailable", key.worldId)
            return null
        }

        tickets[key] = TicketState(
            owned = result == ChunkTicketAddResult.ADDED,
            leaseCount = 1,
        )
        return lease(key)
    }

    fun shutdown() {
        tickets.forEach { (key, state) ->
            if (state.owned) removeOwnedTicket(key)
        }
        tickets.clear()
    }

    private fun lease(key: ArcChunkKey): ArcChunkTicketLease =
        ArcChunkTicketLease { release(key) }

    private fun release(key: ArcChunkKey) {
        val current = tickets[key] ?: return
        if (current.leaseCount <= 0) return
        current.leaseCount--
        if (current.leaseCount > 0) return

        if (!current.owned) {
            tickets.remove(key)
            return
        }

        val released = removeOwnedTicket(key)
        if (released != null) {
            // true means this registry removed the ticket; false means Paper
            // already had no ticket. Both outcomes are terminal.
            tickets.remove(key)
        }
    }

    private fun removeOwnedTicket(key: ArcChunkKey): Boolean? =
        runCatching { backend.remove(key) }
            .onFailure { failure ->
                warn("Failed to release ARC chunk ticket {}:{},{}", key.worldId, key.x, key.z, failure)
            }.getOrNull()
}

internal class ArcChunkTicketLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private object PaperArcChunkTicketBackend : ArcChunkTicketBackend {
    override fun add(key: ArcChunkKey): ChunkTicketAddResult {
        val world = Bukkit.getWorld(key.worldId) ?: return ChunkTicketAddResult.UNAVAILABLE
        return if (world.addPluginChunkTicket(key.x, key.z, ARC.instance)) {
            ChunkTicketAddResult.ADDED
        } else {
            ChunkTicketAddResult.ALREADY_PRESENT
        }
    }

    override fun remove(key: ArcChunkKey): Boolean {
        val world = Bukkit.getWorld(key.worldId) ?: return false
        return world.removePluginChunkTicket(key.x, key.z, ARC.instance)
    }
}

internal object ArcChunkTickets {
    private val registry = ArcChunkTicketRegistry(PaperArcChunkTicketBackend)

    fun acquire(chunk: Chunk): ArcChunkTicketLease? =
        registry.acquire(ArcChunkKey(chunk.world.uid, chunk.x, chunk.z))

    fun shutdown() = registry.shutdown()
}
