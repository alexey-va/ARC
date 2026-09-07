package ru.arc.audit

import java.util.UUID

/**
 * Small optional bridge for RusCrafting plugins that produce Vault changes.
 * Callers use reflection plus soft-depend, so ARC remains the sole owner of
 * audit persistence and metrics.
 */
object ExternalEconomyAuditBridge {
    private val externalSources = mapOf(
        "voting" to EconomySource.VOTING,
        "arcvotes" to EconomySource.VOTING,
        "ranks" to EconomySource.RANKS,
        "arcranks" to EconomySource.RANKS,
        "farms" to EconomySource.FARMS,
        "arcfarms" to EconomySource.FARMS,
    )

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
    fun markExternalReward(
        playerId: UUID,
        source: String,
        action: String,
        amount: Double,
        currency: String?,
        rewardId: String?,
    ): String? {
        val normalizedSource = externalSources[source.trim().lowercase()] ?: return null
        val normalizedAction = when (normalizedSource) {
            EconomySource.VOTING -> if (action.equals("vote_reward", ignoreCase = true)) "vote_reward" else return null
            EconomySource.RANKS -> if (action.equals("contract_reward", ignoreCase = true)) "contract_reward" else return null
            EconomySource.FARMS -> if (action.equals("farm_reward", ignoreCase = true)) "farm_reward" else return null
            else -> return null
        }
        if (!amount.isFinite() || amount <= 0.0) return null
        val now = System.currentTimeMillis()
        return EconomyPendingContextTracker.register(
            playerId = playerId,
            expectedAmount = amount,
            context = EconomyLedgerContext(
                requestedAmount = amount,
                action = normalizedAction,
                correlationId = rewardId?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
                capturedAt = now,
            ),
            now = now,
            source = normalizedSource,
            currency = currency,
        )
    }

    @JvmStatic
    fun cancel(playerId: UUID, token: String?) {
        token?.takeIf(String::isNotBlank)?.let { EconomyPendingContextTracker.cancel(playerId, it) }
    }
}
