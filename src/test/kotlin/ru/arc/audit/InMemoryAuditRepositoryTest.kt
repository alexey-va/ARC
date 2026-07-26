package ru.arc.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class InMemoryAuditRepositoryTest {
    @Test
    fun `getOrCreate normalizes ids and invokes factory once concurrently`() {
        val repository = InMemoryAuditRepository()
        val creations = AtomicInteger()

        val futures =
            List(16) {
                CompletableFuture.supplyAsync {
                    repository.getOrCreate("Player") {
                        creations.incrementAndGet()
                        AuditData.create("Player")
                    }.join()
                }
            }

        futures.forEach { future ->
            assertEquals("player", future.join().id())
        }
        assertEquals(1, creations.get())
        assertEquals(repository.get("PLAYER").join(), repository.get("player").join())
    }

    @Test
    fun `getOrCreate rejects a factory returning another id`() {
        val repository = InMemoryAuditRepository()

        assertThrows(IllegalArgumentException::class.java) {
            repository.getOrCreate("expected") {
                AuditData.create("different")
            }
        }
    }
}
