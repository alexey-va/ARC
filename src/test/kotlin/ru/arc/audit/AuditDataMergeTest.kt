package ru.arc.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedDeque

class AuditDataMergeTest {
    @Test
    fun `identical durable snapshot keeps existing transaction objects`() {
        val existing = transaction(eventId = "event-1", timestamp = 100)
        val local = data(existing)
        val remote = data(existing.copy())

        local.merge(remote)

        assertSame(existing, local.transactions.single())
    }

    @Test
    fun `newer remote aggregate wins without retaining the remote object`() {
        val local = data(transaction(eventId = "event-1", timestamp = 100, occurrences = 1, amount = 10.0))
        val remoteWinner = transaction(eventId = "event-1", timestamp = 100, occurrences = 2, amount = 20.0)

        local.merge(data(remoteWinner))

        val merged = local.transactions.single()
        assertEquals(2, merged.occurrenceCount)
        assertEquals(20.0, merged.amount)
        assertNotSame(remoteWinner, merged)

        remoteWinner.amount = 999.0
        assertEquals(20.0, merged.amount)
    }

    @Test
    fun `large identical snapshot is a no-op and preserves order`() {
        val existing =
            (0 until 2_000).map { index ->
                transaction(eventId = "event-$index", timestamp = index.toLong())
            }
        val local = data(*existing.toTypedArray())
        val remote = data(*existing.map(Transaction::copy).toTypedArray())

        local.merge(remote)

        assertEquals(existing.size, local.transactions.size)
        existing.zip(local.transactions).forEach { (expected, actual) -> assertSame(expected, actual) }
    }

    @Test
    fun `legacy records still deduplicate and unique records stay timestamp ordered`() {
        val legacy = transaction(eventId = null, timestamp = 200, occurrences = 1, amount = 10.0)
        val unique = transaction(eventId = "event-new", timestamp = 300, amount = 30.0)
        val local = data(legacy, unique)
        val legacyWinner = legacy.copy(amount = 20.0, timestamp2 = 250, occurrences = 2)
        val earlier = transaction(eventId = "event-early", timestamp = 100, amount = 5.0)

        local.merge(data(legacyWinner, earlier))

        assertEquals(listOf(100L, 200L, 300L), local.transactions.map(Transaction::timestamp))
        assertEquals(20.0, local.transactions.single { it.eventId == null }.amount)
    }

    @Test
    fun `event keys stay cached and cannot collide with legacy keys`() {
        val legacy = transaction(eventId = null, timestamp = 200)
        val event = transaction(eventId = legacy.mergeKey, timestamp = 300)

        assertNotEquals(legacy.mergeKey, event.mergeKey)
        assertSame(event.mergeKey, event.mergeKey)
    }

    private fun data(vararg transactions: Transaction): AuditData =
        AuditData(
            transactions = ConcurrentLinkedDeque(transactions.toList()),
            name = "Player",
        )

    private fun transaction(
        eventId: String?,
        timestamp: Long,
        timestamp2: Long = timestamp,
        occurrences: Int = 1,
        amount: Double = 10.0,
    ): Transaction =
        Transaction(
            type = Type.JOB,
            amount = amount,
            comment = "job",
            timestamp = timestamp,
            timestamp2 = timestamp2,
            source = EconomySource.JOBS,
            flow = EconomyFlow.MINT,
            currency = "vault",
            server = "spawn",
            origin = "test",
            occurrences = occurrences,
            eventId = eventId,
        )
}
