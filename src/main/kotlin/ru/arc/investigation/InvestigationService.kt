package ru.arc.investigation

import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random

sealed interface InvestigationStartResult {
    data class Started(val record: InvestigationJournalRecord) : InvestigationStartResult
    data class AlreadyActive(val record: InvestigationJournalRecord) : InvestigationStartResult
    data class Cooldown(val until: Long) : InvestigationStartResult
    data object Disabled : InvestigationStartResult
    data object Busy : InvestigationStartResult
    data object EconomyUnavailable : InvestigationStartResult
    data object InsufficientFunds : InvestigationStartResult
    data object JournalUnavailable : InvestigationStartResult
    data object PaymentFailed : InvestigationStartResult
    data object ManualReview : InvestigationStartResult
}

sealed interface InvestigationClueResult {
    data class Evidence(val record: InvestigationJournalRecord, val lines: List<String>, val firstRead: Boolean) : InvestigationClueResult
    data class Expired(val record: InvestigationJournalRecord) : InvestigationClueResult
    data object NoActiveCase : InvestigationClueResult
    data object Busy : InvestigationClueResult
    data object ManualReview : InvestigationClueResult
    data object UnknownWitness : InvestigationClueResult
    data object PersistenceFailure : InvestigationClueResult
}

sealed interface InvestigationVerdictResult {
    data class Success(val record: InvestigationJournalRecord) : InvestigationVerdictResult
    data class Wrong(val record: InvestigationJournalRecord) : InvestigationVerdictResult
    data class NeedClues(val collected: Int) : InvestigationVerdictResult
    data class Expired(val record: InvestigationJournalRecord) : InvestigationVerdictResult
    data object NoActiveCase : InvestigationVerdictResult
    data object Busy : InvestigationVerdictResult
    data object EconomyUnavailable : InvestigationVerdictResult
    data object ManualReview : InvestigationVerdictResult
    data object PersistenceFailure : InvestigationVerdictResult
}

