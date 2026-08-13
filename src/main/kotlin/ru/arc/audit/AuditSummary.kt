package ru.arc.audit

import kotlin.math.abs
import kotlin.math.ceil

private const val ACTIVITY_BUCKET_MILLIS = 5 * 60 * 1000L
private const val ACTIVITY_BUCKET_MINUTES = 5

internal class MutableAuditStats(
    private val trackBalanceProfile: Boolean = false,
) {
    var income = 0.0
    var expense = 0.0
    var operations = 0L
    var records = 0L
    val players = linkedSetOf<String>()
    val flows = linkedMapOf<String, Long>()
    private val mintByPlayer = linkedMapOf<String, Double>()
    private val burnByPlayer = linkedMapOf<String, Double>()
    private val mintPlayerBuckets = linkedSetOf<String>()
    private val burnPlayerBuckets = linkedSetOf<String>()

    fun add(player: String, transaction: Transaction, since: Long) {
        if (transaction.amount > 0) income += transaction.amount else expense += transaction.absoluteAmount
        operations += transaction.occurrenceCount
        records++
        players += player
        flows.merge(transaction.normalizedFlow.label, transaction.occurrenceCount.toLong(), Long::plus)
        if (!trackBalanceProfile) return
        when (transaction.normalizedFlow) {
            EconomyFlow.MINT -> {
                val amount = transaction.amount.coerceAtLeast(0.0)
                if (amount > 0.0) {
                    mintByPlayer.merge(player, amount, Double::plus)
                    addActivityBuckets(mintPlayerBuckets, player, transaction, since)
                }
            }
            EconomyFlow.BURN -> {
                val amount = (-transaction.amount).coerceAtLeast(0.0)
                if (amount > 0.0) {
                    burnByPlayer.merge(player, amount, Double::plus)
                    addActivityBuckets(burnPlayerBuckets, player, transaction, since)
                }
            }
            else -> Unit
        }
    }

    fun toMap(labelName: String, label: String): Map<String, Any?> {
        val result =
            linkedMapOf<String, Any?>(
                labelName to label,
                "income" to income,
                "expense" to expense,
                "net" to income - expense,
                "operations" to operations,
                "records" to records,
                "players" to players.size,
                "flows" to flows.toSortedMap(),
            )
        if (trackBalanceProfile) {
            result["mintDistribution"] = distribution(mintByPlayer.values)
            result["burnDistribution"] = distribution(burnByPlayer.values)
            result["activity"] =
                linkedMapOf(
                    "mintPlayerBuckets" to mintPlayerBuckets.size,
                    "burnPlayerBuckets" to burnPlayerBuckets.size,
                    "mintActivityPlayerHoursProxy" to playerHours(mintPlayerBuckets.size),
                    "burnActivityPlayerHoursProxy" to playerHours(burnPlayerBuckets.size),
                    "mintPerActivityPlayerHourProxy" to perPlayerHour(mintByPlayer.values.sum(), mintPlayerBuckets.size),
                    "burnPerActivityPlayerHourProxy" to perPlayerHour(burnByPlayer.values.sum(), burnPlayerBuckets.size),
                )
        }
        return result
    }

    fun volume(): Double = income + expense

    private fun addActivityBuckets(
        buckets: MutableSet<String>,
        player: String,
        transaction: Transaction,
        since: Long,
    ) {
        val first = transaction.timestamp / ACTIVITY_BUCKET_MILLIS
        val last = transaction.timestamp2 / ACTIVITY_BUCKET_MILLIS
        if (transaction.timestamp >= since) buckets += "$player|$first"
        buckets += "$player|$last"
    }

    private fun distribution(values: Collection<Double>): Map<String, Any?> {
        val sorted = values.filter { it.isFinite() && it > 0.0 }.sorted()
        val total = sorted.sum()
        return linkedMapOf(
            "players" to sorted.size,
            "topPlayerShare" to sorted.lastOrNull()?.takeIf { total > 0.0 }?.div(total),
            "p50" to percentile(sorted, 0.50),
            "p90" to percentile(sorted, 0.90),
            "p99" to percentile(sorted, 0.99),
        )
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double? {
        if (sorted.isEmpty()) return null
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun playerHours(bucketCount: Int): Double = bucketCount * ACTIVITY_BUCKET_MINUTES / 60.0

    private fun perPlayerHour(amount: Double, bucketCount: Int): Double? =
        playerHours(bucketCount).takeIf { it > 0.0 }?.let { amount / it }
}

private data class AdminShopItemKey(
    val source: String,
    val item: String,
    val material: String?,
    val customItemId: String?,
)

private data class EconomyActionKey(
    val source: String,
    val action: String,
)

private class MutableAdminShopItemStats {
    var quantity = 0L
    var exactIncome = 0.0
    var allocatedIncome = 0.0
    var transactions = 0L
    var firstTimestamp: Long? = null
    var lastTimestamp: Long? = null
    private val quantityByPlayer = linkedMapOf<String, Long>()
    private val incomeByPlayer = linkedMapOf<String, Double>()

    fun add(
        player: String,
        itemQuantity: Int,
        income: Double?,
        exact: Boolean,
        firstObservedAt: Long,
        lastObservedAt: Long,
    ) {
        quantity += itemQuantity.toLong()
        if (income != null) {
            if (exact) exactIncome += income else allocatedIncome += income
        }
        transactions++
        firstTimestamp = minOf(firstTimestamp ?: firstObservedAt, firstObservedAt)
        lastTimestamp = maxOf(lastTimestamp ?: lastObservedAt, lastObservedAt)
        quantityByPlayer.merge(player, itemQuantity.toLong(), Long::plus)
        if (income != null) incomeByPlayer.merge(player, income, Double::plus)
    }

    fun toMap(key: AdminShopItemKey): Map<String, Any?> =
        linkedMapOf(
            "source" to key.source,
            "item" to key.item,
            "material" to key.material,
            "customItemId" to key.customItemId,
            "quantity" to quantity,
            "income" to exactIncome + allocatedIncome,
            "effectiveUnitPrice" to
                (exactIncome + allocatedIncome).takeIf { quantity > 0L }?.div(quantity.toDouble()),
            "exactIncome" to exactIncome,
            "allocatedIncome" to allocatedIncome,
            "incomeEvidence" to
                when {
                    exactIncome > 0.0 && allocatedIncome == 0.0 -> "exact"
                    allocatedIncome > 0.0 && exactIncome == 0.0 -> "allocated"
                    exactIncome > 0.0 -> "mixed"
                    else -> "unattributed"
                },
            "transactions" to transactions,
            "firstTimestamp" to firstTimestamp,
            "lastTimestamp" to lastTimestamp,
            "players" to quantityByPlayer.size,
            "topPlayerQuantityShare" to share(quantityByPlayer.values.maxOrNull()?.toDouble(), quantity.toDouble()),
            "topPlayerIncomeShare" to share(incomeByPlayer.values.maxOrNull(), exactIncome + allocatedIncome),
        )

    private fun share(part: Double?, total: Double): Double? =
        part?.takeIf { total > 0.0 }?.div(total)?.coerceIn(0.0, 1.0)
}

private class AdminShopSalesSummary {
    private val items = linkedMapOf<AdminShopItemKey, MutableAdminShopItemStats>()
    var income = 0.0
        private set
    var exactIncome = 0.0
        private set
    var allocatedIncome = 0.0
        private set
    var unattributedIncome = 0.0
        private set
    var quantity = 0L
        private set
    var transactions = 0L
        private set
    var unattributedTransactions = 0L
        private set

    fun add(player: String, transaction: Transaction) {
        if (transaction.normalizedStatus != EconomyEventStatus.SUCCEEDED) return
        if (transaction.normalizedSource !in setOf(EconomySource.SHOP, EconomySource.AUTOSELL)) return
        if (transaction.normalizedFlow != EconomyFlow.MINT || transaction.amount <= 0.0) return

        income += transaction.amount
        transactions++
        val evidence = transaction.context?.normalizedItems.orEmpty().filter { item ->
            (item.quantity ?: 0) > 0 && (!item.key.isNullOrBlank() || !item.material.isNullOrBlank())
        }
        if (evidence.isEmpty()) {
            unattributedIncome += transaction.amount
            unattributedTransactions++
            return
        }

        quantity += evidence.sumOf { it.quantity!!.toLong() }
        if (evidence.size == 1) {
            addItem(player, transaction, evidence.single(), transaction.amount, exact = true)
            exactIncome += transaction.amount
            return
        }

        val weights = evidence.map { item ->
            val unitPrice = item.unitPrice
            val itemQuantity = item.quantity
            if (unitPrice == null || itemQuantity == null || !unitPrice.isFinite() || unitPrice <= 0.0) return@map null
            (unitPrice * itemQuantity).takeIf { it.isFinite() && it > 0.0 }
        }
        val weightTotal = weights.filterNotNull().sum().takeIf { weights.all { it != null } && it > 0.0 }
        if (weightTotal == null) {
            evidence.forEach { addItem(player, transaction, it, null, exact = false) }
            unattributedIncome += transaction.amount
            unattributedTransactions++
            return
        }

        evidence.zip(weights).forEach { (item, weight) ->
            addItem(player, transaction, item, transaction.amount * weight!! / weightTotal, exact = false)
        }
        allocatedIncome += transaction.amount
    }

    fun toMap(limit: Int): Map<String, Any?> =
        linkedMapOf(
            "income" to income,
            "attributedIncome" to exactIncome + allocatedIncome,
            "exactIncome" to exactIncome,
            "allocatedIncome" to allocatedIncome,
            "unattributedIncome" to unattributedIncome,
            "quantity" to quantity,
            "transactions" to transactions,
            "unattributedTransactions" to unattributedTransactions,
            "items" to
                items.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<AdminShopItemKey, MutableAdminShopItemStats>> {
                            it.value.exactIncome + it.value.allocatedIncome
                        }.thenByDescending { it.value.quantity }
                            .thenBy { it.key.item },
                    ).take(limit)
                    .map { it.value.toMap(it.key) },
        )

    private fun addItem(
        player: String,
        transaction: Transaction,
        item: EconomyLedgerItem,
        itemIncome: Double?,
        exact: Boolean,
    ) {
        val key =
            AdminShopItemKey(
                source = transaction.normalizedSource.label,
                item = item.key?.takeIf(String::isNotBlank) ?: item.material!!,
                material = item.material,
                customItemId = item.customItemId,
            )
        items.computeIfAbsent(key) { MutableAdminShopItemStats() }
            .add(
                player,
                item.quantity!!,
                itemIncome,
                exact,
                transaction.timestamp,
                transaction.timestamp2,
            )
    }
}

internal fun buildAuditSummary(
    data: Collection<AuditData>,
    generatedAt: Long,
    since: Long,
    limit: Int,
    serverFilter: String?,
    anomalies: List<EconomyAnomaly>,
    rapidWindowMillis: Long = 300_000L,
    rapidAmount: Double = 250_000.0,
    rapidTransactions: Int = 40,
    largeTransactionAmount: Double = 100_000.0,
    slimefunBuyOnlyPolicyEnabled: Boolean = false,
    slimefunBuyOnlyPolicyActivatedAt: Long = 0L,
): Map<String, Any?> {
    val sources = linkedMapOf<String, MutableAuditStats>()
    val actions = linkedMapOf<EconomyActionKey, MutableAuditStats>()
    val players = linkedMapOf<String, MutableAuditStats>()
    val unknownOrigins = linkedMapOf<String, MutableAuditStats>()
    var minted = 0.0
    var burned = 0.0
    var transferIn = 0.0
    var transferOut = 0.0
    var adjustments = 0.0
    var unknownNet = 0.0
    var internalNet = 0.0
    var observedNet = 0.0
    var operations = 0L
    var records = 0L
    var attempts = 0L
    var enrichedRecords = 0L
    var oldest: Long? = null
    var newest: Long? = null
    val persistedRecords = mutableListOf<Pair<String, Transaction>>()
    val allRecords = mutableListOf<Pair<String, Transaction>>()
    val attemptsByStatus = linkedMapOf<String, Long>()
    val attemptsByAction = linkedMapOf<String, Long>()
    val attemptsBySource = linkedMapOf<String, Long>()
    val contextPresent = linkedMapOf<String, Long>()
    val adminShopSales = AdminShopSalesSummary()
    var contextEligible = 0L

    data.forEach { auditData ->
        val snapshot = synchronized(auditData) { auditData.transactions.map(Transaction::copy) }
        snapshot.forEach { transaction ->
            if (transaction.timestamp2 < since) return@forEach
            if (!serverFilter.isNullOrBlank() && transaction.normalizedServer != serverFilter) return@forEach
            allRecords += auditData.name to transaction
            if (transaction.normalizedRecordKind == EconomyRecordKind.ATTEMPT) {
                attempts += transaction.occurrenceCount
                attemptsByStatus.merge(transaction.normalizedStatus.name.lowercase(), transaction.occurrenceCount.toLong(), Long::plus)
                attemptsByAction.merge(transaction.context?.action?.ifBlank { "unknown" } ?: "unknown", transaction.occurrenceCount.toLong(), Long::plus)
                attemptsBySource.merge(transaction.normalizedSource.label, transaction.occurrenceCount.toLong(), Long::plus)
                oldest = minOf(oldest ?: transaction.timestamp, transaction.timestamp)
                newest = maxOf(newest ?: transaction.timestamp2, transaction.timestamp2)
                return@forEach
            }
            if (transaction.normalizedStatus !in setOf(EconomyEventStatus.SUCCEEDED, EconomyEventStatus.REVERTED)) {
                return@forEach
            }
            persistedRecords += auditData.name to transaction
            contextEligible++
            if (transaction.context != null) enrichedRecords++
            val context = transaction.context
            linkedMapOf(
                "balance" to (context?.balanceBefore != null && context.balanceAfter != null),
                "session" to !context?.sessionId.isNullOrBlank(),
                "world" to !context?.world.isNullOrBlank(),
                "counterparty" to (context?.counterparty != null),
                "items" to !context?.normalizedItems.isNullOrEmpty(),
                "correlation" to !context?.correlationId.isNullOrBlank(),
                "providerTimestamp" to (context?.providerTimestamp != null),
                "action" to !context?.action.isNullOrBlank(),
            ).forEach { (field, present) ->
                if (present) contextPresent.merge(field, 1L, Long::plus)
            }
            val source = transaction.normalizedSource.label
            val action = transaction.normalizedAction.label
            val accountKey = context?.accountId?.takeIf(String::isNotBlank) ?: auditData.name.lowercase()
            adminShopSales.add(auditData.name, transaction)
            sources.computeIfAbsent(source) { MutableAuditStats(trackBalanceProfile = true) }
                .add(accountKey, transaction, since)
            actions.computeIfAbsent(EconomyActionKey(source, action)) { MutableAuditStats(trackBalanceProfile = true) }
                .add(accountKey, transaction, since)
            players.computeIfAbsent(auditData.name) { MutableAuditStats() }.add(accountKey, transaction, since)
            if (transaction.normalizedSource == EconomySource.UNKNOWN) {
                unknownOrigins.computeIfAbsent(transaction.origin.orEmpty().ifBlank { "unresolved" }) { MutableAuditStats() }
                    .add(accountKey, transaction, since)
            }

            when (transaction.normalizedFlow) {
                EconomyFlow.MINT -> minted += transaction.amount.coerceAtLeast(0.0)
                EconomyFlow.BURN -> burned += (-transaction.amount).coerceAtLeast(0.0)
                EconomyFlow.TRANSFER -> if (transaction.amount > 0) transferIn += transaction.amount else transferOut += abs(transaction.amount)
                EconomyFlow.ADJUSTMENT -> adjustments += transaction.amount
                EconomyFlow.INTERNAL -> internalNet += transaction.amount
                EconomyFlow.UNKNOWN -> unknownNet += transaction.amount
            }
            if (transaction.normalizedFlow != EconomyFlow.INTERNAL) observedNet += transaction.amount
            operations += transaction.occurrenceCount
            records++
            oldest = minOf(oldest ?: transaction.timestamp, transaction.timestamp)
            newest = maxOf(newest ?: transaction.timestamp2, transaction.timestamp2)
        }
    }

    fun ranked(
        stats: Map<String, MutableAuditStats>,
        labelName: String,
    ): List<Map<String, Any?>> =
        stats.entries.sortedByDescending { it.value.volume() }.take(limit)
            .map { it.value.toMap(labelName, it.key) }

    val derivedAnomalies =
        derivePersistedAnomalies(
            persistedRecords,
            rapidWindowMillis,
            rapidAmount,
            rapidTransactions,
            largeTransactionAmount,
            limit,
        )
    val policyViolations =
        summarizePolicyViolations(
            records = persistedRecords,
            slimefunBuyOnlyEnabled = slimefunBuyOnlyPolicyEnabled,
            slimefunBuyOnlyActivatedAt = slimefunBuyOnlyPolicyActivatedAt,
            limit = limit,
        )

    val contextCoverage =
        listOf("balance", "session", "world", "counterparty", "items", "correlation", "providerTimestamp", "action")
            .associateWith { field ->
                val present = contextPresent[field] ?: 0L
                linkedMapOf(
                    "present" to present,
                    "total" to contextEligible,
                    "ratio" to if (contextEligible == 0L) 0.0 else present.toDouble() / contextEligible,
                )
            }

    val recentEvents =
        allRecords
            .sortedByDescending { (_, transaction) -> transaction.timestamp2 }
            .take(limit)
            .map(::ledgerEventMap)
    val recentFailures =
        allRecords
            .asSequence()
            .filter { (_, transaction) ->
                transaction.normalizedStatus in setOf(EconomyEventStatus.FAILED, EconomyEventStatus.CANCELLED)
            }
            .sortedByDescending { (_, transaction) -> transaction.timestamp2 }
            .take(limit)
            .map(::ledgerEventMap)
            .toList()

    return linkedMapOf(
        "ledgerSchemaVersion" to 2,
        "generatedAt" to generatedAt,
        "since" to since,
        "serverFilter" to serverFilter,
        "coverage" to
            linkedMapOf(
                "oldest" to oldest,
                "newest" to newest,
                "records" to records,
                "operations" to operations,
                "players" to players.size,
                "attempts" to attempts,
                "enrichedRecords" to enrichedRecords,
            ),
        "totals" to
            linkedMapOf(
                "minted" to minted,
                "burned" to burned,
                "classifiedMintBurnNet" to minted - burned,
                "transferIn" to transferIn,
                "transferOut" to transferOut,
                "transferNet" to transferIn - transferOut,
                "adjustments" to adjustments,
                "unknownNet" to unknownNet,
                "internalNet" to internalNet,
                "knownSupplyNet" to minted - burned,
                "vaultObservedNet" to observedNet,
                "supplyCoverage" to "known_mint_burn_only; bank_interest_and_transfer_fees_require_separate_reconciliation",
            ),
        "sources" to ranked(sources, "source"),
        "actions" to
            actions.entries
                .sortedByDescending { it.value.volume() }
                .take(limit)
                .map { (key, stats) ->
                    LinkedHashMap(stats.toMap("source", key.source)).apply {
                        put("action", key.action)
                    }
                },
        "balanceProfileEvidence" to
            linkedMapOf(
                "distributionUnit" to "per_player_window_mint_or_burn_total",
                "percentileMethod" to "nearest_rank",
                "bucketMinutes" to ACTIVITY_BUCKET_MINUTES,
                "unit" to "unique_player_source_or_action_bucket",
                "interpretation" to "five_minute_presence_proxy_not_measured_session_duration",
            ),
        "attempts" to
            linkedMapOf(
                "total" to attempts,
                "byStatus" to attemptsByStatus.toSortedMap(),
                "byAction" to attemptsByAction.toSortedMap(),
                "bySource" to attemptsBySource.toSortedMap(),
            ),
        "contextCoverage" to contextCoverage,
        "adminShopSales" to adminShopSales.toMap(limit),
        "topPlayers" to ranked(players, "player"),
        "unknownOrigins" to ranked(unknownOrigins, "origin"),
        "policyViolations" to policyViolations,
        "recentAnomalies" to
            anomalies
                .filter { anomaly ->
                    anomaly.timestamp >= since && (serverFilter.isNullOrBlank() || anomaly.server == serverFilter)
                }.takeLast(limit),
        "derivedAnomalies" to derivedAnomalies,
        "recentEvents" to recentEvents,
        "recentFailures" to recentFailures,
    )
}

private fun summarizePolicyViolations(
    records: List<Pair<String, Transaction>>,
    slimefunBuyOnlyEnabled: Boolean,
    slimefunBuyOnlyActivatedAt: Long,
    limit: Int,
): Map<String, Any?> {
    val violations =
        records.filter { (_, transaction) ->
            EconomyPolicy.isSlimefunBuyOnlyViolation(
                amount = transaction.amount,
                source = transaction.normalizedSource,
                flow = transaction.normalizedFlow,
                context = transaction.context,
                eventTimestamp = transaction.context?.providerTimestamp ?: transaction.timestamp2,
                enabled = slimefunBuyOnlyEnabled,
                activatedAt = slimefunBuyOnlyActivatedAt,
            )
        }
    val recent =
        violations.sortedByDescending { (_, transaction) -> transaction.timestamp2 }
            .take(limit)
            .map { entry ->
                LinkedHashMap(ledgerEventMap(entry)).apply {
                    put("policy", EconomyPolicy.SLIMEFUN_BUY_ONLY)
                    put("evidence", "persisted_admin_shop_sale_after_policy_activation")
                }
            }
    return linkedMapOf(
        "policies" to
            listOf(
                linkedMapOf(
                    "policy" to EconomyPolicy.SLIMEFUN_BUY_ONLY,
                    "enabled" to slimefunBuyOnlyEnabled,
                    "activatedAt" to slimefunBuyOnlyActivatedAt.takeIf { it > 0L },
                    "records" to violations.size,
                    "operations" to violations.sumOf { (_, transaction) -> transaction.occurrenceCount.toLong() },
                    "income" to violations.sumOf { (_, transaction) -> transaction.amount.coerceAtLeast(0.0) },
                    "players" to violations.map { (player, _) -> player.lowercase() }.toSet().size,
                ),
            ),
        "recent" to recent,
    )
}

private fun ledgerEventMap(entry: Pair<String, Transaction>): Map<String, Any?> {
    val (player, transaction) = entry
    val context = transaction.context
    return linkedMapOf(
        "eventId" to transaction.eventId,
        "player" to player,
        "accountId" to context?.accountId,
        "recordKind" to transaction.normalizedRecordKind.name.lowercase(),
        "status" to transaction.normalizedStatus.name.lowercase(),
        "amount" to transaction.amount,
        "requestedAmount" to context?.requestedAmount,
        "operations" to transaction.occurrenceCount,
        "source" to transaction.normalizedSource.label,
        "flow" to transaction.normalizedFlow.label,
        "currency" to transaction.normalizedCurrency,
        "server" to transaction.normalizedServer,
        "world" to context?.world,
        "sessionId" to context?.sessionId,
        "sessionStartedAt" to context?.sessionStartedAt,
        "balanceBefore" to context?.balanceBefore,
        "balanceAfter" to context?.balanceAfter,
        "balanceEvidence" to context?.balanceEvidence?.name?.lowercase(),
        "counterparty" to context?.counterparty,
        "correlationId" to context?.correlationId,
        "providerTimestamp" to context?.providerTimestamp,
        "action" to context?.action,
        "shopId" to context?.shopId,
        "items" to context?.normalizedItems.orEmpty(),
        "priceComponents" to context?.normalizedPriceComponents.orEmpty(),
        "failureReason" to context?.failureReason,
        "revertedWith" to context?.revertedWith,
        "reason" to transaction.comment,
        "origin" to transaction.origin,
        "timestamp" to transaction.timestamp,
        "timestamp2" to transaction.timestamp2,
    )
}

private data class PersistedIncomePoint(
    val timestamp: Long,
    val amount: Double,
    val operations: Int,
)

private fun derivePersistedAnomalies(
    records: List<Pair<String, Transaction>>,
    rapidWindowMillis: Long,
    rapidAmount: Double,
    rapidTransactions: Int,
    largeTransactionAmount: Double,
    limit: Int,
): List<Map<String, Any?>> {
    val large =
        records.mapNotNull { (player, transaction) ->
            if (largeTransactionAmount <= 0.0 || abs(transaction.amount) < largeTransactionAmount) return@mapNotNull null
            linkedMapOf<String, Any?>(
                "kind" to "large_persisted_aggregate",
                "player" to player,
                "amount" to transaction.amount,
                "operations" to transaction.occurrenceCount,
                "source" to transaction.normalizedSource.label,
                "flow" to transaction.normalizedFlow.label,
                "server" to transaction.normalizedServer,
                "currency" to transaction.normalizedCurrency,
                "timestamp" to transaction.timestamp2,
            )
        }
    val rapid = mutableListOf<Map<String, Any?>>()
    records
        .filter { (_, transaction) ->
            transaction.normalizedRecordKind == EconomyRecordKind.TRANSACTION &&
                transaction.normalizedStatus in setOf(EconomyEventStatus.SUCCEEDED, EconomyEventStatus.REVERTED) &&
                transaction.amount > 0.0 &&
                transaction.normalizedFlow !in setOf(EconomyFlow.ADJUSTMENT, EconomyFlow.INTERNAL)
        }.groupBy { (player, transaction) ->
            listOf(player.lowercase(), transaction.normalizedSource.label, transaction.normalizedServer, transaction.normalizedCurrency)
        }.forEach { (key, grouped) ->
            val points = ArrayDeque<PersistedIncomePoint>()
            var total = 0.0
            var operations = 0
            var best: Map<String, Any?>? = null
            grouped.sortedBy { it.second.timestamp2 }.forEach { (player, transaction) ->
                val now = transaction.timestamp2
                points.addLast(PersistedIncomePoint(now, transaction.amount, transaction.occurrenceCount))
                total += transaction.amount
                operations += transaction.occurrenceCount
                while (points.isNotEmpty() && points.first().timestamp < now - rapidWindowMillis) {
                    val removed = points.removeFirst()
                    total -= removed.amount
                    operations -= removed.operations
                }
                if (total >= rapidAmount || operations >= rapidTransactions) {
                    val candidate =
                        linkedMapOf<String, Any?>(
                            "kind" to "rapid_income_persisted",
                            "player" to player,
                            "amount" to total,
                            "operations" to operations,
                            "source" to key[1],
                            "server" to key[2],
                            "currency" to key[3],
                            "timestamp" to now,
                            "windowMillis" to rapidWindowMillis,
                        )
                    if (best == null || total > (best["amount"] as Number).toDouble()) best = candidate
                }
            }
            best?.let(rapid::add)
        }
    return (large + rapid).sortedByDescending { abs((it["amount"] as Number).toDouble()) }.take(limit)
}
