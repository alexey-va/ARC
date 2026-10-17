package ru.arc.metrics

import ru.arc.metrics.core.MetricPoint
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One FIFO owner for metrics I/O and rolling state. Admission never runs the submitted work.
 * The short admission gate only orders offer/close; no I/O or processor lock is held there.
 * One reserved terminal slot drains accepted work even when the ordinary queue is saturated.
 */
internal class MetricsWorkQueue(
    capacity: Int = 8_192,
    predecessor: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
    private val onFailure: (Throwable) -> Unit = {},
    initialize: () -> Unit = {},
) {
    private val admission = Any()
    private val slots = Semaphore(capacity)
    private val accepted = AtomicLong()
    private val rejected = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var accepting = true
    @Volatile private var state = "starting"
    @Volatile private var startupFailure: Throwable? = null
    private val closed = CompletableFuture<Unit>()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity + 1), { task -> Thread(task, "arc-metrics-storage").apply { isDaemon = false } },
        ThreadPoolExecutor.AbortPolicy())
    val ready = CompletableFuture<Unit>()

    init {
        require(capacity > 0)
        executor.execute {
            try {
                predecessor.join() // worker only: a replacement must never overtake an older file flush
                initialize()
                state = "ready"
                ready.complete(Unit)
            } catch (failure: Throwable) {
                startupFailure = failure
                state = "failed"
                failed(failure)
                ready.completeExceptionally(failure)
            }
        }
    }

    fun offer(action: () -> Unit): Boolean = admit(null, action)
    fun <T> submit(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { admit(it, action) }

    private fun <T> admit(result: CompletableFuture<T>?, action: () -> T): Boolean = synchronized(admission) {
        if (!accepting || startupFailure != null || !slots.tryAcquire()) {
            rejected.incrementAndGet()
            result?.completeExceptionally(RejectedExecutionException("Metrics capture queue is closed or full"))
            return false
        }
        try {
            executor.execute {
                try {
                    startupFailure?.let { throw IllegalStateException("Metrics initialization failed", it) }
                    val value = action()
                    result?.complete(value)
                } catch (failure: Throwable) {
                    failed(failure)
                    result?.completeExceptionally(failure)
                } finally { slots.release() }
            }
            accepted.incrementAndGet()
            true
        } catch (failure: RejectedExecutionException) {
            slots.release()
            rejected.incrementAndGet()
            result?.completeExceptionally(failure)
            false
        }
    }

    /** A component can be unavailable even while the shared metrics runtime is healthy. */
    fun reject(): Boolean { rejected.incrementAndGet(); return false }

    fun closeAsync(cleanup: () -> Unit = {}): CompletableFuture<Unit> = synchronized(admission) {
        if (!accepting) return closed
        accepting = false
        executor.execute {
            state = "draining"
            try {
                cleanup()
                startupFailure?.let { throw IllegalStateException("Metrics initialization failed", it) }
                state = "closed"
                closed.complete(Unit)
            } catch (failure: Throwable) {
                state = "drain_failed"
                failed(failure)
                closed.completeExceptionally(failure)
            } finally { executor.shutdown() }
        }
        closed
    }

    private fun failed(failure: Throwable) {
        val count = failures.incrementAndGet()
        // Log first and powers of two; diagnostics retain the exact total without a log flood.
        if (count and (count - 1) == 0L) runCatching { onFailure(failure) }
    }

    fun coverageComplete(): Boolean = rejected.get() == 0L && failures.get() == 0L

    fun health(): Map<String, Any> = mapOf("state" to state, "accepting" to accepting,
        "queued" to executor.queue.size, "accepted" to accepted.get(), "rejected" to rejected.get(), "failures" to failures.get(),
        "durability" to "accepted_in_memory_until_async_flush")

    fun metricPoints(): List<MetricPoint> = listOf(
        MetricPoint("arc_product_capture_queue_depth", "Queued telemetry operations", executor.queue.size.toDouble()),
        MetricPoint("arc_product_capture_accepted_total", "Telemetry operations admitted since lifecycle start", accepted.get().toDouble()),
        MetricPoint("arc_product_capture_rejected_total", "Telemetry operations rejected by capacity or lifecycle", rejected.get().toDouble()),
        MetricPoint("arc_product_capture_failures_total", "Telemetry processing failures since lifecycle start", failures.get().toDouble()),
    )
}
