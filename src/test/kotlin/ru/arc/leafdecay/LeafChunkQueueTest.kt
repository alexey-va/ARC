package ru.arc.leafdecay

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

class LeafChunkQueueTest {
    @Test
    fun `chunks at the same coordinates in different worlds are kept`() {
        val first = chunk(UUID.randomUUID(), 4, -7)
        val second = chunk(UUID.randomUUID(), 4, -7)
        val queue = LeafChunkQueue()

        queue.add(first)
        queue.add(second)

        assertEquals(2, queue.size())
    }

    @Test
    fun `the same chunk is queued only once`() {
        val chunk = chunk(UUID.randomUUID(), 1, 2)
        val queue = LeafChunkQueue()

        queue.add(chunk)
        queue.add(chunk)

        assertEquals(1, queue.size())
        assertSame(chunk, queue.poll())
        assertEquals(0, queue.size())
    }

    private fun chunk(
        worldId: UUID,
        x: Int,
        z: Int,
    ): Chunk {
        val world =
            mockk<World> {
                every { uid } returns worldId
            }
        return mockk {
            every { this@mockk.world } returns world
            every { this@mockk.x } returns x
            every { this@mockk.z } returns z
        }
    }
}