class InvestigationService(
    private val journal: InvestigationJournal,
    private val wallet: InvestigationWallet,
    private val enabled: () -> Boolean,
    private val feeMinor: () -> Long,
    private val rewardMinor: () -> Long,
    private val duration: () -> Duration,
    private val cooldown: () -> Duration,
    private val caseGenerator: InvestigationCaseGenerator,
    private val runSync: ((() -> Unit) -> Unit),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val activeOperations = ConcurrentHashMap.newKeySet<UUID>()

    fun current(playerId: UUID): InvestigationJournalRecord? {
        expire(playerId)
        return journal.open(playerId)
    }

    fun latest(playerId: UUID): InvestigationJournalRecord? = journal.latest(playerId)

    fun balanceMinor(playerId: UUID): Long? = wallet.balanceMinor(playerId)

    fun start(
        playerId: UUID,
        bypassCooldown: Boolean = false,
    ): InvestigationStartResult {
        if (!enabled()) return InvestigationStartResult.Disabled
        if (!wallet.available) return InvestigationStartResult.EconomyUnavailable
        if (!activeOperations.add(playerId)) return InvestigationStartResult.Busy
        try {
            expire(playerId)
            journal.open(playerId)?.let { open ->
                return if (open.status == InvestigationStatus.ACTIVE) {
                    InvestigationStartResult.AlreadyActive(open)
                } else {
                    InvestigationStartResult.ManualReview
                }
            }
            val now = clock()
            if (!bypassCooldown) {
                journal.latest(playerId)?.cooldownUntil?.takeIf { it > now }?.let {
                    return InvestigationStartResult.Cooldown(it)
                }
            }
            val fee = feeMinor()
            val reward = rewardMinor()
            val record =
                InvestigationJournalRecord(
                    transactionId = UUID.randomUUID().toString(),
                    playerId = playerId.toString(),
                    case = caseGenerator.generate(random, journal.latest(playerId)?.case),
                    feeMinor = fee,
                    rewardMinor = reward,
                    createdAt = now,
                )
            if (!journal.persist(record)) return InvestigationStartResult.JournalUnavailable

            val balanceBefore = wallet.balanceMinor(playerId)
            if (balanceBefore == null) {
                persist(record.advance(InvestigationStatus.CANCELLED, "balance_unavailable"))
                return InvestigationStartResult.EconomyUnavailable
            }
            if (balanceBefore < fee) {
                persist(record.advance(InvestigationStatus.CANCELLED, "insufficient_funds"))
                return InvestigationStartResult.InsufficientFunds
            }
            val startedAt = nextTime(record.updatedAt)
            val started =
                record.copy(
                    status = InvestigationStatus.WITHDRAWAL_STARTED,
                    updatedAt = startedAt,
                    withdrawalStartedAt = startedAt,
                    feeBalanceBeforeMinor = balanceBefore,
                )
            if (!journal.persist(started)) return InvestigationStartResult.JournalUnavailable

            val money = wallet.withdraw(playerId, fee, feeReason(record), balanceBefore)
            val expectedAfter = balanceBefore - fee
            if (money.balanceAfterMinor != expectedAfter) {
                val exactUnchanged = money.balanceAfterMinor == balanceBefore && money.providerAccepted == false
                val result =
                    if (exactUnchanged) {
                        started.advance(InvestigationStatus.CANCELLED, money.failureCode ?: "provider_rejected")
                    } else {
                        started.copy(
                            status = InvestigationStatus.MANUAL_REVIEW,
                            updatedAt = nextTime(started.updatedAt),
                            feeBalanceAfterMinor = money.balanceAfterMinor,
                            evidence = safeEvidence(money.failureCode, "ambiguous_withdrawal"),
                        )
                    }
                persist(result)
                return if (exactUnchanged) InvestigationStartResult.PaymentFailed else InvestigationStartResult.ManualReview
            }

            val activeAt = max(clock(), nextTime(started.updatedAt))
            val active =
                started.copy(
                    status = InvestigationStatus.ACTIVE,
                    updatedAt = activeAt,
                    activeAt = activeAt,
                    expiresAt = Math.addExact(activeAt, duration().toMillis()),
                    cooldownUntil = Math.addExact(activeAt, cooldown().toMillis()),
                    feeBalanceAfterMinor = expectedAfter,
                    evidence = "exact_fee_balance_delta",
                )
            if (!journal.persist(active)) {
                persist(
                    started.copy(
                        status = InvestigationStatus.MANUAL_REVIEW,
                        updatedAt = activeAt,
                        feeBalanceAfterMinor = expectedAfter,
                        evidence = "active_persist_failed",
                    ),
                )
                return InvestigationStartResult.ManualReview
            }
            return InvestigationStartResult.Started(active)
        } finally {
            activeOperations.remove(playerId)
        }
    }

    fun collectClue(playerId: UUID, witness: InvestigationWitness): InvestigationClueResult {
        if (!activeOperations.add(playerId)) return InvestigationClueResult.Busy
        try {
            val record = activeRecord(playerId)
                ?: return if (journal.open(playerId)?.status == InvestigationStatus.MANUAL_REVIEW) {
                    InvestigationClueResult.ManualReview
                } else {
                    InvestigationClueResult.NoActiveCase
                }
            if (isExpired(record)) {
                val failed = failTimeout(record)
                    ?: return InvestigationClueResult.PersistenceFailure
                return InvestigationClueResult.Expired(failed)
            }
            val caseWitness = record.case.witness(witness.commandValue) ?: return InvestigationClueResult.UnknownWitness
            if (record.hasClue(caseWitness)) {
                return InvestigationClueResult.Evidence(record, record.case.testimony(caseWitness), false)
            }
            val updated =
                record.copy(
                    updatedAt = nextTime(record.updatedAt),
                    cluesMask = record.cluesMask or caseWitness.bit,
                    evidence = "clue_${caseWitness.commandValue}",
                )
            if (!journal.persist(updated)) return InvestigationClueResult.PersistenceFailure
            return InvestigationClueResult.Evidence(updated, updated.case.testimony(caseWitness), true)
        } finally {
            activeOperations.remove(playerId)
        }
    }

    fun submitVerdict(playerId: UUID, verdict: InvestigationVerdict): InvestigationVerdictResult {
        if (!activeOperations.add(playerId)) return InvestigationVerdictResult.Busy
        try {
            val record = activeRecord(playerId)
                ?: return if (journal.open(playerId)?.status == InvestigationStatus.MANUAL_REVIEW) {
                    InvestigationVerdictResult.ManualReview
                } else {
                    InvestigationVerdictResult.NoActiveCase
                }
            if (isExpired(record)) {
                val failed = failTimeout(record)
                    ?: return InvestigationVerdictResult.PersistenceFailure
                return InvestigationVerdictResult.Expired(failed)
            }
            if (record.clueCount() < MIN_CLUES) return InvestigationVerdictResult.NeedClues(record.clueCount())
            if (verdict != record.case.verdict) {
                val failed =
                    record.copy(
                        status = InvestigationStatus.FAILED,
                        updatedAt = nextTime(record.updatedAt),
                        submittedVerdict = verdict,
                        evidence = "wrong_verdict",
                    )
                if (!journal.persist(failed)) return InvestigationVerdictResult.PersistenceFailure
                return InvestigationVerdictResult.Wrong(failed)
            }

            val balanceBefore = wallet.balanceMinor(playerId) ?: return InvestigationVerdictResult.EconomyUnavailable
            val rewardAt = nextTime(record.updatedAt)
            val rewardStarted =
                record.copy(
                    status = InvestigationStatus.REWARD_STARTED,
                    updatedAt = rewardAt,
                    rewardStartedAt = rewardAt,
                    rewardBalanceBeforeMinor = balanceBefore,
                    submittedVerdict = verdict,
                    evidence = "reward_intent",
                )
            if (!journal.persist(rewardStarted)) return InvestigationVerdictResult.PersistenceFailure
            val money = wallet.deposit(playerId, record.rewardMinor, rewardReason(record), balanceBefore)
            val expectedAfter = balanceBefore + record.rewardMinor
            if (money.balanceAfterMinor != expectedAfter) {
                val manual =
                    rewardStarted.copy(
                        status = InvestigationStatus.MANUAL_REVIEW,
                        updatedAt = nextTime(rewardStarted.updatedAt),
                        rewardBalanceAfterMinor = money.balanceAfterMinor,
                        evidence = safeEvidence(money.failureCode, "ambiguous_reward"),
                    )
                persist(manual)
                return InvestigationVerdictResult.ManualReview
            }
            val completed =
                rewardStarted.copy(
                    status = InvestigationStatus.COMPLETED,
                    updatedAt = nextTime(rewardStarted.updatedAt),
                    rewardBalanceAfterMinor = expectedAfter,
                    evidence = "exact_reward_balance_delta",
                )
            if (!journal.persist(completed)) return InvestigationVerdictResult.ManualReview
            return InvestigationVerdictResult.Success(completed)
        } finally {
            activeOperations.remove(playerId)
        }
    }

    fun expireAll() {
        journal.records().filter { it.status == InvestigationStatus.ACTIVE && isExpired(it) }.forEach(::failTimeout)
    }

    fun recover(onManualReview: (InvestigationJournalRecord) -> Unit) {
        journal.records().filterNot { it.status.resolved }.forEach { record ->
            when (record.status) {
                InvestigationStatus.PREPARED -> persist(record.advance(InvestigationStatus.CANCELLED, "startup_cancelled"))
                InvestigationStatus.WITHDRAWAL_STARTED -> recoverWithdrawal(record, onManualReview)
                InvestigationStatus.ACTIVE -> if (isExpired(record)) failTimeout(record)
                InvestigationStatus.REWARD_STARTED -> recoverReward(record, onManualReview)
                InvestigationStatus.MANUAL_REVIEW -> {
                    when {
                        record.rewardStartedAt != null && record.rewardBalanceBeforeMinor != null -> recoverReward(record, onManualReview)
                        record.feeBalanceBeforeMinor != null && record.feeBalanceAfterMinor == record.feeBalanceBeforeMinor - record.feeMinor -> recoverPaidCase(record, onManualReview)
                        record.feeBalanceBeforeMinor != null -> recoverWithdrawal(record, onManualReview)
                        else -> onManualReview(record)
                    }
                }

                InvestigationStatus.FAILED,
                InvestigationStatus.COMPLETED,
                InvestigationStatus.CANCELLED,
                -> Unit
            }
        }
    }

    private fun recoverWithdrawal(record: InvestigationJournalRecord, onManualReview: (InvestigationJournalRecord) -> Unit) {
        val playerId = UUID.fromString(record.playerId)
        wallet.findTransaction(playerId, -record.feeMinor, feeReason(record), record.createdAt).whenComplete { history, failure ->
            runSync {
                if (failure != null || history?.found != true) {
                    markManual(record, if (history?.historyAvailable == true) "fee_not_found_in_history" else "fee_history_unavailable")
                        ?.let(onManualReview)
                    return@runSync
                }
                val before = requireNotNull(record.feeBalanceBeforeMinor)
                val proven =
                    record.copy(
                        status = InvestigationStatus.MANUAL_REVIEW,
                        updatedAt = nextTime(record.updatedAt),
                        feeBalanceAfterMinor = before - record.feeMinor,
                        evidence = "history_fee_found",
                    )
                recoverPaidCase(proven, onManualReview)
            }
        }
    }

    private fun recoverPaidCase(record: InvestigationJournalRecord, onManualReview: (InvestigationJournalRecord) -> Unit) {
        val activeAt = record.activeAt ?: requireNotNull(record.withdrawalStartedAt)
        val expiresAt = record.expiresAt ?: Math.addExact(activeAt, duration().toMillis())
        val cooldownUntil = record.cooldownUntil ?: Math.addExact(activeAt, cooldown().toMillis())
        val target = if (clock() >= expiresAt) InvestigationStatus.FAILED else InvestigationStatus.ACTIVE
        val recovered =
            record.copy(
                status = target,
                updatedAt = nextTime(record.updatedAt),
                activeAt = activeAt,
                expiresAt = expiresAt,
                cooldownUntil = cooldownUntil,
                evidence = if (target == InvestigationStatus.FAILED) "startup_timeout" else "startup_fee_recovered",
            )
        if (!persist(recovered)) onManualReview(record)
    }

    private fun recoverReward(record: InvestigationJournalRecord, onManualReview: (InvestigationJournalRecord) -> Unit) {
        val playerId = UUID.fromString(record.playerId)
        wallet.findTransaction(playerId, record.rewardMinor, rewardReason(record), record.createdAt).whenComplete { history, failure ->
            runSync {
                if (failure != null || history?.found != true) {
                    markManual(record, if (history?.historyAvailable == true) "reward_not_found_in_history" else "reward_history_unavailable")
                        ?.let(onManualReview)
                    return@runSync
                }
                val before = requireNotNull(record.rewardBalanceBeforeMinor)
                val completed =
                    record.copy(
                        status = InvestigationStatus.COMPLETED,
                        updatedAt = nextTime(record.updatedAt),
                        rewardBalanceAfterMinor = before + record.rewardMinor,
                        evidence = "history_reward_found",
                    )
                if (!persist(completed)) onManualReview(record)
            }
        }
    }

    private fun expire(playerId: UUID) {
        journal.open(playerId)?.takeIf { it.status == InvestigationStatus.ACTIVE && isExpired(it) }?.let(::failTimeout)
    }

    private fun activeRecord(playerId: UUID): InvestigationJournalRecord? = journal.open(playerId)?.takeIf { it.status == InvestigationStatus.ACTIVE }

    private fun isExpired(record: InvestigationJournalRecord): Boolean = record.expiresAt?.let { clock() >= it } ?: false

    private fun failTimeout(record: InvestigationJournalRecord): InvestigationJournalRecord? {
        if (record.status != InvestigationStatus.ACTIVE) return record.takeIf { it.status == InvestigationStatus.FAILED }
        val failed =
            record.copy(
                status = InvestigationStatus.FAILED,
                updatedAt = nextTime(record.updatedAt),
                evidence = "time_expired",
            )
        return failed.takeIf(::persist)
    }

    private fun markManual(record: InvestigationJournalRecord, evidence: String): InvestigationJournalRecord? {
        val manual =
            record.copy(
                status = InvestigationStatus.MANUAL_REVIEW,
                updatedAt = nextTime(record.updatedAt),
                evidence = evidence,
            )
        return manual.takeIf(::persist)
    }

    private fun persist(record: InvestigationJournalRecord): Boolean = runCatching { journal.persist(record) }.getOrDefault(false)

    private fun InvestigationJournalRecord.advance(status: InvestigationStatus, evidence: String): InvestigationJournalRecord =
        copy(status = status, updatedAt = nextTime(updatedAt), evidence = evidence)

    private fun nextTime(previous: Long): Long = max(clock(), previous + 1L)

    private fun feeReason(record: InvestigationJournalRecord): String = "arc-investigation:${record.transactionId}"

    private fun rewardReason(record: InvestigationJournalRecord): String = "arc-investigation-reward:${record.transactionId}"

    private fun safeEvidence(raw: String?, fallback: String): String =
        raw?.lowercase()?.takeIf { EVIDENCE_PATTERN.matches(it) } ?: fallback

    companion object {
        const val MIN_CLUES = 3
        private val EVIDENCE_PATTERN = Regex("[a-z0-9_:-]{1,180}")
    }
}
