package ru.arc.metrics

import org.bukkit.Server
import org.bukkit.World
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.paper.PaperMetricsCollector
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses the published collector through a typed one-world view; every Bukkit call stays on Paper. */
internal class BoundedPaperMetricsCapture(
    private val server: Server,
    private val worker: MetricsWorkQueue,
    private val publish: (List<MetricPoint>) -> Unit,
) : AutoCloseable {
    private val batch = NativeTelemetryBatch(maxPerTick = 1)
    @Volatile private var closed = false
    private var collecting = false

    fun sample() {
        if (closed || collecting) return
        val worlds = server.worlds.map { it.uid }
        if (worlds.isEmpty()) {
            val points = collector(emptyList()).heavySnapshot()
            worker.offer { if (!closed) publish(points) }
            return
        }
        collecting = true
        val complete = ArrayList<List<MetricPoint>>() // worker confined
        val rejected = AtomicBoolean()
        var remaining = worlds.size // native thread confined
        batch.start(worlds) { id ->
            // Unloaded worlds are skipped; no chunk/world load is attempted.
            val world = server.getWorld(id)
            val points = try { collector(world?.let(::listOf).orEmpty()).heavySnapshot() }
            catch (failure: Exception) {
                rejected.set(true)
                if (--remaining == 0) collecting = false
                throw failure
            }
            val last = --remaining == 0
            if (!worker.offer {
                complete += points
                if (last && !closed && !rejected.get()) publish(combine(complete))
            }) rejected.set(true)
            if (last) collecting = false
        }
    }

    private fun collector(worlds: List<World>) = PaperMetricsCollector(object : Server by server {
        override fun getWorlds(): List<World> = worlds
    })

    override fun close() { closed = true; batch.close() }

    companion object {
        private val TOTALS = setOf("arc_loaded_chunks_total", "arc_entities_total", "arc_paper_tile_entities_total", "arc_paper_tickable_tile_entities_total")
        /** World-labelled series concatenate; global gauges keep one value; four additive totals sum. */
        internal fun combine(snapshots: List<List<MetricPoint>>): List<MetricPoint> {
            val result = linkedMapOf<Pair<String, Map<String, String>>, MetricPoint>()
            snapshots.flatten().forEach { point ->
                val key = point.name to point.tags
                val previous = result[key]
                result[key] = if (point.name in TOTALS && previous != null) point.copy(value = previous.value + point.value) else point
            }
            return result.values.toList()
        }
    }
}
