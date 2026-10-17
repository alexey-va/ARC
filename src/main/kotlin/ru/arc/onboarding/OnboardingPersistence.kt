package ru.arc.onboarding

import ru.arc.core.BoundedAsyncExecutor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal data class PreparedOnboarding(
    val config: OnboardingConfig,
    val store: OnboardingStore?,
    val particleColors: Set<Int> = emptySet(),
)

/** Owns config/file preparation and every durable transition in admission order. */
internal class OnboardingPersistence(
    predecessor: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
    prepare: () -> PreparedOnboarding,
) {
    private val worker = BoundedAsyncExecutor("arc-onboarding")
    private val hints = ConcurrentHashMap<UUID, OnboardingHint>()
    private val deliveries = ConcurrentHashMap<UUID, OnboardingStore.DeliveryReceipt>()
    val ready: CompletableFuture<PreparedOnboarding> = worker.submit {
        predecessor.join() // Worker only. Reload cannot reopen an older disk snapshot.
        prepare().also { hints.putAll(it.store?.pendingHints().orEmpty()) }
    }

    fun nextHint(playerId: UUID): OnboardingHint? = hints[playerId]

    fun <T> submit(playerId: UUID, operation: (PreparedOnboarding) -> T): CompletableFuture<T> = worker.submit {
        val prepared = ready.join() // The initializer is first on this FIFO; failure remains visible.
        operation(prepared).also {
            val next = prepared.store?.nextHint(playerId)
            if (next == null) hints.remove(playerId) else hints[playerId] = next
        }
    }

    fun reserveDelivery(playerId: UUID, hint: OnboardingHint, now: Long): CompletableFuture<Boolean> =
        submit(playerId) { prepared ->
            val receipt = prepared.store?.reserveDelivery(playerId, hint, now) ?: return@submit false
            deliveries[playerId] = receipt
            true
        }

    /** Main-thread acknowledgement is memory only; no storage monitor is acquired. */
    fun acknowledgeDelivery(playerId: UUID) { deliveries.remove(playerId) }

    fun returnDelivery(playerId: UUID): CompletableFuture<Unit> = submit(playerId) { prepared ->
        deliveries[playerId]?.let { receipt ->
            prepared.store?.returnUndelivered(receipt)
            deliveries.remove(playerId, receipt)
        }
        Unit
    }

    fun closeAsync(): CompletableFuture<Unit> = worker.closeAsync {
        // Reserved terminal work runs after accepted writes, even when the queue was saturated.
        if (!ready.isCompletedExceptionally) {
            val store = ready.getNow(null)?.store
            deliveries.values.forEach { store?.returnUndelivered(it) }
        }
        deliveries.clear()
    }
}
