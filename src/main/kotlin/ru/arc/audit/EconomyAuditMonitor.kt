package ru.arc.audit

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import ru.arc.core.SystemTimeProvider
import ru.arc.core.TimeProvider
import ru.arc.util.Logging.warn
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class EconomyAnomaly(
    val timestamp: Long,
    val kind: String,
    val player: String,
    val amount: Double,
    val source: String,
    val flow: String,
    val server: String,
    val reason: String,
)

/** Low-cardinality metrics plus player-specific anomaly evidence kept out of Prometheus labels. */
class EconomyAuditMonitor(
    private val config: AuditConfig,
    private val registryProvider: () -> MeterRegistry? = { null },
    private val timeProvider: TimeProvider = SystemTimeProvider,
) {
    private data class IncomePoint(val timestamp: Long, val amount: Double)

    private data class MetricKey(
        val source: String,
        val flow: String,
        val direction: String,
        val currency: String,
    )

    private data class AnomalyMetricKey(val kind: String, val source: String)

    private data class PersistenceMetricKey(val source: String, val server: String)

    private data class AttemptMetricKey(val source: String, val status: String, val action: String)

    private data class ContextMetricKey(val source: String, val field: String, val present: String)

    private class IncomeWindow {
        val points = ArrayDeque<IncomePoint>()
        var total = 0.0
    }

    private val incomeWindows = ConcurrentHashMap<String, IncomeWindow>()
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val anomalies = ConcurrentLinkedDeque<EconomyAnomaly>()
    private val transactionCounters = ConcurrentHashMap<MetricKey, Counter>()
    private val amountCounters = ConcurrentHashMap<MetricKey, Counter>()
    private val anomalyCounters = ConcurrentHashMap<AnomalyMetricKey, Counter>()
    private val persistenceFailureCounters = ConcurrentHashMap<PersistenceMetricKey, Counter>()
    private val attemptCounters = ConcurrentHashMap<AttemptMetricKey, Counter>()
    private val contextCounters = ConcurrentHashMap<ContextMetricKey, Counter>()
    private val observations = AtomicLong()

    fun observe(
        player: String,
        amount: Double,
        metadata: AuditMetadata,
        reason: String,
        context: EconomyLedgerContext? = null,
    ) {
        if (!config.monitoringEnabled || !amount.isFinite() || amount == 0.0) return
        recordMetrics(amount, metadata)
        recordContextMetrics(metadata, context)

        val now = timeProvider.currentTimeMillis()
        if (abs(amount) >= config.largeTransactionAmount && config.largeTransactionAmount > 0.0) {
            emit("large_transaction", player, amount, metadata, reason, now)
        }
        if (amount > 0.0 && metadata.flow !in setOf(EconomyFlow.ADJUSTMENT, EconomyFlow.INTERNAL)) {
            observeIncome(player, amount, metadata, reason, now)
        }
        if (observations.incrementAndGet() % CLEANUP_INTERVAL == 0L) {
            cleanupStaleState(now)
        }
    }

    fun recent(limit: Int): List<EconomyAnomaly> = anomalies.toList().takeLast(limit.coerceIn(1, 100))

    fun observeAttempt(metadata: AuditMetadata, context: EconomyLedgerContext) {
        if (!config.monitoringEnabled) return
        val registry = registryProvider() ?: return
        val key =
            AttemptMetricKey(
                source = metadata.source.label,
                status = context.normalizedStatus.name.lowercase(),
                action = normalizeMetricLabel(context.action),
            )
        attemptCounters.computeIfAbsent(key) {
            Counter.builder("arc_economy_attempts_total")
                .description("Observed structured economy attempts by outcome")
                .tags("source", key.source, "status", key.status, "action", key.action)
                .register(registry)
        }.increment()
    }

    fun persistenceFailure(metadata: AuditMetadata) {
        val registry = registryProvider() ?: return
        val key = PersistenceMetricKey(metadata.source.label, metadata.server)
        persistenceFailureCounters.computeIfAbsent(key) {
            Counter.builder("arc_economy_persistence_failures_total")
                .description("Economy audit operations that could not be persisted")
                .tags("source", key.source, "server", key.server)
                .register(registry)
        }.increment()
    }

    fun unresolvedBalanceSet(
        player: String,
        absoluteBalance: Double,
        metadata: AuditMetadata,
        reason: String,
    ) {
        if (!config.monitoringEnabled) return
        emit("unresolved_balance_set", player, absoluteBalance, metadata, reason, timeProvider.currentTimeMillis())
    }

    private fun recordMetrics(amount: Double, metadata: AuditMetadata) {
        val registry = registryProvider() ?: return
        val direction = if (amount > 0.0) "income" else "expense"
        val key = MetricKey(metadata.source.label, metadata.flow.label, direction, metadata.currency)
        transactionCounters.computeIfAbsent(key) {
            Counter.builder("arc_economy_transactions_total")
                .description("Observed player economy transactions")
                .tags(key.tags())
                .register(registry)
        }.increment()
        amountCounters.computeIfAbsent(key) {
            Counter.builder("arc_economy_amount_total")
                .description("Absolute observed player economy amount")
                .baseUnit("currency")
                .tags(key.tags())
                .register(registry)
        }.increment(abs(amount))
    }

    private fun recordContextMetrics(metadata: AuditMetadata, context: EconomyLedgerContext?) {
        val registry = registryProvider() ?: return
        val fields =
            linkedMapOf(
                "balance" to (context?.balanceBefore != null && context.balanceAfter != null),
                "session" to !context?.sessionId.isNullOrBlank(),
                "world" to !context?.world.isNullOrBlank(),
                "counterparty" to (context?.counterparty != null),
                "items" to !context?.normalizedItems.isNullOrEmpty(),
                "correlation" to !context?.correlationId.isNullOrBlank(),
                "provider_timestamp" to (context?.providerTimestamp != null),
            )
        fields.forEach { (field, present) ->
            val key = ContextMetricKey(metadata.source.label, field, if (present) "true" else "false")
            contextCounters.computeIfAbsent(key) {
                Counter.builder("arc_economy_context_total")
                    .description("Economy ledger context field coverage")
                    .tags("source", key.source, "field", key.field, "present", key.present)
                    .register(registry)
            }.increment()
        }
    }

    private fun normalizeMetricLabel(value: String?): String =
        value.orEmpty().lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(32).ifBlank { "unknown" }

    private fun observeIncome(
        player: String,
        amount: Double,
        metadata: AuditMetadata,
        reason: String,
        now: Long,
    ) {
        val cutoff = now - config.rapidIncomeWindowSeconds * 1000L
        val windowKey = "${player.lowercase()}|${metadata.source.label}|${metadata.currency}"
        var count = 0
        var total = 0.0
        incomeWindows.compute(windowKey) { _, existing ->
            val window = existing ?: IncomeWindow()
            window.points.addLast(IncomePoint(now, amount))
            window.total += amount
            while (window.points.isNotEmpty() && window.points.first.timestamp < cutoff) {
                window.total -= window.points.removeFirst().amount
            }
            count = window.points.size
            total = window.total
            window
        }
        if (total >= config.rapidIncomeAmount || count >= config.rapidIncomeTransactions) {
            emit("rapid_income", player, total, metadata, "$count transactions; last=$reason", now)
        }
    }

    private fun emit(
        kind: String,
        player: String,
        amount: Double,
        metadata: AuditMetadata,
        reason: String,
        now: Long,
    ) {
        val key = "${player.lowercase()}|$kind|${metadata.source.label}|${metadata.currency}"
        val cooldownMillis = config.anomalyCooldownSeconds * 1000L
        var shouldEmit = false
        cooldowns.compute(key) { _, previous ->
            if (previous == null || now - previous >= cooldownMillis) {
                shouldEmit = true
                now
            } else {
                previous
            }
        }
        if (!shouldEmit) return

        val anomaly =
            EconomyAnomaly(
                timestamp = now,
                kind = kind,
                player = player,
                amount = amount,
                source = metadata.source.label,
                flow = metadata.flow.label,
                server = metadata.server,
                reason = reason.take(240),
            )
        anomalies.addLast(anomaly)
        while (anomalies.size > 100) anomalies.pollFirst()

        registryProvider()?.let { registry ->
            val metricKey = AnomalyMetricKey(kind, metadata.source.label)
            anomalyCounters.computeIfAbsent(metricKey) {
                Counter.builder("arc_economy_anomalies_total")
                    .description("Detected economy anomalies")
                    .tags("kind", metricKey.kind, "source", metricKey.source)
                    .register(registry)
            }.increment()
        }
        warn(
            "ECONOMY_ANOMALY kind={} player={} amount={} source={} flow={} server={} reason={}",
            kind,
            player,
            amount,
            metadata.source.label,
            metadata.flow.label,
            metadata.server,
            reason.take(240),
        )
    }

    private fun cleanupStaleState(now: Long) {
        val incomeCutoff = now - config.rapidIncomeWindowSeconds * 1000L
        incomeWindows.keys.forEach { key ->
            incomeWindows.computeIfPresent(key) { _, window ->
                if (window.points.lastOrNull()?.timestamp?.let { it < incomeCutoff } != false) null else window
            }
        }
        val cooldownCutoff = now - config.anomalyCooldownSeconds * 2000L
        cooldowns.entries.removeIf { it.value < cooldownCutoff }
    }

    private fun MetricKey.tags(): Iterable<io.micrometer.core.instrument.Tag> =
        io.micrometer.core.instrument.Tags.of(
            "source", source,
            "flow", flow,
            "direction", direction,
            "currency", currency,
        )

    private companion object {
        const val CLEANUP_INTERVAL = 1_000L
    }
}
