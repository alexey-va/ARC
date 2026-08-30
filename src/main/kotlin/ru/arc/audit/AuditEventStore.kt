package ru.arc.audit

import java.util.concurrent.CompletableFuture

enum class AuditStorageMode {
    REDIS,
    DUAL,
    SQL,
    ;

    companion object {
        fun parse(value: String): AuditStorageMode =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported audit storage mode: $value")
    }
}

data class AuditEvent(
    val playerName: String,
    val transaction: Transaction,
) {
    init {
        require(playerName.isNotBlank()) { "Audit player name must not be blank" }
        require(!transaction.eventId.isNullOrBlank()) { "Audit event id must not be blank" }
    }

    val playerKey: String = playerName.trim().lowercase()
    val eventId: String = requireNotNull(transaction.eventId)
}

data class AuditPageRequest(
    val playerName: String,
    val page: Int,
    val pageSize: Int,
    val filter: AuditFilter = AuditFilter.ALL,
) {
    init {
        require(playerName.isNotBlank()) { "Audit player name must not be blank" }
        require(page >= 1) { "Audit page must be positive" }
        require(pageSize in 1..100) { "Audit page size must be between 1 and 100" }
    }

    val playerKey: String = playerName.trim().lowercase()
    val offset: Long = (page.toLong() - 1L) * pageSize
    val limit: Int = pageSize
}

data class AuditPage(
    val records: List<Transaction>,
    val totalRecords: Long,
)

data class AuditScanRequest(
    val sinceEpochMs: Long,
    val untilEpochMs: Long,
    val serverFilter: String? = null,
) {
    init {
        require(sinceEpochMs >= 0L) { "Audit scan start must not be negative" }
        require(untilEpochMs >= sinceEpochMs) { "Audit scan end must not precede its start" }
    }

    val normalizedServerFilter: String? =
        serverFilter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
}

data class AuditAppendResult(
    val inserted: Boolean,
)

data class AuditMigrationReport(
    val scanned: Long,
    val inserted: Long,
    val duplicates: Long,
    val failed: Long,
    val completedAt: Long,
)

data class AuditStorageStatus(
    val mode: AuditStorageMode,
    val ready: Boolean,
    val schemaVersion: Int? = null,
    val eventCount: Long? = null,
    val redisEventCount: Long? = null,
    val sqlEventCount: Long? = null,
    val pendingEvents: Int? = null,
    val migration: AuditMigrationReport? = null,
    val detail: String? = null,
)

fun interface AuditEventConsumer {
    fun accept(event: AuditEvent)
}

interface AuditEventStore : AutoCloseable {
    fun append(event: AuditEvent): CompletableFuture<AuditAppendResult>

    fun page(request: AuditPageRequest): CompletableFuture<AuditPage>

    /** Streams records in chronological order without retaining the result set. */
    fun scan(request: AuditScanRequest, consumer: AuditEventConsumer): CompletableFuture<Long>

    fun count(): CompletableFuture<Long>

    fun clearPlayer(playerName: String): CompletableFuture<Int>

    fun clearAll(): CompletableFuture<Int>

    fun prune(beforeEpochMs: Long): CompletableFuture<Int>

    fun status(): CompletableFuture<AuditStorageStatus>

    override fun close()
}
