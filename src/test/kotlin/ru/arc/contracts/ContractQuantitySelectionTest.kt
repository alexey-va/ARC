package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractQuantitySelectionTest : StringSpec({
    "bounds the initial selection by inventory quota target and budget" {
        val selection =
            ContractQuantitySelector.select(
                view(
                    remaining = 900,
                    playerRemaining = 700,
                    maxSubmission = 512,
                    unspentBudgetMinor = 30_000,
                ),
                availableItems = 800,
            )

        selection.minimum shouldBe 32
        selection.maximum shouldBe 300
        selection.selected shouldBe 300
        selection.payoutMinor shouldBe 30_000L
        selection.canSubmit shouldBe true
    }

    "disables confirmation below the minimum plain-item batch" {
        val selection = ContractQuantitySelector.select(view(), availableItems = 31)

        selection.maximum shouldBe 31
        selection.selected shouldBe 0
        selection.payoutMinor shouldBe 0L
        selection.canSubmit shouldBe false
    }

    "quantity controls step by the minimum and clamp to both ends" {
        val selection = ContractQuantitySelector.select(view(maxSubmission = 100), availableItems = 100, requested = 64)

        ContractQuantitySelector.decrease(selection, jumpToMinimum = false) shouldBe 32
        ContractQuantitySelector.decrease(selection, jumpToMinimum = true) shouldBe 32
        ContractQuantitySelector.increase(selection, jumpToMaximum = false) shouldBe 96
        ContractQuantitySelector.increase(selection, jumpToMaximum = true) shouldBe 100
    }
})

private fun view(
    remaining: Long = 1_000,
    playerRemaining: Long = 1_000,
    maxSubmission: Int = 512,
    unspentBudgetMinor: Long = 100_000,
): ResourceContractPlayerView =
    ResourceContractPlayerView(
        contract =
            ResourceContractView(
                id = "forge_test",
                displayName = "Тестовый заказ",
                itemKey = "minecraft:raw_iron",
                funding = "server_envelope",
                status = "open",
                windowStartsAt = 1L,
                windowEndsAt = Long.MAX_VALUE,
                payoutMinorPerUnit = 100L,
                budgetMinor = unspentBudgetMinor,
                spentMinor = 0L,
                reservedMinor = 0L,
                targetQuantity = 1_000L,
                acceptedQuantity = 1_000L - remaining,
                reservedQuantity = 0L,
                remainingQuantity = remaining,
                contributors = 0,
                group = "forge_orders",
            ),
        minSubmissionQuantity = 32,
        maxSubmissionQuantity = maxSubmission,
        perPlayerQuantityCap = 1_000L,
        playerAcceptedQuantity = 1_000L - playerRemaining,
        playerReservedQuantity = 0L,
        playerRemainingQuantity = playerRemaining,
    )
