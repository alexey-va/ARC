package ru.arc.audit

import java.util.UUID

/**
 * Small optional bridge for RusCrafting plugins that produce Vault changes.
 * Callers use reflection plus soft-depend, so ARC remains the sole owner of
 * audit persistence and metrics.
 */
object ExternalEconomyAuditBridge {
    @JvmStatic
    fun markJobReward(
        playerId: UUID,
        job: String,
        amount: Double,
    ): String? {
        if (!amount.isFinite() || amount <= 0.0 || job.isBlank()) return null
        val now = System.currentTimeMillis()
        return EconomyPendingContextTracker.register(
            playerId = playerId,
            expectedAmount = amount,
            context =
                EconomyLedgerContext(
                    requestedAmount = amount,
                    action = EconomyAction.JOB_REWARD.label,
                    jobBreakdown =
                        listOf(
                            EconomyJobRewardComponent(
                                job = job.lowercase().take(48),
                                activity = "payout",
                                origin = "arcecojobs",
                                amount = amount,
                                occurrences = 1,
                            ),
                        ),
                    capturedAt = now,
                ),
            now = now,
            source = EconomySource.JOBS,
        )
    }

    @JvmStatic
    fun cancel(playerId: UUID, token: String?) {
        token?.takeIf(String::isNotBlank)?.let { EconomyPendingContextTracker.cancel(playerId, it) }
    }
}
