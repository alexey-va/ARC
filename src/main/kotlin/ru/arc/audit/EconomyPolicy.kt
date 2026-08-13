package ru.arc.audit

/** Fixed identifiers are safe for Prometheus labels and long-lived audit output. */
internal object EconomyPolicy {
    const val SLIMEFUN_BUY_ONLY = "slimefun_buy_only"

    fun isSlimefunBuyOnlyViolation(
        amount: Double,
        source: EconomySource,
        flow: EconomyFlow,
        context: EconomyLedgerContext?,
        eventTimestamp: Long,
        enabled: Boolean,
        activatedAt: Long,
    ): Boolean {
        if (!enabled || activatedAt <= 0L || eventTimestamp < activatedAt) return false
        if (!amount.isFinite() || amount <= 0.0 || flow != EconomyFlow.MINT) return false
        if (source !in setOf(EconomySource.SHOP, EconomySource.AUTOSELL)) return false
        if (context?.normalizedStatus != EconomyEventStatus.SUCCEEDED) return false
        return context.shopId.equals("Slimefun", ignoreCase = true) ||
            context.normalizedItems.any { item -> item.key?.startsWith("Slimefun.", ignoreCase = true) == true }
    }
}
