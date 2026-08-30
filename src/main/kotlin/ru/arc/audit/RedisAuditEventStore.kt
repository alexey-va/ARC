package ru.arc.audit

import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/**
 * Compatibility adapter around the legacy Redis snapshot repository.
 *
 * New records are append-only and keep their SQL event id. The adapter remains
 * intentionally simple because it is only the read side during dual-write and
 * the rollback path after the SQL cutover.
 */
class RedisAuditEventStore(
    private val repository: AuditRepository,
) : AuditEventStore {
    override fun append(event: AuditEvent): CompletableFuture<AuditAppendResult> {
        val entityId = entityId(event)
        return repository.getOrCreate(entityId) {
            AuditData.create(event.playerName, entityId.takeIf { ':' in it })
        }.thenApply { data ->
            synchronized(data) {
                if (data.transactions.any { transaction -> stableEventId(data.name, transaction) == event.eventId }) {
                    AuditAppendResult(false)
                } else {
                    data.transactions.add(event.transaction.copy(eventId = event.eventId))
                    repository.save(data)
                    AuditAppendResult(true)
                }
            }
        }
    }

    override fun page(request: AuditPageRequest): CompletableFuture<AuditPage> =
        CompletableFuture.completedFuture(
            snapshotEvents()
                .asSequence()
                .filter { it.playerKey == request.playerKey }
                .filter { event -> matches(event.transaction, request.filter) }
                .sortedWith(compareByDescending<AuditEvent> { it.transaction.timestamp2 }.thenByDescending(AuditEvent::eventId))
                .toList()
                .let { matches ->
                    AuditPage(
                        records = matches.drop(request.offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(request.limit)
                            .map(AuditEvent::transaction),
                        totalRecords = matches.size.toLong(),
                    )
                },
        )

    override fun scan(
        request: AuditScanRequest,
        consumer: AuditEventConsumer,
    ): CompletableFuture<Long> {
        val events =
            snapshotEvents()
                .asSequence()
                .filter { event ->
                    event.transaction.timestamp2 >= request.sinceEpochMs &&
                        event.transaction.timestamp <= request.untilEpochMs &&
                        (request.normalizedServerFilter == null ||
                            event.transaction.normalizedServer == request.normalizedServerFilter)
                }
                .sortedWith(compareBy<AuditEvent> { it.transaction.timestamp }.thenBy(AuditEvent::eventId))
                .toList()
        events.forEach(consumer::accept)
        return CompletableFuture.completedFuture(events.size.toLong())
    }

    override fun count(): CompletableFuture<Long> =
        CompletableFuture.completedFuture(
            repository.all().sumOf { data -> synchronized(data) { data.transactions.size.toLong() } },
        )

    override fun clearPlayer(playerName: String): CompletableFuture<Int> {
        var removed = 0
        repository.all().filter { it.name.equals(playerName, ignoreCase = true) }.forEach { data ->
            synchronized(data) {
                removed += data.transactions.size
                data.clear()
                repository.save(data)
            }
        }
        return CompletableFuture.completedFuture(removed)
    }

    override fun clearAll(): CompletableFuture<Int> {
        var removed = 0
        repository.all().forEach { data ->
            synchronized(data) {
                removed += data.transactions.size
                data.clear()
                repository.save(data)
            }
        }
        return CompletableFuture.completedFuture(removed)
    }

    override fun prune(beforeEpochMs: Long): CompletableFuture<Int> {
        var removed = 0
        repository.all().forEach { data ->
            synchronized(data) {
                val before = data.transactions.size
                data.transactions.removeIf { transaction -> transaction.timestamp2 < beforeEpochMs }
                val changed = before - data.transactions.size
                if (changed > 0) repository.save(data)
                removed += changed
            }
        }
        return CompletableFuture.completedFuture(removed)
    }

    override fun status(): CompletableFuture<AuditStorageStatus> =
        count().thenApply { count ->
            AuditStorageStatus(
                mode = AuditStorageMode.REDIS,
                ready = true,
                eventCount = count,
                redisEventCount = count,
            )
        }

    override fun close() = repository.shutdown()

    internal fun snapshotEvents(): List<AuditEvent> = snapshotEventSequence().toList()

    internal fun snapshotEventSequence(): Sequence<AuditEvent> =
        repository.all().asSequence().flatMap { data ->
            synchronized(data) {
                data.transactions.map { transaction ->
                    AuditEvent(data.name, transaction.copy(eventId = stableEventId(data.name, transaction)))
                }
            }.asSequence()
        }

    private fun entityId(event: AuditEvent): String {
        val playerId = event.playerKey
        val server = event.transaction.normalizedServer.takeUnless { it.isEmpty() || it == "unknown" }
        return if (server == null) playerId else "$server:$playerId"
    }

    private fun matches(transaction: Transaction, filter: AuditFilter): Boolean =
        when (filter) {
            AuditFilter.ALL -> true
            AuditFilter.INCOME -> transaction.amount > 0.0
            AuditFilter.EXPENSE -> transaction.amount < 0.0
            AuditFilter.SHOP -> transaction.type == Type.SHOP
            AuditFilter.JOB -> transaction.type == Type.JOB
            AuditFilter.PAY -> transaction.type == Type.PAY
        }

    private fun stableEventId(playerName: String, transaction: Transaction): String =
        transaction.eventId?.takeIf(String::isNotBlank) ?: sha256(
            listOf(
                "legacy-audit-v1",
                playerName.lowercase(),
                transaction.timestamp.toString(),
                transaction.timestamp2.toString(),
                transaction.type.name,
                transaction.amount.toString(),
                transaction.comment,
                transaction.normalizedSource.label,
                transaction.normalizedFlow.label,
                transaction.normalizedCurrency,
                transaction.normalizedServer,
                transaction.origin.orEmpty(),
                transaction.occurrenceCount.toString(),
            ).joinToString("|"),
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

class DualWriteAuditEventStore(
    private val redis: AuditEventStore,
    private val sql: AuditEventStore,
) : AuditEventStore {
    override fun append(event: AuditEvent): CompletableFuture<AuditAppendResult> =
        redis.append(event).thenCombine(sql.append(event)) { redisResult, sqlResult ->
            AuditAppendResult(redisResult.inserted || sqlResult.inserted)
        }

    override fun page(request: AuditPageRequest): CompletableFuture<AuditPage> = redis.page(request)

    override fun scan(request: AuditScanRequest, consumer: AuditEventConsumer): CompletableFuture<Long> =
        redis.scan(request, consumer)

    override fun count(): CompletableFuture<Long> = redis.count()

    override fun clearPlayer(playerName: String): CompletableFuture<Int> =
        redis.clearPlayer(playerName).thenCombine(sql.clearPlayer(playerName)) { redisCount, _ -> redisCount }

    override fun clearAll(): CompletableFuture<Int> =
        redis.clearAll().thenCombine(sql.clearAll()) { redisCount, _ -> redisCount }

    override fun prune(beforeEpochMs: Long): CompletableFuture<Int> =
        redis.prune(beforeEpochMs).thenCombine(sql.prune(beforeEpochMs)) { redisCount, _ -> redisCount }

    override fun status(): CompletableFuture<AuditStorageStatus> =
        redis.status().thenCombine(sql.status()) { redisStatus, sqlStatus ->
            AuditStorageStatus(
                mode = AuditStorageMode.DUAL,
                ready = redisStatus.ready && sqlStatus.ready,
                schemaVersion = sqlStatus.schemaVersion,
                eventCount = sqlStatus.eventCount,
                redisEventCount = redisStatus.eventCount,
                sqlEventCount = sqlStatus.eventCount,
                pendingEvents = sqlStatus.pendingEvents,
                detail = "redis=" + redisStatus.eventCount + ", sql=" + sqlStatus.eventCount,
            )
        }

    override fun close() {
        redis.close()
        sql.close()
    }
}
