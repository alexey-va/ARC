package ru.arc.metrics

import ru.arc.metrics.core.MetricPoint
import ru.arc.product.ProductOnboardingHint
import java.util.concurrent.CompletableFuture

/** Immutable admission only: true means queued, never durable or already processed. */
internal class ProductInterestTelemetry(
    private val worker: MetricsWorkQueue,
    factory: ((() -> Unit) -> Boolean) -> ProductInterestProcessor,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private var processor: ProductInterestProcessor? = null // worker confined
    @Volatile private var initializationFailure: Throwable? = null
    @Volatile private var resetStatus: Map<String, Any?> = mapOf("state" to "starting")
    val ready: CompletableFuture<Unit> = worker.submit {
        processor = factory(worker::offer)
        processor().start()
        resetStatus = processor().measurementResetStatus() + ("state" to "ready")
    }.whenComplete { _, failure ->
        if (failure != null) {
            initializationFailure = failure
            resetStatus = mapOf("state" to "failed", "failure" to failure.javaClass.simpleName)
        }
    }

    private fun processor(): ProductInterestProcessor {
        initializationFailure?.let { throw IllegalStateException("Product telemetry initialization failed", it) }
        return checkNotNull(processor) { "Product telemetry is not initialized" }
    }
    private fun capture(action: (ProductInterestProcessor) -> Unit): Boolean =
        if (initializationFailure != null) worker.reject() else worker.offer { action(processor()) }
    internal fun <T> submit(action: (ProductInterestProcessor) -> T): CompletableFuture<T> {
        initializationFailure?.let { failure ->
            worker.reject()
            return CompletableFuture.failedFuture(IllegalStateException("Product telemetry initialization failed", failure))
        }
        return worker.submit { action(processor()) }
    }
    /** Only for an already admitted operation on the same queue owner. */
    internal fun processOnWorker(action: (ProductInterestProcessor) -> Unit) {
        if (initializationFailure == null) action(processor())
    }
    internal fun onWorker(action: (ProductInterestProcessor) -> Unit): Boolean = capture(action)

    fun join(playerId: String, firstJoin: Boolean, sample: ProductPlayerSample, qa: Boolean = false, now: Long = clockMillis()) =
        capture { it.join(playerId, firstJoin, sample, qa, now) }
    fun trackExisting(playerId: String, sample: ProductPlayerSample, qa: Boolean = false, now: Long = clockMillis()) =
        capture { it.trackExisting(playerId, sample, qa, now) }
    fun leave(playerId: String, now: Long = clockMillis()) = capture { it.leave(playerId, now) }
    fun command(playerId: String, message: String, now: Long = clockMillis()) = capture { it.command(playerId, message, now) }
    fun pathChoice(playerId: String, path: ProductPath, now: Long = clockMillis()) = capture { it.pathChoice(playerId, path, now) }
    fun featureInterest(playerId: String, feature: ProductFeature, entry: ProductEntryPoint, now: Long = clockMillis()) =
        capture { it.featureInterest(playerId, feature, entry, now) }
    fun onboardingHint(playerId: String, hint: ProductOnboardingHint, now: Long = clockMillis()) = capture { it.onboardingHint(playerId, hint, now) }
    fun outcome(playerId: String, outcome: ProductOutcome, feature: ProductFeature? = null,
                entry: ProductEntryPoint = ProductEntryPoint.API, now: Long = clockMillis()) =
        capture { it.outcome(playerId, outcome, feature, entry, now) }
    fun action(playerId: String, action: ProductAction, now: Long = clockMillis()) = capture { it.action(playerId, action, now) }
    fun worldChange(playerId: String, from: ProductWorldType, to: ProductWorldType, world: String, now: Long = clockMillis()) =
        capture { it.worldChange(playerId, from, to, world, now) }
    fun teleport(playerId: String, from: ProductWorldType, to: ProductWorldType, world: String, cause: String, now: Long = clockMillis()) =
        capture { it.teleport(playerId, from, to, world, cause, now) }
    fun npcClick(playerId: String, npcId: Int, npcName: String?, now: Long = clockMillis()) = capture { it.npcClick(playerId, npcId, npcName, now) }
    fun ui(playerId: String, kind: ProductUiKind, view: ProductUiView, button: String = "_menu", durationMillis: Long = 0,
           now: Long = clockMillis()): Boolean {
        val captured = view.copy(buttons = view.buttons.toMap(), details = view.details.toMap())
        return capture { it.ui(playerId, kind, captured, button, durationMillis, now) }
    }
    fun sample(samples: Collection<ProductPlayerSample>, now: Long = clockMillis()): Boolean {
        val captured = samples.toList()
        return capture { it.sample(captured, now) }
    }
    fun snapshot(networkReady: Boolean, now: Long = clockMillis()): CompletableFuture<List<MetricPoint>> = submit { it.snapshot(networkReady, now) }
    fun report(days: Int, limit: Int, networkReady: Boolean, now: Long = clockMillis()): CompletableFuture<Map<String, Any?>> =
        submit {
            val report = it.report(days, limit, networkReady, now)
            report + mapOf("captureQueue" to worker.health(), "complete" to (report["complete"] == true && worker.coverageComplete()))
        }
    fun flush(now: Long = clockMillis()) = submit { it.flush(now) }
    fun requestMeasurementReset(expectedGeneration: Long) = submit {
        it.requestMeasurementReset(expectedGeneration).also { resetStatus = processor().measurementResetStatus() + ("state" to "ready") }
    }
    fun readMeasurementReset() = submit { it.readMeasurementReset() }
    fun pollMeasurementReset() = submit {
        try { it.pollMeasurementReset() } finally { resetStatus = it.measurementResetStatus() + ("state" to "ready") }
    }
    fun measurementResetStatus(): Map<String, Any?> = resetStatus + ("captureQueue" to worker.health())
    /** Called by the worker's reserved terminal task, after every accepted event. */
    fun shutdownOnWorker(now: Long) {
        try { processor?.shutdown(now) }
        finally { initializationFailure?.let { throw IllegalStateException("Product telemetry never became ready; admitted events failed", it) } }
    }
}
