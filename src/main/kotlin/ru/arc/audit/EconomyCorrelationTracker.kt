package ru.arc.audit

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs

/** Correlates a structured shop attempt with RedisEconomy's later balance event. */
object EconomyPendingContextTracker {
    private data class Pending(
        val expectedAmounts: List<Double>,
        val context: EconomyLedgerContext,
        val expiresAt: Long,
    )

    private val pending = ConcurrentHashMap<UUID, ConcurrentLinkedDeque<Pending>>()

    fun register(playerId: UUID, expectedAmount: Double?, context: EconomyLedgerContext, now: Long) {
        val amount = expectedAmount?.takeIf(Double::isFinite) ?: return
        register(playerId, listOf(amount), context, now)
    }

    fun register(playerId: UUID, expectedAmounts: Collection<Double>, context: EconomyLedgerContext, now: Long) {
        val amounts = expectedAmounts.asSequence().filter(Double::isFinite).distinct().toList()
        if (amounts.isEmpty()) return
        val queue = pending.computeIfAbsent(playerId) { ConcurrentLinkedDeque() }
        queue.addLast(Pending(amounts, context, now + TTL_MILLIS))
        while (queue.size > MAX_PENDING_PER_PLAYER) queue.pollFirst()
    }

    fun consume(playerId: UUID, amount: Double, now: Long): EconomyLedgerContext? {
        val queue = pending[playerId] ?: return null
        queue.removeIf { it.expiresAt < now }
        val match = queue.firstOrNull { candidate ->
            candidate.expectedAmounts.any { approximatelyEqualMoney(it, amount) }
        }
        if (match != null) queue.remove(match)
        if (queue.isEmpty()) pending.remove(playerId, queue)
        return match?.context?.asTransaction()
    }

    internal fun clear() = pending.clear()

    private const val TTL_MILLIS = 10_000L
    private const val MAX_PENDING_PER_PLAYER = 32
}

/** Pairs the debit and credit sides of one player transfer without player labels in metrics. */
object EconomyTransferCorrelationTracker {
    private data class Key(
        val first: String,
        val second: String,
        val currency: String,
        val amountBits: Long,
        val reason: String,
    )

    private data class Side(
        val account: String,
        val actor: String,
        val timestamp: Long,
        val correlationId: String,
    )

    private val pending = ConcurrentHashMap<Key, ConcurrentLinkedDeque<Side>>()

    fun correlate(
        account: String,
        actor: String,
        currency: String,
        amount: Double,
        reason: String,
        timestamp: Long,
    ): String {
        val parties = listOf(account, actor).sorted()
        val key = Key(parties[0], parties[1], currency, abs(amount).toBits(), reason.take(120))
        val queue = pending.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        queue.removeIf { timestamp - it.timestamp > TTL_MILLIS }
        val opposite = queue.firstOrNull { it.account == actor && it.actor == account && abs(timestamp - it.timestamp) <= TTL_MILLIS }
        if (opposite != null) {
            queue.remove(opposite)
            if (queue.isEmpty()) pending.remove(key, queue)
            return opposite.correlationId
        }
        val correlationId = UUID.randomUUID().toString()
        queue.addLast(Side(account, actor, timestamp, correlationId))
        while (queue.size > MAX_PENDING_PER_KEY) queue.pollFirst()
        return correlationId
    }

    internal fun clear() = pending.clear()

    private const val TTL_MILLIS = 2_000L
    private const val MAX_PENDING_PER_KEY = 8
}
