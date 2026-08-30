package ru.arc.audit

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Idempotent Redis snapshot import. Dual-write uses the same event ids while
 * this runs, so replaying any batch is safe and no global write pause is
 * required.
 */
class AuditLegacyMigration(
    private val source: RedisAuditEventStore,
    private val target: SqlAuditEventStore,
    private val batchSize: Int,
    private val scanExecutor: Executor = ForkJoinPool.commonPool(),
) {
    init {
        require(batchSize > 0) { "Audit migration batch size must be positive" }
    }

    fun migrate(): CompletableFuture<AuditMigrationReport> =
        CompletableFuture.supplyAsync({ source.snapshotEventSequence().iterator() }, scanExecutor)
            .thenCompose { iterator -> importNext(iterator, MutableReport()) }

    private fun importNext(
        iterator: Iterator<AuditEvent>,
        report: MutableReport,
    ): CompletableFuture<AuditMigrationReport> {
        val batch = ArrayList<AuditEvent>(batchSize)
        while (iterator.hasNext() && batch.size < batchSize) batch += iterator.next()
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(
                AuditMigrationReport(
                    scanned = report.scanned,
                    inserted = report.inserted,
                    duplicates = report.duplicates,
                    failed = report.failed,
                    completedAt = System.currentTimeMillis(),
                ),
            )
        }
        report.scanned += batch.size
        return target.insertBatch(batch).handle { result, failure ->
            if (failure == null) {
                report.inserted += result.first
                report.duplicates += result.second
            } else {
                report.failed += batch.size
                throw failure
            }
            Unit
        }.thenCompose { importNext(iterator, report) }
    }

    private data class MutableReport(
        var scanned: Long = 0,
        var inserted: Long = 0,
        var duplicates: Long = 0,
        var failed: Long = 0,
    )
}
