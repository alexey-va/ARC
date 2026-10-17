package ru.arc.metrics

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.metrics.core.MetricPoint
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedTelemetryCaptureTest {
    @Test
    fun `player reads are spread over ticks and close cancels the unfinished sweep`() {
        val scheduler = TestTaskScheduler()
        val batch = NativeTelemetryBatch(LifecycleTaskScope(scheduler), maxPerTick = 2, nanoTime = { 0 })
        val ids = List(5) { UUID.randomUUID() }
        val captured = mutableListOf<UUID>()
        batch.start(ids) { captured += it }
        assertTrue(captured.isEmpty())
        scheduler.tick()
        assertEquals(ids.take(2), captured)
        batch.start(listOf(UUID.randomUUID())) { fail("An unfinished sweep must not be replaced") }
        scheduler.tick()
        assertEquals(ids.take(4), captured)
        batch.close()
        scheduler.tick(10)
        assertEquals(ids.take(4), captured)
    }

    @Test
    fun `native budget is checked between calls and a failed read does not strand the sweep`() {
        val scheduler = TestTaskScheduler()
        var nanos = 0L
        var failures = 0
        val batch = NativeTelemetryBatch(LifecycleTaskScope(scheduler), maxPerTick = 8,
            nanoTime = { nanos }, onFailure = { failures++ })
        val ids = List(3) { UUID.randomUUID() }
        val captured = mutableListOf<UUID>()
        batch.start(ids) {
            captured += it
            nanos += 3_000_000L // One indivisible getter may exceed the two-ms budget.
            if (it == ids[1]) error("world unloaded")
        }
        scheduler.tick()
        assertEquals(1, captured.size)
        scheduler.tick()
        assertEquals(2, captured.size)
        scheduler.tick()
        assertEquals(ids, captured)
        assertEquals(1, failures)
        batch.close()
    }

    @Test
    fun `world totals sum exactly while global gauges remain one series`() {
        fun points(world: String, count: Double) = listOf(
            MetricPoint("arc_loaded_chunks_total", "chunks", count),
            MetricPoint("arc_entities_total", "entities", count * 2),
            MetricPoint("arc_paper_tile_entities_total", "tiles", count * 3),
            MetricPoint("arc_paper_tickable_tile_entities_total", "ticking", count * 4),
            MetricPoint("arc_paper_world_entities", "world entities", count * 2, mapOf("world" to world)),
            MetricPoint("arc_paper_plugins", "plugins", 7.0, mapOf("state" to "enabled")),
            MetricPoint("arc_paper_scheduler_pending_tasks", "tasks", 5.0),
        )
        val combined = BoundedPaperMetricsCapture.combine(listOf(points("a", 2.0), points("b", 3.0)))
        assertEquals(5.0, combined.single { it.name == "arc_loaded_chunks_total" }.value)
        assertEquals(10.0, combined.single { it.name == "arc_entities_total" }.value)
        assertEquals(15.0, combined.single { it.name == "arc_paper_tile_entities_total" }.value)
        assertEquals(20.0, combined.single { it.name == "arc_paper_tickable_tile_entities_total" }.value)
        assertEquals(7.0, combined.single { it.name == "arc_paper_plugins" }.value)
        assertEquals(5.0, combined.single { it.name == "arc_paper_scheduler_pending_tasks" }.value)
        assertEquals(2, combined.count { it.name == "arc_paper_world_entities" })
    }

    @Test
    fun `published collector reads one live world on native ticks and publishes complete sweep off thread`() {
        val nativeThread = Thread.currentThread()
        val scheduler = TestTaskScheduler()
        val queue = MetricsWorkQueue()
        val published = AtomicReference<List<MetricPoint>>()
        val publishingThread = AtomicReference<Thread>()
        val server = mockk<Server>(relaxed = true)
        val reads = mutableListOf<String>()
        fun world(name: String, chunks: Int): World {
            val result = mockk<World>(relaxed = true)
            every { result.uid } returns UUID.randomUUID()
            every { result.name } returns name
            every { result.environment } returns World.Environment.NORMAL
            every { result.chunkCount } answers {
                assertSame(nativeThread, Thread.currentThread())
                reads += name
                chunks
            }
            every { result.entityCount } returns chunks * 2
            every { result.players } returns emptyList()
            every { result.forceLoadedChunks } returns emptySet()
            return result
        }
        val first = world("a", 2)
        val second = world("b", 3)
        every { server.worlds } answers { assertSame(nativeThread, Thread.currentThread()); listOf(first, second) }
        every { server.getWorld(first.uid) } answers { assertSame(nativeThread, Thread.currentThread()); first }
        every { server.getWorld(second.uid) } answers { assertSame(nativeThread, Thread.currentThread()); second }
        every { server.pluginManager.plugins } returns emptyArray()
        every { server.scheduler.pendingTasks } returns emptyList()
        try {
            Tasks.withScheduler(scheduler) {
                val capture = BoundedPaperMetricsCapture(server, queue) { published.set(it); publishingThread.set(Thread.currentThread()) }
                capture.sample()
                scheduler.tick()
                queue.submit {}.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("a"), reads)
                assertNull(published.get())
                scheduler.tick()
                queue.submit {}.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("a", "b"), reads)
                assertEquals(5.0, published.get().single { it.name == "arc_loaded_chunks_total" }.value)
                assertNotSame(nativeThread, publishingThread.get())
                published.set(null)
                capture.sample()
                capture.close()
                scheduler.tick(5)
                queue.submit {}.get(5, TimeUnit.SECONDS)
                assertNull(published.get())
                assertEquals(2, reads.size)
            }
        } finally { queue.closeAsync().get(5, TimeUnit.SECONDS) }
    }
}
