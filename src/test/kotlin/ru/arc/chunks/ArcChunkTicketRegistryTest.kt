package ru.arc.chunks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ArcChunkTicketRegistryTest {
    private val key = ArcChunkKey(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        12,
        -4,
    )

    @Test
    fun `shared ticket is removed only after its last lease closes`() {
        val backend = RecordingBackend()
        val registry = ArcChunkTicketRegistry(backend)

        val first = checkNotNull(registry.acquire(key))
        val second = checkNotNull(registry.acquire(key))

        assertEquals(1, backend.addCalls)
        first.close()
        assertEquals(0, backend.removeCalls)
        second.close()
        assertEquals(1, backend.removeCalls)

        second.close()
        assertEquals(1, backend.removeCalls, "Lease close must be idempotent")
    }

    @Test
    fun `pre-existing ARC ticket is borrowed and never removed`() {
        val backend = RecordingBackend(addResult = ChunkTicketAddResult.ALREADY_PRESENT)
        val registry = ArcChunkTicketRegistry(backend)

        val lease = checkNotNull(registry.acquire(key))
        lease.close()
        registry.shutdown()

        assertEquals(1, backend.addCalls)
        assertEquals(0, backend.removeCalls)
    }

    @Test
    fun `failed release remains owned and is retried by the next lease`() {
        val backend = RecordingBackend(failRemove = true)
        val registry = ArcChunkTicketRegistry(backend)

        checkNotNull(registry.acquire(key)).close()
        assertEquals(1, backend.addCalls)
        assertEquals(1, backend.removeCalls)

        backend.failRemove = false
        checkNotNull(registry.acquire(key)).close()

        assertEquals(1, backend.addCalls, "Retry should reuse the ticket whose release was uncertain")
        assertEquals(2, backend.removeCalls)
    }

    @Test
    fun `shutdown releases each owned ticket once despite outstanding leases`() {
        val backend = RecordingBackend()
        val registry = ArcChunkTicketRegistry(backend)

        val first = checkNotNull(registry.acquire(key))
        val second = checkNotNull(registry.acquire(key))
        registry.shutdown()

        assertEquals(1, backend.removeCalls)
        first.close()
        second.close()
        assertEquals(1, backend.removeCalls)
    }

    private class RecordingBackend(
        private val addResult: ChunkTicketAddResult = ChunkTicketAddResult.ADDED,
        var failRemove: Boolean = false,
    ) : ArcChunkTicketBackend {
        var addCalls: Int = 0
        var removeCalls: Int = 0

        override fun add(key: ArcChunkKey): ChunkTicketAddResult {
            addCalls++
            return addResult
        }

        override fun remove(key: ArcChunkKey): Boolean {
            removeCalls++
            if (failRemove) error("simulated remove failure")
            return true
        }
    }
}
