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
    var oldest: Long? = null
    var newest: Long? = null
    val persistedRecords = mutableListOf<Pair<String, Transaction>>()

    data.forEach { auditData ->
        val snapshot = synchronized(auditData) { auditData.transactions.map(Transaction::copy) }
        snapshot.forEach { transaction ->
            if (transaction.timestamp2 < since) return@forEach
            if (!serverFilter.isNullOrBlank() && transaction.normalizedServer != serverFilter) return@forEach
            persistedRecords += auditData.name to transaction
            val source = transaction.normalizedSource.label
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

    return linkedMapOf(
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
        "topPlayers" to ranked(players, "player"),
        "unknownOrigins" to ranked(unknownOrigins, "origin"),
        "recentAnomalies" to
            anomalies
                .filter { anomaly ->
                    anomaly.timestamp >= since && (serverFilter.isNullOrBlank() || anomaly.server == serverFilter)
                }.takeLast(limit),
        "derivedAnomalies" to derivedAnomalies,
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
            transaction.amount > 0.0 && transaction.normalizedFlow !in setOf(EconomyFlow.ADJUSTMENT, EconomyFlow.INTERNAL)
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
