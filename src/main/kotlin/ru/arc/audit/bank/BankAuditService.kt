package ru.arc.audit.bank

import ru.arc.metrics.core.MetricPoint
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

data class BankAuditAccount(
    val playerId: String,
    val player: String?,
    val walletBalance: Double,
    val bankBalance: Double,
    val pendingInterest: Double,
    val lastSeenAt: Long? = null,
) {
    val bankSupply: Double get() = bankBalance + pendingInterest
    val knownSupply: Double get() = walletBalance + bankSupply
}

data class BankAuditReadResult(
    val discoveredAccounts: Int,
    val accounts: List<BankAuditAccount>,
    val failedAccounts: Int = 0,
    val capped: Boolean = false,
    val activity: BankAuditActivityRead = BankAuditActivityRead(),
) {
    val complete: Boolean
        get() = !capped && failedAccounts == 0 && accounts.size == discoveredAccounts
}

data class BankAuditActivityRead(
    val collectionSucceeded: Boolean = false,
    val coverageStartedAt: Long? = null,
    val registryPlayers: Int = 0,
    val invalidEntries: Int = 0,
)

data class ActiveSupplyCohort(
    val windowDays: Int,
    val accounts: Int,
    val walletSupply: Double,
    val bankSupply: Double,
    val knownSupply: Double,
    val knownSupplyShare: Double,
    val maturityRatio: Double,
    val complete: Boolean,
)

/** Inferred from two complete account snapshots; labels explicitly retain the observed/inferred nature. */
enum class BankAuditChangeType(val label: String) {
    OBSERVED_TRANSFER_TO_BANK("observed_transfer_to_bank"),
    OBSERVED_TRANSFER_FROM_BANK("observed_transfer_from_bank"),
    OBSERVED_INTEREST_ACCRUAL("observed_interest_accrual"),
    OBSERVED_INTEREST_CAPITALIZATION("observed_interest_capitalization"),
    OBSERVED_PENDING_INTEREST_REDUCTION("observed_pending_interest_reduction"),
    UNEXPLAINED_SUPPLY_INCREASE("unexplained_supply_increase"),
    UNEXPLAINED_SUPPLY_DECREASE("unexplained_supply_decrease"),
    MIXED_CHANGE("mixed_change"),
}

data class BankAuditChange(
    val timestamp: Long,
    val playerId: String,
    val player: String?,
    val before: Double,
    val after: Double,
    val delta: Double,
    val walletDelta: Double,
    val bankBalanceDelta: Double,
    val pendingInterestDelta: Double,
    val knownSupplyDelta: Double,
    val classification: BankAuditChangeType,
)

data class BankAuditSnapshot(
    val timestamp: Long,
    val complete: Boolean,
    val discoveredAccounts: Int,
    val sampledAccounts: Int,
    val failedAccounts: Int,
    val capped: Boolean,
    val positiveBankAccounts: Int,
    val walletSupply: Double,
    val bankBalance: Double,
    val pendingInterest: Double,
    val bankSupply: Double,
    val knownSupply: Double,
    val bankSupplyDelta: Double?,
    val walletSupplyDelta: Double?,
    val knownSupplyDelta: Double?,
    val observedBankIncrease: Double,
    val observedBankDecrease: Double,
    val observedPendingInterestIncrease: Double,
    val changedAccounts: Int,
    val classifiedChanges: Int,
    val changeTypes: Map<String, Int>,
    val bankQuantiles: Map<String, Double>,
    val bankConcentration: Map<String, Double>,
    val knownConcentration: Map<String, Double>,
    val activityCollectionSucceeded: Boolean,
    val activityCoverageStartedAt: Long?,
    val activityRegistryPlayers: Int,
    val activityInvalidEntries: Int,
    val activityMatchedAccounts: Int,
    val activeSupplyCohorts: List<ActiveSupplyCohort>,
    val topBankAccounts: List<BankAuditAccount>,
    val topKnownAccounts: List<BankAuditAccount>,
)

/**
 * Converts periodic Bank snapshots into low-cardinality metrics and bounded
 * authenticated evidence. No player identity is exported to Prometheus.
 */
