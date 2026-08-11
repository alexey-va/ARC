package ru.arc.audit

import kotlin.math.abs

internal class MutableAuditStats {
    var income = 0.0
    var expense = 0.0
    var operations = 0L
    var records = 0L
    val players = linkedSetOf<String>()
    val flows = linkedMapOf<String, Long>()

    fun add(player: String, transaction: Transaction) {
        if (transaction.amount > 0) income += transaction.amount else expense += transaction.absoluteAmount
        operations += transaction.occurrenceCount
        records++
        players += player
        flows.merge(transaction.normalizedFlow.label, transaction.occurrenceCount.toLong(), Long::plus)
    }

    fun toMap(labelName: String, label: String): Map<String, Any?> =
        linkedMapOf(
            labelName to label,
            "income" to income,
            "expense" to expense,
            "net" to income - expense,
            "operations" to operations,
            "records" to records,
            "players" to players.size,
            "flows" to flows.toSortedMap(),
        )

    fun volume(): Double = income + expense
}

private data class AdminShopItemKey(
    val source: String,
    val item: String,
    val material: String?,
)

private class MutableAdminShopItemStats {
    var quantity = 0L
    var exactIncome = 0.0
    var allocatedIncome = 0.0
    var transactions = 0L
    val players = linkedSetOf<String>()

    fun add(player: String, itemQuantity: Int, income: Double?, exact: Boolean) {
        quantity += itemQuantity.toLong()
        if (income != null) {
            if (exact) exactIncome += income else allocatedIncome += income
        }
        transactions++
        players += player
    }

    fun toMap(key: AdminShopItemKey): Map<String, Any?> =
        linkedMapOf(
            "source" to key.source,
            "item" to key.item,
            "material" to key.material,
            "quantity" to quantity,
            "income" to exactIncome + allocatedIncome,
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
            "players" to players.size,
        )
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
            )
        items.computeIfAbsent(key) { MutableAdminShopItemStats() }
            .add(player, item.quantity!!, itemIncome, exact)
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
): Map<String, Any?> {
    val sources = linkedMapOf<String, MutableAuditStats>()
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
            ).forEach { (field, present) ->
                if (present) contextPresent.merge(field, 1L, Long::plus)
            }
            val source = transaction.normalizedSource.label
            adminShopSales.add(auditData.name, transaction)
            sources.computeIfAbsent(source) { MutableAuditStats() }.add(auditData.name, transaction)
            players.computeIfAbsent(auditData.name) { MutableAuditStats() }.add(auditData.name, transaction)
            if (transaction.normalizedSource == EconomySource.UNKNOWN) {
                unknownOrigins.computeIfAbsent(transaction.origin.orEmpty().ifBlank { "unresolved" }) { MutableAuditStats() }
                    .add(auditData.name, transaction)
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

    fun ranked(stats: Map<String, MutableAuditStats>, labelName: String): List<Map<String, Any?>> =
        stats.entries.sortedByDescending { it.value.volume() }.take(limit).map { it.value.toMap(labelName, it.key) }

    val derivedAnomalies =
        derivePersistedAnomalies(
            persistedRecords,
            rapidWindowMillis,
            rapidAmount,
            rapidTransactions,
            largeTransactionAmount,
            limit,
        )

    val contextCoverage =
        listOf("balance", "session", "world", "counterparty", "items", "correlation", "providerTimestamp")
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
