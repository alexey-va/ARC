package ru.arc.audit

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/** ARC-owned storage metrics; no auxiliary plugin opens a metrics port. */
class AuditStorageTelemetry(
    registryProvider: () -> MeterRegistry?,
    mode: AuditStorageMode,
) {
    private val pending = AtomicLong()
    private val accepted: Counter?
    private val inserted: Counter?
    private val duplicates: Counter?
    private val retryFailures: Counter?
    private val compactedSource: Counter?
    private val compactedAggregates: Counter?
    private val expired: Counter?
    private val coalescedInput: Counter?
    private val coalescedOutput: Counter?

    init {
        val registry = registryProvider()
        if (registry == null) {
            accepted = null
            inserted = null
            duplicates = null
            retryFailures = null
            compactedSource = null
            compactedAggregates = null
            expired = null
            coalescedInput = null
            coalescedOutput = null
        } else {
            Gauge.builder("arc_audit_storage_pending_events", pending) { value -> value.get().toDouble() }
                .description("Economy audit events waiting for an asynchronous SQL batch")
                .tag("mode", mode.name.lowercase())
                .register(registry)
            accepted = counter(registry, "accepted", mode)
            inserted = counter(registry, "inserted", mode)
            duplicates = counter(registry, "duplicate", mode)
            retryFailures = counter(registry, "retry_failure", mode)
            compactedSource = maintenanceCounter(registry, "compacted_source", mode)
            compactedAggregates = maintenanceCounter(registry, "compacted_aggregate", mode)
            expired = maintenanceCounter(registry, "expired", mode)
            coalescedInput = coalescingCounter(registry, "input", mode)
            coalescedOutput = coalescingCounter(registry, "output", mode)
        }
    }

    fun accepted(pendingEvents: Int) {
        accepted?.increment()
        pending.set(pendingEvents.toLong())
    }

    fun completed(result: AuditAppendResult, pendingEvents: Int) {
        if (result.inserted) inserted?.increment() else duplicates?.increment()
        pending.set(pendingEvents.toLong())
    }

    fun retryFailure(pendingEvents: Int) {
        retryFailures?.increment()
        pending.set(pendingEvents.toLong())
    }

    fun maintenance(report: AuditMaintenanceReport) {
        compactedSource?.increment(report.compactedSourceRows.toDouble())
        compactedAggregates?.increment(report.compactedRows.toDouble())
        expired?.increment(report.expiredRows.toDouble())
    }

    fun coalesced(inputEvents: Int) {
        coalescedInput?.increment(inputEvents.toDouble())
        coalescedOutput?.increment()
    }

    private fun counter(registry: MeterRegistry, result: String, mode: AuditStorageMode): Counter =
        Counter.builder("arc_audit_storage_events_total")
            .description("Economy audit asynchronous SQL writer outcomes")
            .tags("mode", mode.name.lowercase(), "result", result)
            .register(registry)

    private fun maintenanceCounter(registry: MeterRegistry, kind: String, mode: AuditStorageMode): Counter =
        Counter.builder("arc_audit_storage_maintenance_rows_total")
            .description("Economy audit rows processed by bounded maintenance")
            .tags("mode", mode.name.lowercase(), "kind", kind)
            .register(registry)

    private fun coalescingCounter(registry: MeterRegistry, kind: String, mode: AuditStorageMode): Counter =
        Counter.builder("arc_audit_storage_coalescing_events_total")
            .description("Jobs audit events entering and leaving the bounded minute coalescer")
            .tags("mode", mode.name.lowercase(), "kind", kind)
            .register(registry)
}
