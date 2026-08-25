package ru.arc.contracts

data class ContractQuantitySelection(
    val minimum: Int,
    val maximum: Int,
    val selected: Int,
    val payoutMinor: Long,
) {
    val canSubmit: Boolean get() = selected >= minimum && maximum >= minimum
}

/** Pure quantity model shared by the menu and its regression tests. */
object ContractQuantitySelector {
    fun select(
        view: ResourceContractPlayerView,
        availableItems: Int,
        requested: Int? = null,
    ): ContractQuantitySelection {
        val contract = view.contract
        val budgetUnits =
            (contract.budgetMinor - contract.spentMinor - contract.reservedMinor)
                .coerceAtLeast(0L) / contract.payoutMinorPerUnit
        val maximum =
            minOf(
                availableItems.coerceAtLeast(0).toLong(),
                view.maxSubmissionQuantity.toLong(),
                view.playerRemainingQuantity,
                contract.remainingQuantity,
                budgetUnits,
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val selected =
            if (maximum < view.minSubmissionQuantity) {
                0
            } else {
                (requested ?: maximum).coerceIn(view.minSubmissionQuantity, maximum)
            }
        return ContractQuantitySelection(
            minimum = view.minSubmissionQuantity,
            maximum = maximum,
            selected = selected,
            payoutMinor = Math.multiplyExact(selected.toLong(), contract.payoutMinorPerUnit),
        )
    }

    fun decrease(selection: ContractQuantitySelection, jumpToMinimum: Boolean): Int =
        if (jumpToMinimum) {
            selection.minimum
        } else {
            (selection.selected - selection.minimum).coerceAtLeast(selection.minimum)
        }

    fun increase(selection: ContractQuantitySelection, jumpToMaximum: Boolean): Int =
        if (jumpToMaximum) {
            selection.maximum
        } else {
            (selection.selected + selection.minimum).coerceAtMost(selection.maximum)
        }

    fun adjust(
        selection: ContractQuantitySelection,
        decrease: Boolean,
        jumpToBoundary: Boolean,
    ): Int =
        if (decrease) {
            decrease(selection, jumpToBoundary)
        } else {
            increase(selection, jumpToBoundary)
        }
}
