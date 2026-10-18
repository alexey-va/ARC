package ru.arc.onboarding

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.economy.CurrencyTransactionEvidence
import ru.arc.economy.CurrencyWallet
import ru.arc.economy.minorToDouble
import ru.arc.economy.toProviderMinorOrNull
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.arc.util.Logging
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifetime stage/currency rewards, independent of quest runs and backend identity. Ledger
 * claims precede native wallet calls. A completed reconcile means every admitted attempt is
 * settled or retained for recovery; it is not a receipt that every reward was paid.
 * The caller owns the async ledger and shared Paper scope. Close that scope before awaiting
 * this controller's drain, then close the ledger. Neither close path waits on gameplay.
 */
internal class OnboardingRewards(
    private val ledger: OneTimeUseLedger,
    private val wallet: CurrencyWallet,
    private val tasks: LifecycleTaskScope,
    private val valid: (Player) -> Boolean,
    private val report: (UUID, UUID, String, Throwable?) -> Unit = { playerId, useId, reason, failure ->
        if (failure == null) Logging.warn("Onboarding reward retained: player={} use={} reason={}", playerId, useId, reason)
        else Logging.warn("Onboarding reward retained: player={} use={} reason={}", playerId, useId, reason, failure)
    },
) {
    private val monitor = Any()
    @Volatile private var active = true
    private val pending = mutableMapOf<UUID, CompletableFuture<Void>>()
    private val paid = ConcurrentHashMap.newKeySet<OneTimeUseIdentity>()
    private val held = ConcurrentHashMap.newKeySet<OneTimeUseIdentity>()
    private var drain: CompletableFuture<Void>? = null

    fun reconcile(player: Player, progress: OnboardingProgress, coinsMinor: Long, tokensMinor: Long): CompletableFuture<Void> {
        require(coinsMinor > 0L && tokensMinor > 0L) { "Onboarding rewards must be positive minor-unit amounts" }
        if (!active || !ledger.available) return completed()
        val playerId = player.uniqueId
        val attempts = REWARDED_STAGES.filter { it in progress.milestones }.flatMap { stage ->
            listOf("vault" to coinsMinor, "tokens" to tokensMinor).map { (currency, amount) ->
                admit(player, reward(playerId, stage, currency, amount))
            }
        }
        return CompletableFuture.allOf(*attempts.toTypedArray())
    }

    fun closeAsync(): CompletableFuture<Void> = synchronized(monitor) {
        active = false
        (drain ?: CompletableFuture.allOf(*pending.values.toTypedArray()).also { drain = it }).copy()
    }

    private fun admit(player: Player, reward: Reward): CompletableFuture<Void> {
        val result: CompletableFuture<Void>
        synchronized(monitor) {
            if (!active || reward.request.identity in paid || reward.request.identity in held) return completed()
            pending[reward.id]?.let { return it }
            result = CompletableFuture<Void>()
            pending[reward.id] = result
        }
        future { ledger.claim(reward.request) }.handle { claim, failure ->
            if (failure != null) hold(reward, null, "claim_outcome_unknown", failure)
            else handleClaim(player, reward, claim)
        }.thenCompose { it }.whenComplete { _, failure ->
            if (failure != null) {
                held += reward.request.identity
                diagnostic(reward, "controller_failure", failure)
            }
            synchronized(monitor) { pending.remove(reward.id, result) }
            result.complete(null)
        }
        return result
    }

    private fun handleClaim(player: Player, reward: Reward, result: OneTimeUseClaimResult?): CompletableFuture<Void> = when (result) {
        is OneTimeUseClaimResult.Acquired -> {
            val claim = result.claim
            when {
                claim.asRequest() != reward.request -> hold(reward, null, "claim_identity_mismatch")
                !claim.newlyCreated -> recover(player, reward, claim)
                else -> {
                    val providerAttempted = AtomicBoolean()
                    tasks.callSync { deposit(player, reward, providerAttempted) }.handle { payment, failure ->
                        when {
                            failure != null && providerAttempted.get() -> hold(reward, claim, "provider_threw", failure)
                            failure != null -> {
                                if (generateSequence(failure) { it.cause }.none { it is CancellationException }) {
                                    diagnostic(reward, "preflight_failed", failure)
                                }
                                release(reward, claim)
                            }
                            payment == Payment.APPLIED -> commit(reward, claim)
                            payment == Payment.NO_EFFECT -> release(reward, claim)
                            else -> hold(reward, claim, "provider_outcome_unknown")
                        }
                    }.thenCompose { it }
                }
            }
        }
        OneTimeUseClaimResult.AlreadyConsumed -> { paid += reward.request.identity; completed() }
        OneTimeUseClaimResult.Busy -> completed() // Another live holder owns the decision; a later reconcile may inspect it.
        OneTimeUseClaimResult.Missing -> hold(reward, null, "claim_missing")
        OneTimeUseClaimResult.IdentityConflict -> hold(reward, null, "identity_conflict")
        null -> hold(reward, null, "claim_result_missing")
    }

    /** Only this lifecycle-fenced Paper stage may call the monetary provider. */
    private fun deposit(player: Player, reward: Reward, providerAttempted: AtomicBoolean): Payment {
        if (!active || !player.isOnline || !valid(player)) return Payment.NO_EFFECT
        val selected = wallet.walletForCurrency(reward.currency)?.takeIf { it.available } ?: return Payment.NO_EFFECT
        if (reward.amount.minorToDouble().toProviderMinorOrNull() != reward.amount) return Payment.NO_EFFECT
        val before = selected.balanceMinor(reward.playerId) ?: return Payment.NO_EFFECT
        val expected = Math.addExact(before, reward.amount)
        providerAttempted.set(true)
        val evidence = selected.deposit(reward.playerId, reward.amount, reward.reason, before)
        return when {
            evidence.providerAccepted == true && evidence.balanceAfterMinor == expected -> Payment.APPLIED
            !evidence.providerCallAttempted || evidence.providerAccepted == false && evidence.balanceAfterMinor == before -> Payment.NO_EFFECT
            else -> Payment.UNKNOWN
        }
    }

    /** A retained claim may already have paid. Exact provider history is evidence, never permission to retry the deposit. */
    private fun recover(player: Player, reward: Reward, claim: OneTimeUseClaim): CompletableFuture<Void> =
        tasks.callSync {
            if (!active || !player.isOnline || !valid(player)) {
                CompletableFuture.completedFuture(CurrencyTransactionEvidence(null, false))
            } else {
                wallet.walletForCurrency(reward.currency)?.takeIf { it.available }
                    ?.findTransaction(reward.playerId, reward.amount, reward.reason, 0L)
                    ?: CompletableFuture.completedFuture(CurrencyTransactionEvidence(null, false))
            }
        }.thenCompose { it }.handle { evidence, failure ->
            if (failure == null && evidence != null && evidence.historyAvailable && evidence.found) commit(reward, claim)
            else hold(reward, claim, "recovery_requires_evidence", failure)
        }.thenCompose { it }

    private fun commit(reward: Reward, claim: OneTimeUseClaim): CompletableFuture<Void> =
        future { ledger.commit(claim) }.handle { result, failure ->
            if (failure == null && result in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                paid += reward.request.identity
                completed()
            } else hold(reward, claim, "commit_outcome_unknown", failure)
        }.thenCompose { it }

    private fun release(reward: Reward, claim: OneTimeUseClaim): CompletableFuture<Void> =
        future { ledger.release(claim) }.handle { result, failure ->
            if (failure == null && result in setOf(OneTimeUseReleaseResult.RELEASED, OneTimeUseReleaseResult.ALREADY_RELEASED)) completed()
            else hold(reward, claim, "release_outcome_unknown", failure)
        }.thenCompose { it }

    private fun hold(reward: Reward, claim: OneTimeUseClaim?, reason: String, failure: Throwable? = null): CompletableFuture<Void> {
        if (held.add(reward.request.identity)) diagnostic(reward, reason, failure)
        if (claim == null) return completed()
        return future { ledger.abandon(claim) }.handle { result, abandonFailure ->
            if (abandonFailure == null && result == OneTimeUseAbandonResult.ALREADY_COMMITTED) {
                paid += reward.request.identity
                held -= reward.request.identity
            } else if (abandonFailure != null || result != OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY) {
                diagnostic(reward, "abandon_outcome_unknown", abandonFailure)
            }
            null
        }
    }

    private fun diagnostic(reward: Reward, reason: String, failure: Throwable?) {
        runCatching { report(reward.playerId, reward.id, reason, failure) }
    }

    private data class Reward(val request: OneTimeUseClaimRequest, val currency: String, val amount: Long) {
        val id: UUID get() = request.identity.useId
        val playerId: UUID get() = request.claimantId
        val reason: String get() = "arc-reward:$id"
    }

    private enum class Payment { APPLIED, NO_EFFECT, UNKNOWN }

    companion object {
        private val REWARDED_STAGES = listOf(OnboardingMilestone.FIRST_RTP, OnboardingMilestone.HOME_CREATED,
            OnboardingMilestone.FOOTHOLD_COMPLETE, OnboardingMilestone.AUTOBUILD_COMPLETE)
        private val SCOPE = OneTimeUseScope.parse("onboarding")

        private fun reward(playerId: UUID, stage: OnboardingMilestone, currency: String, amount: Long): Reward {
            val useId = UUID.nameUUIDFromBytes("onboarding:v1:$playerId:${stage.id}:$currency".toByteArray(UTF_8))
            val fingerprint = OneTimeUseFingerprint.sha256Fields("onboarding:v1", playerId.toString(), stage.id, currency, amount.toString())
            return Reward(OneTimeUseClaimRequest(OneTimeUseIdentity(useId, fingerprint), useId, playerId, SCOPE), currency, amount)
        }

        private fun completed(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        private fun <T> future(action: () -> CompletableFuture<T>): CompletableFuture<T> =
            try { action() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
    }
}