class BankAuditService(
    private val config: BankAuditConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var latestSnapshot: BankAuditSnapshot? = null

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastFailureAt: Long? = null

    private var lastCompleteSnapshot: BankAuditSnapshot? = null
    private var previousAccounts = emptyMap<String, BankAuditAccount>()
    private val recentChanges = ArrayDeque<BankAuditChange>()

    @Synchronized
    fun accept(read: BankAuditReadResult): BankAuditSnapshot {
        val now = clock()
        val accounts = read.accounts.filter(::validAccount)
        val invalidAccounts = read.accounts.size - accounts.size
        val failedAccounts = read.failedAccounts + invalidAccounts
        val complete = read.complete && invalidAccounts == 0
        val current = accounts.associateBy(BankAuditAccount::playerId)
        val changes = mutableListOf<BankAuditChange>()

        if (complete && previousAccounts.isNotEmpty()) {
            current.forEach { (playerId, account) ->
                val previous = previousAccounts[playerId] ?: return@forEach
                val delta = account.bankSupply - previous.bankSupply
                val walletDelta = account.walletBalance - previous.walletBalance
                val bankBalanceDelta = account.bankBalance - previous.bankBalance
                val pendingInterestDelta = account.pendingInterest - previous.pendingInterest
                val knownSupplyDelta = account.knownSupply - previous.knownSupply
                // Wallet movement alone belongs to the main ledger, not the Bank activity stream.
                if (max(abs(bankBalanceDelta), abs(pendingInterestDelta)) < changeThreshold()) {
                    return@forEach
                }
                changes +=
                    BankAuditChange(
                        timestamp = now,
                        playerId = playerId,
                        player = account.player ?: previous.player,
                        before = previous.bankSupply,
                        after = account.bankSupply,
                        delta = delta,
                        walletDelta = walletDelta,
                        bankBalanceDelta = bankBalanceDelta,
                        pendingInterestDelta = pendingInterestDelta,
                        knownSupplyDelta = knownSupplyDelta,
                        classification = classifyChange(walletDelta, bankBalanceDelta, pendingInterestDelta),
                    )
            }
        }

        changes
            .sortedBy(::changeMagnitude)
            .forEach(recentChanges::addLast)
        while (recentChanges.size > config.recentChanges) recentChanges.removeFirst()

        val walletSupply = accounts.sumOf(BankAuditAccount::walletBalance)
        val bankBalance = accounts.sumOf(BankAuditAccount::bankBalance)
        val pendingInterest = accounts.sumOf(BankAuditAccount::pendingInterest)
        val bankSupply = bankBalance + pendingInterest
        val knownSupply = walletSupply + bankSupply
        val previous = lastCompleteSnapshot?.takeIf { complete }
        val positiveBankAccounts = accounts.filter { it.bankSupply > 0.0 }
        val bankValues = positiveBankAccounts.map(BankAuditAccount::bankSupply).sorted()
        val activityCoverageStartedAt =
            read.activity.coverageStartedAt?.takeIf { it in 1..(now + FUTURE_TIMESTAMP_TOLERANCE_MILLIS) }
        val validActivityAccounts =
            accounts.filter { account ->
                account.lastSeenAt?.let { it in 1..(now + FUTURE_TIMESTAMP_TOLERANCE_MILLIS) } == true
            }
        val invalidAccountActivity = accounts.count { it.lastSeenAt != null } - validActivityAccounts.size
        val activeSupplyCohorts =
            ACTIVE_WINDOWS_DAYS.map { windowDays ->
                activeSupplyCohort(
                    accounts = validActivityAccounts,
                    windowDays = windowDays,
                    now = now,
                    coverageStartedAt = activityCoverageStartedAt,
                    totalKnownSupply = knownSupply,
                    activityRead = read.activity,
                    invalidAccountActivity = invalidAccountActivity,
                )
            }

        val snapshot =
            BankAuditSnapshot(
                timestamp = now,
                complete = complete,
                discoveredAccounts = read.discoveredAccounts,
                sampledAccounts = accounts.size,
                failedAccounts = failedAccounts,
                capped = read.capped,
                positiveBankAccounts = positiveBankAccounts.size,
                walletSupply = walletSupply,
                bankBalance = bankBalance,
                pendingInterest = pendingInterest,
                bankSupply = bankSupply,
                knownSupply = knownSupply,
                bankSupplyDelta = previous?.let { bankSupply - it.bankSupply },
                walletSupplyDelta = previous?.let { walletSupply - it.walletSupply },
                knownSupplyDelta = previous?.let { knownSupply - it.knownSupply },
                observedBankIncrease = changes.asSequence().filter { it.delta > 0.0 }.sumOf(BankAuditChange::delta),
                observedBankDecrease = -changes.asSequence().filter { it.delta < 0.0 }.sumOf(BankAuditChange::delta),
                observedPendingInterestIncrease =
                    changes
                        .asSequence()
                        .map(BankAuditChange::pendingInterestDelta)
                        .filter { it > 0.0 }
                        .sum(),
                changedAccounts = changes.count { abs(it.delta) >= changeThreshold() },
                classifiedChanges = changes.size,
                changeTypes =
                    changes.groupingBy { it.classification.label }
                        .eachCount()
                        .toSortedMap(),
                bankQuantiles =
                    linkedMapOf(
                        "0.50" to quantile(bankValues, 0.50),
                        "0.90" to quantile(bankValues, 0.90),
                        "0.99" to quantile(bankValues, 0.99),
                    ),
                bankConcentration = concentration(positiveBankAccounts.map(BankAuditAccount::bankSupply)),
                knownConcentration = concentration(accounts.map(BankAuditAccount::knownSupply)),
                activityCollectionSucceeded = read.activity.collectionSucceeded,
                activityCoverageStartedAt = activityCoverageStartedAt,
                activityRegistryPlayers = read.activity.registryPlayers,
                activityInvalidEntries = read.activity.invalidEntries + invalidAccountActivity,
                activityMatchedAccounts = validActivityAccounts.size,
                activeSupplyCohorts = activeSupplyCohorts,
                topBankAccounts = accounts.sortedByDescending(BankAuditAccount::bankSupply).take(config.topAccounts),
                topKnownAccounts = accounts.sortedByDescending(BankAuditAccount::knownSupply).take(config.topAccounts),
            )

        if (complete) {
            previousAccounts = current
            lastCompleteSnapshot = snapshot
        }
        latestSnapshot = snapshot
        lastFailure = null
        lastFailureAt = null
        return snapshot
    }

    fun recordFailure(failure: Throwable) {
        lastFailure = failure.javaClass.simpleName.take(80)
        lastFailureAt = clock()
    }

    fun latest(): BankAuditSnapshot? = latestSnapshot

    @Synchronized
    fun summary(limit: Int): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, 100)
        val snapshot = latestSnapshot
            ?: return linkedMapOf(
                "status" to if (lastFailure == null) "warming_up" else "error",
                "lastFailure" to lastFailure,
                "lastFailureAt" to lastFailureAt,
            )

        return linkedMapOf(
            "status" to if (snapshot.complete) "ready" else "partial",
            "timestamp" to snapshot.timestamp,
            "lastCompleteTimestamp" to lastCompleteSnapshot?.timestamp,
            "complete" to snapshot.complete,
            "accounts" to
                linkedMapOf(
                    "discovered" to snapshot.discoveredAccounts,
                    "sampled" to snapshot.sampledAccounts,
                    "failed" to snapshot.failedAccounts,
                    "positiveBankBalance" to snapshot.positiveBankAccounts,
                    "supplyChanged" to snapshot.changedAccounts,
                    "classifiedChanges" to snapshot.classifiedChanges,
                    "capped" to snapshot.capped,
                ),
            "money" to
                linkedMapOf(
                    "wallet" to snapshot.walletSupply,
                    "bankBalance" to snapshot.bankBalance,
                    "pendingInterest" to snapshot.pendingInterest,
                    "bankSupply" to snapshot.bankSupply,
                    "knownSupply" to snapshot.knownSupply,
                    "bankSupplyDelta" to snapshot.bankSupplyDelta,
                    "walletSupplyDelta" to snapshot.walletSupplyDelta,
                    "knownSupplyDelta" to snapshot.knownSupplyDelta,
                    "observedBankIncrease" to snapshot.observedBankIncrease,
                    "observedBankDecrease" to snapshot.observedBankDecrease,
                    "observedPendingInterestIncrease" to snapshot.observedPendingInterestIncrease,
                ),
            "changeTypes" to snapshot.changeTypes,
            "distribution" to
                linkedMapOf(
                    "bankQuantiles" to snapshot.bankQuantiles,
                    "bankConcentration" to snapshot.bankConcentration,
                    "knownConcentration" to snapshot.knownConcentration,
                ),
            "activity" to
                linkedMapOf(
                    "evidence" to "velocity_authenticated_session_and_heartbeat",
                    "collectionSucceeded" to snapshot.activityCollectionSucceeded,
                    "coverageStartedAt" to snapshot.activityCoverageStartedAt,
                    "registryPlayers" to snapshot.activityRegistryPlayers,
                    "invalidEntries" to snapshot.activityInvalidEntries,
                    "matchedMoneyAccounts" to snapshot.activityMatchedAccounts,
                    "moneyAccountCoverageRatio" to activityAccountCoverage(snapshot),
                    "cohorts" to snapshot.activeSupplyCohorts.map(::cohortMap),
                ),
            "topBankAccounts" to snapshot.topBankAccounts.take(safeLimit).map(::accountMap),
            "topKnownAccounts" to snapshot.topKnownAccounts.take(safeLimit).map(::accountMap),
            "recentBankChanges" to recentChanges.toList().takeLast(safeLimit).reversed().map(::changeMap),
            "lastFailure" to lastFailure,
            "lastFailureAt" to lastFailureAt,
        )
    }

    fun metricPoints(snapshot: BankAuditSnapshot): List<MetricPoint> = metricPoints(snapshot, collectionSucceeded = true)

    fun failureMetricPoints(): List<MetricPoint> = metricPoints(attempt = null, collectionSucceeded = false)

    private fun metricPoints(
        attempt: BankAuditSnapshot?,
        collectionSucceeded: Boolean,
    ): List<MetricPoint> =
        buildList {
            val completeSnapshot = attempt?.takeIf(BankAuditSnapshot::complete) ?: lastCompleteSnapshot
            val attemptTimestamp = attempt?.timestamp ?: lastFailureAt ?: clock()
            add(point("arc_bank_audit_collector", "Active single-leader Bank audit collector", 1.0))
            add(point("arc_bank_collection_success", "Whether the latest Bank collection attempt completed without a fatal error", bool(collectionSucceeded)))
            add(point("arc_bank_snapshot_complete", "Whether the latest Bank collection covered every discovered account", bool(collectionSucceeded && attempt?.complete == true)))
            add(point("arc_bank_last_attempt_timestamp_seconds", "Unix timestamp of the latest Bank collection attempt", attemptTimestamp / 1000.0))
            add(point("arc_bank_expected_max_lag_seconds", "Expected maximum Bank DB lag for players active on another server", config.expectedMaxLagSeconds.toDouble()))
            add(point("arc_bank_snapshot_coverage_ratio", "Share of discovered accounts included in the latest Bank collection", attempt?.let(::coverage) ?: 0.0))
            add(accountPoint("discovered", attempt?.discoveredAccounts ?: 0))
            add(accountPoint("sampled", attempt?.sampledAccounts ?: 0))
            add(accountPoint("failed", attempt?.failedAccounts ?: 0))
            add(point("arc_bank_activity_collection_success", "Whether the latest network player activity hash read succeeded", bool(attempt?.activityCollectionSucceeded == true)))
            completeSnapshot ?: return@buildList
            add(point("arc_bank_snapshot_timestamp_seconds", "Unix timestamp of the latest complete Bank snapshot", completeSnapshot.timestamp / 1000.0))
            add(accountPoint("positive_bank_balance", completeSnapshot.positiveBankAccounts))
            add(moneyPoint("wallet", completeSnapshot.walletSupply))
            add(moneyPoint("bank_balance", completeSnapshot.bankBalance))
            add(moneyPoint("pending_interest", completeSnapshot.pendingInterest))
            add(moneyPoint("bank_supply", completeSnapshot.bankSupply))
            add(moneyPoint("known_supply", completeSnapshot.knownSupply))
            add(point("arc_bank_activity_registry_players", "Players recorded by the Velocity network activity registry", completeSnapshot.activityRegistryPlayers.toDouble()))
            add(point("arc_bank_activity_invalid_entries", "Invalid entries observed in the network activity registry or matched money accounts", completeSnapshot.activityInvalidEntries.toDouble()))
            add(point("arc_bank_activity_matched_accounts", "Money accounts matched to valid Velocity network activity evidence", completeSnapshot.activityMatchedAccounts.toDouble()))
            add(point("arc_bank_activity_account_coverage_ratio", "Share of sampled money accounts matched to valid Velocity network activity evidence", activityAccountCoverage(completeSnapshot)))
            completeSnapshot.activityCoverageStartedAt?.let {
                add(point("arc_bank_activity_coverage_started_timestamp_seconds", "Unix timestamp when Velocity network activity observation began", it / 1000.0))
            }
            completeSnapshot.activeSupplyCohorts.forEach { cohort ->
                val window = mapOf("window_days" to cohort.windowDays.toString())
                add(MetricPoint("arc_bank_activity_window_maturity_ratio", "Observed share of the requested active-supply window since Velocity tracking began", cohort.maturityRatio, window))
                add(MetricPoint("arc_bank_activity_window_complete", "Whether the active-supply window has fully matured without invalid activity evidence", bool(cohort.complete), window))
                add(MetricPoint("arc_bank_active_accounts", "Money accounts active on the network within the requested window", cohort.accounts.toDouble(), window))
                add(activeSupplyPoint(cohort, "wallet", cohort.walletSupply))
                add(activeSupplyPoint(cohort, "bank_supply", cohort.bankSupply))
                add(activeSupplyPoint(cohort, "known_supply", cohort.knownSupply))
                add(MetricPoint("arc_bank_active_known_supply_share_ratio", "Share of complete known money supply held by accounts active in the requested window", cohort.knownSupplyShare, window))
            }
            completeSnapshot.bankQuantiles.forEach { (quantile, value) ->
                add(
                    MetricPoint(
                        "arc_bank_balance_quantile_currency",
                        "Latest complete positive Bank account balance quantiles",
                        value,
                        mapOf("quantile" to quantile),
                    ),
                )
            }
            completeSnapshot.bankConcentration.forEach { (top, value) -> add(concentrationPoint("bank", top, value)) }
            completeSnapshot.knownConcentration.forEach { (top, value) -> add(concentrationPoint("known", top, value)) }
            completeSnapshot.bankSupplyDelta?.let { add(deltaPoint("bank_supply", it)) }
            completeSnapshot.walletSupplyDelta?.let { add(deltaPoint("wallet", it)) }
            completeSnapshot.knownSupplyDelta?.let { add(deltaPoint("known_supply", it)) }
            add(deltaPoint("observed_bank_increase", completeSnapshot.observedBankIncrease))
            add(deltaPoint("observed_bank_decrease", -completeSnapshot.observedBankDecrease))
            add(deltaPoint("observed_pending_interest_increase", completeSnapshot.observedPendingInterestIncrease))
            add(point("arc_bank_changed_accounts", "Accounts with an observed Bank supply change since the previous complete snapshot", completeSnapshot.changedAccounts.toDouble()))
            completeSnapshot.changeTypes.forEach { (action, count) -> add(changeTypePoint(action, count)) }
        }

    private fun classifyChange(
        walletDelta: Double,
        bankBalanceDelta: Double,
        pendingInterestDelta: Double,
    ): BankAuditChangeType {
        val bankSupplyDelta = bankBalanceDelta + pendingInterestDelta
        val knownSupplyDelta = walletDelta + bankSupplyDelta
        return when {
            positive(bankBalanceDelta) && negative(pendingInterestDelta) && nearZero(walletDelta) && offsets(bankBalanceDelta, pendingInterestDelta) ->
                BankAuditChangeType.OBSERVED_INTEREST_CAPITALIZATION
            positive(pendingInterestDelta) && nearZero(walletDelta) && nearZero(bankBalanceDelta) ->
                BankAuditChangeType.OBSERVED_INTEREST_ACCRUAL
            negative(pendingInterestDelta) && nearZero(walletDelta) && nearZero(bankBalanceDelta) ->
                BankAuditChangeType.OBSERVED_PENDING_INTEREST_REDUCTION
            positive(bankSupplyDelta) && negative(walletDelta) && offsets(bankSupplyDelta, walletDelta) ->
                BankAuditChangeType.OBSERVED_TRANSFER_TO_BANK
            negative(bankSupplyDelta) && positive(walletDelta) && offsets(bankSupplyDelta, walletDelta) ->
                BankAuditChangeType.OBSERVED_TRANSFER_FROM_BANK
            nearZero(walletDelta) && positive(knownSupplyDelta) -> BankAuditChangeType.UNEXPLAINED_SUPPLY_INCREASE
            nearZero(walletDelta) && negative(knownSupplyDelta) -> BankAuditChangeType.UNEXPLAINED_SUPPLY_DECREASE
            else -> BankAuditChangeType.MIXED_CHANGE
        }
    }

    private fun positive(value: Double): Boolean = value >= changeThreshold()

    private fun negative(value: Double): Boolean = value <= -changeThreshold()

    private fun nearZero(value: Double): Boolean = abs(value) < changeThreshold()

    private fun offsets(first: Double, second: Double): Boolean {
        val tolerance = max(changeThreshold(), max(abs(first), abs(second)) * 1e-8)
        return abs(first + second) <= tolerance
    }

    private fun changeThreshold(): Double = max(config.minimumChange, 0.000_001)

    private fun changeMagnitude(change: BankAuditChange): Double =
        maxOf(abs(change.walletDelta), abs(change.bankBalanceDelta), abs(change.pendingInterestDelta))

    private fun validAccount(account: BankAuditAccount): Boolean =
        account.playerId.isNotBlank() &&
            account.walletBalance.isFinite() &&
            account.bankBalance.isFinite() &&
            account.pendingInterest.isFinite()

    private fun activeSupplyCohort(
        accounts: List<BankAuditAccount>,
        windowDays: Int,
        now: Long,
        coverageStartedAt: Long?,
        totalKnownSupply: Double,
        activityRead: BankAuditActivityRead,
        invalidAccountActivity: Int,
    ): ActiveSupplyCohort {
        val windowMillis = windowDays * MILLIS_PER_DAY
        val cutoff = now - windowMillis
        val active = accounts.filter { checkNotNull(it.lastSeenAt) >= cutoff }
        val walletSupply = active.sumOf(BankAuditAccount::walletBalance)
        val bankSupply = active.sumOf(BankAuditAccount::bankSupply)
        val knownSupply = walletSupply + bankSupply
        val maturityRatio =
            coverageStartedAt
                ?.let { startedAt -> ((now - startedAt).coerceAtLeast(0L).toDouble() / windowMillis).coerceIn(0.0, 1.0) }
                ?: 0.0
        return ActiveSupplyCohort(
            windowDays = windowDays,
            accounts = active.size,
            walletSupply = walletSupply,
            bankSupply = bankSupply,
            knownSupply = knownSupply,
            knownSupplyShare = if (totalKnownSupply <= 0.0) 0.0 else (knownSupply / totalKnownSupply).coerceIn(0.0, 1.0),
            maturityRatio = maturityRatio,
            complete =
                activityRead.collectionSucceeded &&
                    maturityRatio >= 1.0 &&
                    activityRead.invalidEntries == 0 &&
                    invalidAccountActivity == 0,
        )
    }

    private fun quantile(sortedValues: List<Double>, quantile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = (ceil(quantile * sortedValues.size).toInt() - 1).coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun concentration(values: List<Double>): Map<String, Double> {
        val sorted = values.asSequence().filter { it > 0.0 }.sortedDescending().toList()
        val positiveSupply = sorted.sum()
        return linkedMapOf(
            "1" to share(sorted, positiveSupply, 1),
            "10" to share(sorted, positiveSupply, 10),
            "50" to share(sorted, positiveSupply, 50),
        )
    }

    private fun share(values: List<Double>, total: Double, count: Int): Double =
        if (total <= 0.0) 0.0 else (values.take(count).sum() / total).coerceIn(0.0, 1.0)

    private fun coverage(snapshot: BankAuditSnapshot): Double =
        if (snapshot.discoveredAccounts == 0) 1.0 else snapshot.sampledAccounts.toDouble() / snapshot.discoveredAccounts

    private fun activityAccountCoverage(snapshot: BankAuditSnapshot): Double =
        if (snapshot.sampledAccounts == 0) 1.0 else snapshot.activityMatchedAccounts.toDouble() / snapshot.sampledAccounts

    private fun point(name: String, description: String, value: Double): MetricPoint =
        MetricPoint(name, description, value)

    private fun accountPoint(state: String, value: Int): MetricPoint =
        MetricPoint(
            "arc_bank_snapshot_accounts",
            "Bank audit account counts; positive balance comes from the latest complete snapshot",
            value.toDouble(),
            mapOf("state" to state),
        )

    private fun moneyPoint(component: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_bank_money_currency",
            "Latest complete known money supply components including Bank",
            value,
            mapOf("component" to component),
        )

    private fun activeSupplyPoint(cohort: ActiveSupplyCohort, component: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_bank_active_supply_currency",
            "Known money supply held by accounts active on the network within the requested window",
            value,
            mapOf("window_days" to cohort.windowDays.toString(), "component" to component),
        )

    private fun deltaPoint(scope: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_bank_last_delta_currency",
            "Change observed by the latest complete Bank audit snapshot",
            value,
            mapOf("scope" to scope),
        )

    private fun changeTypePoint(action: String, value: Int): MetricPoint =
        MetricPoint(
            "arc_bank_last_change_accounts",
            "Accounts by bounded snapshot-delta Bank change classification",
            value.toDouble(),
            mapOf("action" to action, "evidence" to "snapshot_delta_inferred"),
        )

    private fun concentrationPoint(scope: String, top: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_bank_concentration_ratio",
            "Share of money in the latest complete snapshot held by the richest accounts",
            value,
            mapOf("scope" to scope, "top" to top),
        )

    private fun accountMap(account: BankAuditAccount): Map<String, Any?> =
        linkedMapOf(
            "player" to (account.player ?: "unknown"),
            "playerId" to account.playerId,
            "walletBalance" to account.walletBalance,
            "bankBalance" to account.bankBalance,
            "pendingInterest" to account.pendingInterest,
            "bankSupply" to account.bankSupply,
            "totalBalance" to account.knownSupply,
        )

    private fun cohortMap(cohort: ActiveSupplyCohort): Map<String, Any?> =
        linkedMapOf(
            "windowDays" to cohort.windowDays,
            "accounts" to cohort.accounts,
            "walletSupply" to cohort.walletSupply,
            "bankSupply" to cohort.bankSupply,
            "knownSupply" to cohort.knownSupply,
            "knownSupplyShare" to cohort.knownSupplyShare,
            "maturityRatio" to cohort.maturityRatio,
            "complete" to cohort.complete,
        )

    private fun changeMap(change: BankAuditChange): Map<String, Any?> =
        linkedMapOf(
            "timestamp" to change.timestamp,
            "player" to (change.player ?: "unknown"),
            "playerId" to change.playerId,
            "before" to change.before,
            "after" to change.after,
            "delta" to change.delta,
            "walletDelta" to change.walletDelta,
            "bankBalanceDelta" to change.bankBalanceDelta,
            "pendingInterestDelta" to change.pendingInterestDelta,
            "knownSupplyDelta" to change.knownSupplyDelta,
            "classification" to change.classification.label,
            "classificationEvidence" to "snapshot_delta_inferred",
        )

    private fun bool(value: Boolean): Double = if (value) 1.0 else 0.0

    private companion object {
        val ACTIVE_WINDOWS_DAYS = listOf(7, 30, 90)
        const val MILLIS_PER_DAY = 86_400_000L
        const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 300_000L
    }
}
