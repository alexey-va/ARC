package ru.arc.graves

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.redis.InMemoryRedis
import java.util.UUID

class GraveTrackingStoreTest {
    @Test
    fun `compass defaults on and enabled selection persists across store instances`() {
        val redis = InMemoryRedis()
        val playerId = UUID.randomUUID()
        val graveId = UUID.randomUUID()
        val first = GraveTrackingStore(redis, defaultEnabled = true)

        assertEquals(GraveTracking(enabled = true), first.load(playerId).join())
        assertEquals(
            GraveTracking(enabled = false, selected = graveId),
            first.update(playerId) { it.copy(enabled = false, selected = graveId) }.join(),
        )

        val restored = GraveTrackingStore(redis, defaultEnabled = false)
        assertEquals(GraveTracking(enabled = false, selected = graveId), restored.load(playerId).join())
        assertEquals(
            GraveTracking(enabled = true, selected = graveId),
            restored.update(playerId) { it.copy(enabled = true) }.join(),
        )
        assertEquals(GraveTracking(enabled = true, selected = graveId), first.load(playerId).join())
    }
}
