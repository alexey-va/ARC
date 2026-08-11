package ru.arc.audit.bank

import ru.arc.metrics.core.MetricPoint
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil

data class BankAuditAccount(
    val playerId: String,
    val player: String?,
    val walletBalance: Double,
    val bankBalance: Double,
    val pendingInterest: Double,
) {
    val bankSupply: Double get() = bankBalance + pendingInterest
    val knownSupply: Double get() = walletBalance + bankSupply
}

data class BankAuditReadResult(
    val discoveredAccounts: Int,
    val accounts: List<BankAuditAccount>,
    val failedAccounts: Int = 0,
    val capped: Boolean = false,
) {
    val complete: Boolean
        get() = !capped && failedAccounts == 0 && accounts.size == discoveredAccounts
}

data class BankAuditChange(
    val timestamp: Long,
    val playerId: String,
    val player: String?,
    val before: Double,
    val after: Double,
    val delta: Double,
    val pendingInterestDelta: Double,
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
    val bankQuantiles: Map<String, Double>,
    val bankConcentration: Map<String, Double>,
    val knownConcentration: Map<String, Double>,
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
                if (abs(delta) < config.minimumChange) return@forEach
                changes +=
                    BankAuditChange(
                        timestamp = now,
                        playerId = playerId,
                        player = account.player ?: previous.player,
                        before = previous.bankSupply,
                        after = account.bankSupply,
                        delta = delta,
                        pendingInterestDelta = account.pendingInterest - previous.pendingInterest,
                    )
            }
        }

        changes
            .sortedBy { abs(it.delta) }
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
                changedAccounts = changes.size,
                bankQuantiles =
                    linkedMapOf(
                        "0.50" to quantile(bankValues, 0.50),
                        "0.90" to quantile(bankValues, 0.90),
                        "0.99" to quantile(bankValues, 0.99),
                    ),
                bankConcentration = concentration(positiveBankAccounts.map(BankAuditAccount::bankSupply)),
                knownConcentration = concentration(accounts.map(BankAuditAccount::knownSupply)),
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
            "distribution" to
                linkedMapOf(
                    "bankQuantiles" to snapshot.bankQuantiles,
                    "bankConcentration" to snapshot.bankConcentration,
                    "knownConcentration" to snapshot.knownConcentration,
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
            completeSnapshot ?: return@buildList
            add(point("arc_bank_snapshot_timestamp_seconds", "Unix timestamp of the latest complete Bank snapshot", completeSnapshot.timestamp / 1000.0))
            add(accountPoint("positive_bank_balance", completeSnapshot.positiveBankAccounts))
            add(moneyPoint("wallet", completeSnapshot.walletSupply))
            add(moneyPoint("bank_balance", completeSnapshot.bankBalance))
            add(moneyPoint("pending_interest", completeSnapshot.pendingInterest))
            add(moneyPoint("bank_supply", completeSnapshot.bankSupply))
            add(moneyPoint("known_supply", completeSnapshot.knownSupply))
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
        }

    private fun validAccount(account: BankAuditAccount): Boolean =
        account.playerId.isNotBlank() &&
            account.walletBalance.isFinite() &&
            account.bankBalance.isFinite() &&
            account.pendingInterest.isFinite()

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

    private fun deltaPoint(scope: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_bank_last_delta_currency",
            "Change observed by the latest complete Bank audit snapshot",
            value,
            mapOf("scope" to scope),
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

    private fun changeMap(change: BankAuditChange): Map<String, Any?> =
        linkedMapOf(
            "timestamp" to change.timestamp,
            "player" to (change.player ?: "unknown"),
            "playerId" to change.playerId,
            "before" to change.before,
            "after" to change.after,
            "delta" to change.delta,
            "pendingInterestDelta" to change.pendingInterestDelta,
        )

    private fun bool(value: Boolean): Double = if (value) 1.0 else 0.0
}
