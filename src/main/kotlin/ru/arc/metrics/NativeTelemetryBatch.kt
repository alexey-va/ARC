package ru.arc.metrics

import ru.arc.core.LifecycleTaskScope
import java.util.ArrayDeque
import java.util.UUID

/** Native reads only. The time budget is checked between reads; a native getter cannot be preempted. */
internal class NativeTelemetryBatch(
    private val scope: LifecycleTaskScope = LifecycleTaskScope(),
    private val maxPerTick: Int = 8,
    private val budgetNanos: Long = 2_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onFailure: (Throwable) -> Unit = { ru.arc.util.Logging.warn("Native telemetry capture failed", it) },
) : AutoCloseable {
    private val pending = ArrayDeque<Pair<UUID, (UUID) -> Unit>>()
    private var scheduled = false
    private var closed = false

    init { require(maxPerTick > 0 && budgetNanos > 0) }

    /** An unfinished sweep is retained, never replaced by a newer timer firing. */
    fun start(players: Collection<UUID>, capture: (UUID) -> Unit) {
        if (closed || pending.isNotEmpty()) return
        players.forEach { pending.addLast(it to capture) }
        if (!scheduled && pending.isNotEmpty()) {
            scheduled = true
            scope.runLater(1L) { drain() }
        }
    }

    private fun drain() {
        val started = nanoTime()
        var count = 0
        while (pending.isNotEmpty() && count < maxPerTick && (count == 0 || nanoTime() - started < budgetNanos)) {
            val (id, capture) = pending.removeFirst()
            try { capture(id) } catch (failure: Exception) { onFailure(failure) }
            count++
        }
        if (pending.isNotEmpty()) scope.runLater(1L) { drain() } else scheduled = false
    }

    override fun close() { closed = true; scope.close(); pending.clear(); scheduled = false }
}
