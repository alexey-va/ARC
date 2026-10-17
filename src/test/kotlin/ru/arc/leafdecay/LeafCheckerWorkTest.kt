package ru.arc.leafdecay

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.Config
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.nio.file.Path

class LeafCheckerWorkTest {
    @TempDir lateinit var directory: Path

    private fun checker(floating: Boolean = false): LeafChecker {
        val config = Config(directory, "leaf.yml")
        config.setDouble("decay-chance", 1.0)
        config.setBoolean("remove-floating-blobs", floating)
        config.setInt("leaf-distance", 3)
        return LeafChecker(config, setOf(Material.OAK_LEAVES), setOf(Material.OAK_LOG))
    }

    @Test fun `chunk and connectivity traversal each read at most one cell per step`() {
        val checker = checker()
        var reads = 0
        val leaf = LeafPosition(0, 0, 0)
        val scan = checker.beginScan(0, 0, 0, 1, random = { 0.0 }) { position ->
            reads++
            if (position == leaf) LeafCell(Material.OAK_LEAVES, false) else LeafCell(Material.AIR, true)
        }
        assertEquals(0, reads)
        var steps = 0
        while (true) {
            val before = reads
            val done = scan.step()
            assertTrue(reads - before in 0..1)
            if (done) break
            assertTrue(++steps < 10_000)
        }
        assertTrue(reads > 512)
        assertEquals(setOf(leaf), scan.result())
    }

    @Test fun `connected and protected leaves stay out of decay output`() {
        val checker = checker(floating = true)
        val protected = LeafPosition(0, 0, 0)
        val connected = LeafPosition(8, 0, 8)
        val scan = checker.beginScan(0, 0, 0, 0, random = { 0.0 }) { position ->
            when (position) {
                protected -> LeafCell(Material.OAK_LEAVES, false, hasData = true)
                connected -> LeafCell(Material.OAK_LEAVES, false)
                LeafPosition(9, 0, 8) -> LeafCell(Material.STONE, false)
                else -> LeafCell(Material.AIR, true)
            }
        }
        while (!scan.step()) Unit
        assertTrue(scan.result().isEmpty())
    }

    @Test fun `two scans share one aggregate budget and stale generation stops all remaining reads`() {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumSteps = 8, maximumNanos = Long.MAX_VALUE)
        val checker = checker()
        var reads = 0
        var current = true
        val jobs = List(2) {
            val scan = checker.beginScan(it, 0, 0, 2) { reads++; LeafCell(Material.AIR, true) }
            queue.submit({ current }, scan::step)
        }
        assertEquals(0, reads)
        scheduler.tick()
        assertEquals(8, reads)
        assertTrue(jobs.none { it.isDone })
        current = false
        scheduler.tick()
        assertEquals(8, reads)
        assertTrue(jobs.all { it.isCompletedExceptionally })
        queue.close()
        scope.close()
    }
}
