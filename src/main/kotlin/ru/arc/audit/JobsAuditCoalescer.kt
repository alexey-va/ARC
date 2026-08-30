package ru.arc.audit

import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded in-memory minute accumulator for high-frequency Jobs payouts.
 * Player balances are still changed immediately; only the audit representation is coalesced.
 */
internal class JobsAuditCoalescer(
    private val windowMillis: Long,
    private val maximumPendingEvents: Int,
    private val maximumEventsPerGroup: Int,
    private val onCoalesced: (Int) -> Unit = {},
    private val sink: (AuditEvent) -> CompletableFuture<AuditAppendResult>,
) {
    private data class Key(
        val playerKey: String,
        val type: Type,
        val flow: EconomyFlow,
        val currency: String,
        val server: String,
        val recordKind: EconomyRecordKind,
        val status: EconomyEventStatus,
        val action: String,
    )

    private data class Pending(
        val event: AuditEvent,
        val completion: CompletableFuture<AuditAppendResult>,
    )

    private data class Group(
        val firstAcceptedAt: Long,
        val pending: MutableList<Pending> = mutableListOf(),
    )

    private val lock = Any()
    private val groups = linkedMapOf<Key, Group>()
    private val accepted = AtomicInteger()
    private val closed = AtomicBoolean(false)

    init {
        require(windowMillis > 0L) { "Jobs audit coalescing window must be positive" }
        require(maximumPendingEvents > 0) { "Jobs audit pending-event bound must be positive" }
        require(maximumEventsPerGroup in 1..maximumPendingEvents) {
            "Jobs audit group bound must fit the pending-event bound"
        }
    }

    val pendingCount: Int get() = accepted.get()

    fun append(event: AuditEvent, acceptedAt: Long = System.currentTimeMillis()): CompletableFuture<AuditAppendResult> {
        val key = key(event) ?: return sink(event)
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Audit writer is closed"))
        if (!reserve()) return CompletableFuture.failedFuture(AuditBufferFullException(maximumPendingEvents))

        val completion = CompletableFuture<AuditAppendResult>()
        var ready: Group? = null
        synchronized(lock) {
            if (closed.get()) {
                accepted.decrementAndGet()
                return CompletableFuture.failedFuture(IllegalStateException("Audit writer is closed"))
            }
            val group = groups.computeIfAbsent(key) { Group(acceptedAt) }
            group.pending += Pending(event, completion)
            if (group.pending.size >= maximumEventsPerGroup) {
                groups.remove(key)
                ready = group
            }
        }
        ready?.let(::emit)
        return completion
    }

    /** Emits due groups into the ordinary bounded SQL batcher without blocking the scheduler. */
    fun flushDue(now: Long = System.currentTimeMillis()): Int {
        val due = mutableListOf<Group>()
        synchronized(lock) {
            val iterator = groups.entries.iterator()
            while (iterator.hasNext()) {
                val group = iterator.next().value
                if (now - group.firstAcceptedAt >= windowMillis) {
                    iterator.remove()
                    due += group
                }
            }
        }
        due.forEach(::emit)
        return due.size
    }

    fun flushAll(): Int {
        val all = synchronized(lock) {
            groups.values.toList().also { groups.clear() }
        }
        all.forEach(::emit)
        return all.size
    }

    fun stopAccepting() {
        closed.set(true)
    }

    private fun emit(group: Group) {
        val merged = merge(group.pending.map(Pending::event))
        onCoalesced(group.pending.size)
        val write = runCatching { sink(merged) }.getOrElse { CompletableFuture.failedFuture(it) }
        write.whenComplete { result, failure ->
            accepted.addAndGet(-group.pending.size)
            group.pending.forEach { pending ->
                if (failure == null) pending.completion.complete(result)
                else pending.completion.completeExceptionally(failure)
            }
        }
    }

    private fun key(event: AuditEvent): Key? {
        val transaction = event.transaction
        val context = transaction.context ?: return null
        if (
            transaction.normalizedSource != EconomySource.JOBS ||
            transaction.normalizedRecordKind != EconomyRecordKind.TRANSACTION ||
            context.normalizedJobBreakdown.isEmpty()
        ) return null
        return Key(
            playerKey = event.playerKey,
            type = transaction.type,
            flow = transaction.normalizedFlow,
            currency = transaction.normalizedCurrency,
            server = transaction.normalizedServer,
            recordKind = transaction.normalizedRecordKind,
            status = transaction.normalizedStatus,
            action = transaction.normalizedAction.label,
        )
    }

    private fun merge(events: List<AuditEvent>): AuditEvent {
        require(events.isNotEmpty()) { "Cannot coalesce an empty Jobs audit group" }
        val ordered = events.sortedWith(compareBy({ it.transaction.timestamp }, { it.eventId }))
        val first = ordered.first()
        val last = ordered.last()
        val eventId = sha256(ordered.map(AuditEvent::eventId).sorted().joinToString("|", prefix = "jobs-window-v1|"))
        val breakdown =
            ordered.asSequence()
                .flatMap { it.transaction.context?.normalizedJobBreakdown.orEmpty().asSequence() }
                .groupBy { listOf(it.job, it.activity, it.target, it.origin) }
                .values
                .map { components ->
                    val sample = components.first()
                    sample.copy(
                        amount = components.sumOf { it.amount ?: 0.0 },
                        occurrences = components.sumOf(EconomyJobRewardComponent::normalizedOccurrences),
                    )
                }
                .sortedWith(compareBy({ it.job }, { it.activity }, { it.target }, { it.origin }))
                .take(MAX_JOB_COMPONENTS)
        val firstContext = first.transaction.context
        val lastContext = last.transaction.context
        val context =
            firstContext?.copy(
                providerTimestamp = ordered.mapNotNull { it.transaction.context?.providerTimestamp }.maxOrNull(),
                correlationId = eventId,
                balanceBefore = firstContext.balanceBefore,
                balanceAfter = lastContext?.balanceAfter,
                balanceEvidence = null,
                requestedAmount = ordered.sumOf { it.transaction.context?.requestedAmount ?: it.transaction.amount },
                jobBreakdown = breakdown,
                capturedAt = ordered.mapNotNull { it.transaction.context?.capturedAt }.maxOrNull(),
            )
        return AuditEvent(
            playerName = first.playerName,
            transaction =
                first.transaction.copy(
                    amount = ordered.sumOf { it.transaction.amount },
                    timestamp = first.transaction.timestamp,
                    timestamp2 = ordered.maxOf { it.transaction.timestamp2 },
                    occurrences = ordered.sumOf { it.transaction.occurrenceCount },
                    eventId = eventId,
                    context = context,
                ),
        )
    }

    private fun reserve(): Boolean {
        while (true) {
            val current = accepted.get()
            if (current >= maximumPendingEvents) return false
            if (accepted.compareAndSet(current, current + 1)) return true
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_JOB_COMPONENTS = 64
    }
}
