package ru.arc.jobs.guis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class BoostPurchaseCoordinatorTest :
    FreeSpec({
        "permission denial does not reserve or charge" {
            var reservations = 0
            var charges = 0
            val coordinator =
                coordinator(
                    hasPermission = { false },
                    reserve = {
                        reservations++
                        CompletableFuture.completedFuture(true)
                    },
                    charge = {
                        charges++
                        true
                    },
                )

            coordinator.purchase().join() shouldBe BoostPurchaseResult.NO_PERMISSION
            reservations shouldBeExactly 0
            charges shouldBeExactly 0
        }

        "insufficient funds do not reserve a boost" {
            var reservations = 0
            val coordinator =
                coordinator(
                    hasFunds = { false },
                    reserve = {
                        reservations++
                        CompletableFuture.completedFuture(true)
                    },
                )

            coordinator.purchase().join() shouldBe BoostPurchaseResult.INSUFFICIENT_FUNDS
            reservations shouldBeExactly 0
        }

        "failed reservation never charges currency" {
            var charges = 0
            val coordinator =
                coordinator(
                    reserve = { CompletableFuture.completedFuture(false) },
                    charge = {
                        charges++
                        true
                    },
                )

            coordinator.purchase().join() shouldBe BoostPurchaseResult.ALREADY_OWNED
            charges shouldBeExactly 0
        }

        "reservation failure returns unavailable without charging" {
            var charges = 0
            val coordinator =
                coordinator(
                    reserve = {
                        CompletableFuture.failedFuture(IllegalStateException("redis offline"))
                    },
                    charge = {
                        charges++
                        true
                    },
                )

            coordinator.purchase().join() shouldBe BoostPurchaseResult.UNAVAILABLE
            charges shouldBeExactly 0
        }

        "payment failure rolls reservation back before completing" {
            var rollbacks = 0
            val rollback = CompletableFuture<Boolean>()
            val coordinator =
                coordinator(
                    charge = { false },
                    rollback = {
                        rollbacks++
                        rollback
                    },
                )

            val result = coordinator.purchase()
            result.isDone.shouldBeFalse()
            rollbacks shouldBeExactly 1

            rollback.complete(true)

            result.join() shouldBe BoostPurchaseResult.PAYMENT_FAILED
        }

        "successful purchase charges exactly once without rollback" {
            var charges = 0
            var rollbacks = 0
            val coordinator =
                coordinator(
                    charge = {
                        charges++
                        true
                    },
                    rollback = {
                        rollbacks++
                        CompletableFuture.completedFuture(true)
                    },
                )

            coordinator.purchase().join() shouldBe BoostPurchaseResult.PURCHASED
            charges shouldBeExactly 1
            rollbacks shouldBeExactly 0
        }

        "two racing purchases charge only the successful reservation" {
            val reserved = AtomicBoolean()
            var charges = 0
            val coordinator =
                coordinator(
                    reserve = {
                        CompletableFuture.completedFuture(reserved.compareAndSet(false, true))
                    },
                    charge = {
                        charges++
                        true
                    },
                )

            val results = listOf(coordinator.purchase().join(), coordinator.purchase().join())

            results.shouldContainExactlyInAnyOrder(
                BoostPurchaseResult.PURCHASED,
                BoostPurchaseResult.ALREADY_OWNED,
            )
            charges shouldBeExactly 1
        }

        "currency charge is dispatched through the main-thread boundary" {
            var pendingMainAction: (() -> Unit)? = null
            val coordinator =
                coordinator(
                    runOnMain = { action -> pendingMainAction = action },
                )

            val result = coordinator.purchase()

            result.isDone.shouldBeFalse()
            checkNotNull(pendingMainAction).invoke()
            result.join() shouldBe BoostPurchaseResult.PURCHASED
            result.isDone.shouldBeTrue()
        }
    }) {
    companion object {
        private fun coordinator(
            alreadyOwned: () -> Boolean = { false },
            hasPermission: () -> Boolean = { true },
            hasFunds: () -> Boolean = { true },
            reserve: () -> CompletableFuture<Boolean> = {
                CompletableFuture.completedFuture(true)
            },
            charge: () -> Boolean = { true },
            rollback: () -> CompletableFuture<Boolean> = {
                CompletableFuture.completedFuture(true)
            },
            runOnMain: ((() -> Unit) -> Unit) = { it() },
        ) = BoostPurchaseCoordinator(
            alreadyOwned = alreadyOwned,
            hasPermission = hasPermission,
            hasFunds = hasFunds,
            reserveBoost = reserve,
            chargeCurrency = charge,
            rollbackBoost = rollback,
            runOnMainThread = runOnMain,
        )
    }
}
