package ru.arc.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AwaitingResponseGateTest {
    @Test
    fun `only one response per player runs concurrently`() {
        val playerId = UUID.randomUUID()
        val gate = AwaitingResponseGate()
        val pending = CompletableFuture<String>()

        val first = gate.run(playerId) { pending }
        val duplicate = gate.run(playerId) { CompletableFuture.completedFuture("duplicate") }

        assertTrue(gate.isAwaiting(playerId))
        assertNull(duplicate)

        pending.complete("done")
        assertTrue(first!!.isDone)
        assertFalse(gate.isAwaiting(playerId))
    }

    @Test
    fun `failed and synchronously throwing responses release the player`() {
        val playerId = UUID.randomUUID()
        val gate = AwaitingResponseGate()

        gate.run<String>(playerId) {
            CompletableFuture.failedFuture(IllegalStateException("async failure"))
        }
        assertFalse(gate.isAwaiting(playerId))

        gate.run<String>(playerId) {
            throw IllegalStateException("sync failure")
        }
        assertFalse(gate.isAwaiting(playerId))
    }

    @Test
    fun `clear cancels pending responses and releases players`() {
        val playerId = UUID.randomUUID()
        val gate = AwaitingResponseGate()
        val pending = CompletableFuture<String>()

        val tracked = gate.run(playerId) { pending }
        gate.clear()

        assertTrue(pending.isCancelled)
        assertTrue(tracked!!.isCompletedExceptionally)
        assertFalse(gate.isAwaiting(playerId))
    }

    @Test
    fun `clear during response creation still cancels attached future`() {
        val playerId = UUID.randomUUID()
        val gate = AwaitingResponseGate()
        val pending = CompletableFuture<String>()

        val tracked = gate.run(playerId) {
            gate.clear()
            pending
        }

        assertTrue(pending.isCancelled)
        assertTrue(tracked!!.isCompletedExceptionally)
        assertFalse(gate.isAwaiting(playerId))
    }
}
