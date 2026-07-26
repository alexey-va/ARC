package ru.arc.jobs.guis

import java.util.concurrent.CompletableFuture

internal enum class BoostPurchaseResult {
    PURCHASED,
    ALREADY_OWNED,
    NO_PERMISSION,
    INSUFFICIENT_FUNDS,
    PAYMENT_FAILED,
    UNAVAILABLE,
}

/**
 * Coordinates the async boost reservation with the main-thread currency charge.
 *
 * Reserving first prevents racing clicks from both paying for one unique boost.
 * A failed charge releases the reservation before the result is exposed.
 */
internal class BoostPurchaseCoordinator(
    private val alreadyOwned: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val hasFunds: () -> Boolean,
    private val reserveBoost: () -> CompletableFuture<Boolean>,
    private val chargeCurrency: () -> Boolean,
    private val rollbackBoost: () -> CompletableFuture<Boolean>,
    private val runOnMainThread: ((() -> Unit) -> Unit),
) {
    fun purchase(): CompletableFuture<BoostPurchaseResult> {
        val precheck =
            try {
                when {
                    alreadyOwned() -> BoostPurchaseResult.ALREADY_OWNED
                    !hasPermission() -> BoostPurchaseResult.NO_PERMISSION
                    !hasFunds() -> BoostPurchaseResult.INSUFFICIENT_FUNDS
                    else -> null
                }
            } catch (_: Exception) {
                BoostPurchaseResult.UNAVAILABLE
            }
        if (precheck != null) return CompletableFuture.completedFuture(precheck)

        val result = CompletableFuture<BoostPurchaseResult>()
        val reservation =
            try {
                reserveBoost()
            } catch (_: Exception) {
                result.complete(BoostPurchaseResult.UNAVAILABLE)
                return result
            }

        reservation.whenComplete { reserved, reservationFailure ->
            when {
                reservationFailure != null ->
                    result.complete(BoostPurchaseResult.UNAVAILABLE)
                reserved != true ->
                    result.complete(BoostPurchaseResult.ALREADY_OWNED)
                else ->
                    dispatchCharge(result)
            }
        }
        return result
    }

    private fun dispatchCharge(result: CompletableFuture<BoostPurchaseResult>) {
        try {
            runOnMainThread {
                val charged =
                    try {
                        hasPermission() && chargeCurrency()
                    } catch (_: Exception) {
                        false
                    }
                if (charged) {
                    result.complete(BoostPurchaseResult.PURCHASED)
                } else {
                    rollbackThenComplete(result)
                }
            }
        } catch (_: Exception) {
            rollbackThenComplete(result)
        }
    }

    private fun rollbackThenComplete(result: CompletableFuture<BoostPurchaseResult>) {
        val rollback =
            try {
                rollbackBoost()
            } catch (_: Exception) {
                result.complete(BoostPurchaseResult.PAYMENT_FAILED)
                return
            }
        rollback.whenComplete { _, _ ->
            result.complete(BoostPurchaseResult.PAYMENT_FAILED)
        }
    }
}
