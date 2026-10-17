package ru.arc.metrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MetricsWorkQueueTest {
    @Test
    fun `blocked initialization never blocks admission and reserved close drains full FIFO`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = mutableListOf<Int>()
        val queue = MetricsWorkQueue(capacity = 2) { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(CompletableFuture.supplyAsync { queue.offer { order += 1 } }.get(2, TimeUnit.SECONDS))
            assertTrue(queue.offer { order += 2 })
            assertFalse(queue.offer { fail("Saturated work must not run") })
            val closed = queue.closeAsync { order += 3 }
            assertFalse(closed.isDone)
            assertFalse(queue.offer { fail("Closed work must not run") })
            release.countDown()
            closed.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1, 2, 3), order)
            assertEquals(2L, queue.health()["rejected"])
            assertEquals("closed", queue.health()["state"])
        } finally { release.countDown(); queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `replacement initialization waits for previous final durability without blocking caller`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = MetricsWorkQueue()
        val closing = first.closeAsync { entered.countDown(); release.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val initialized = AtomicBoolean()
        val next = MetricsWorkQueue(predecessor = closing) { initialized.set(true) }
        try {
            assertFalse(initialized.get())
            assertTrue(next.offer { assertTrue(initialized.get()) })
            val nextClose = next.closeAsync()
            assertFalse(nextClose.isDone)
            release.countDown()
            nextClose.get(5, TimeUnit.SECONDS)
            assertTrue(initialized.get())
        } finally { release.countDown(); next.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `failed initializer rejects later work and reports unsuccessful drain`() {
        val queue = MetricsWorkQueue { error("recovery failed") }
        assertThrows(ExecutionException::class.java) { queue.ready.get(5, TimeUnit.SECONDS) }
        assertFalse(queue.offer { fail("Must not execute without recovery") })
        assertEquals("failed", queue.health()["state"])
        assertFalse(queue.coverageComplete())
        assertThrows(ExecutionException::class.java) { queue.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `processing failures are visible and do not prevent later accepted work or cleanup`() {
        val queue = MetricsWorkQueue()
        val processed = AtomicBoolean()
        assertTrue(queue.offer { error("processor failure") })
        assertTrue(queue.offer { processed.set(true) })
        queue.closeAsync().get(5, TimeUnit.SECONDS)
        assertTrue(processed.get())
        assertEquals(1L, queue.health()["failures"])
        assertFalse(queue.coverageComplete())
    }

    @Test
    fun `topic bootstrap failure fences ready attachment`() {
        val queue = MetricsWorkQueue()
        val attached = AtomicBoolean()
        try {
            val transport = queue.submit<Unit> { error("subscribe failed") }
            val ready = awaitTelemetryInitialization(queue, CompletableFuture.completedFuture(Unit), transport)
                .thenRun { attached.set(true) }
            assertThrows(ExecutionException::class.java) { ready.get(5, TimeUnit.SECONDS) }
            assertFalse(attached.get())
        } finally { queue.closeAsync().get(5, TimeUnit.SECONDS) }
    }
}
