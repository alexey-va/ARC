package ru.arc.audit

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import ru.arc.core.ScheduledTask
import ru.arc.core.SystemTimeProvider
import ru.arc.core.TaskScheduler
import ru.arc.core.TimeProvider
import ru.arc.util.DateUtils
import ru.arc.util.Logging.info
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil.mm

/**
 * Core audit service with business logic.
 *
 * Fully testable - all dependencies are injected via constructor.
 *
 * @param repository Data storage
 * @param config Configuration values
 * @param scheduler Task scheduler (for prune task)
 * @param timeProvider Time source (for testing)
 */
class AuditService(
    private val repository: AuditRepository,
    private val config: AuditConfig = AuditConfig.default(),
    private val scheduler: TaskScheduler? = null,
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val monitor: EconomyAuditMonitor? = null,
    private val localServer: String? = null,
) {
    private val maximumEconomyWindowMillis = 31L * 24 * 60 * 60 * 1_000
    private var pruneTask: ScheduledTask? = null

    // ==================== Lifecycle ====================

    /**
     * Start background tasks.
     */
    fun start() {
        scheduler?.let {
            pruneTask = it.runTimerAsync(config.pruneInterval, config.pruneInterval) {
                pruneOldData()
            }
        }
    }

    /**
     * Stop background tasks.
     */
    fun stop() {
        pruneTask?.cancel()
        pruneTask = null
    }

    /**
     * Shutdown the repository and cleanup.
     */
    fun shutdown() {
        stop()
        repository.shutdown()
    }

    // ==================== Player Context ====================

    /**
     * Player joined - add to context.
     */
    fun playerJoined(name: String) {
        repository.addContext(name.lowercase())
    }

    /**
     * Player left - remove from context.
     */
    fun playerLeft(name: String) {
        repository.removeContext(name.lowercase())
    }

    // ==================== Operations ====================

    /**
     * Record a financial operation.
     */
    fun operation(
        playerName: String,
        amount: Double,
        type: Type,
        comment: String,
        metadata: AuditMetadata = AuditMetadata.legacy(),
        context: EconomyLedgerContext? = null,
    ) {
        if (!amount.isFinite()) {
            warn("Rejected non-finite economy audit amount for {}: {}", playerName, amount)
            return
        }
        val occurredAt = timeProvider.currentTimeMillis()
        val entityId = entityId(playerName, metadata)
        repository.getOrCreate(entityId) {
            AuditData.create(playerName, entityId.takeIf { ':' in it })
        }.whenComplete { data, failure ->
            if (failure != null) {
                error("Failed to load economy audit data for {}", playerName, failure)
                monitor?.persistenceFailure(metadata)
                return@whenComplete
            }
            try {
                synchronized(data) {
                    data.operation(
                        amount = amount,
                        type = type,
                        comment = comment.take(240),
                        metadata = metadata,
                        context = context,
                        at = occurredAt,
                        aggregationWindowMillis = config.aggregationWindowSeconds * 1000L,
                    )
                    repository.save(data)
                }
            } catch (saveFailure: Throwable) {
                error("Failed to persist economy audit data for {}", playerName, saveFailure)
                monitor?.persistenceFailure(metadata)
            }
        }
    }

    fun economyOperation(
        playerName: String,
        amount: Double,
        type: Type,
        comment: String,
        metadata: AuditMetadata,
        context: EconomyLedgerContext? = null,
    ) {
        operation(playerName, amount, type, comment, metadata, context)
        monitor?.observe(playerName, amount, metadata, comment, context)
    }

    fun economyAttempt(
        playerName: String,
        type: Type,
        comment: String,
        metadata: AuditMetadata,
        context: EconomyLedgerContext,
    ) {
        require(context.normalizedRecordKind == EconomyRecordKind.ATTEMPT) { "Attempt context must use ATTEMPT kind" }
        operation(playerName, 0.0, type, comment, metadata, context)
        monitor?.observeAttempt(metadata, context)
    }

    fun unresolvedBalanceSet(
        playerName: String,
        absoluteBalance: Double,
        observedMetadata: AuditMetadata,
        context: EconomyLedgerContext? = null,
    ) {
        val metadata = observedMetadata.copy(source = EconomySource.BALANCE_SET, flow = EconomyFlow.ADJUSTMENT)
        val reason = "Unresolved API setBalance; requested absolute balance=$absoluteBalance"
        operation(playerName, 0.0, Type.BALANCE_SET, reason, metadata, context)
        monitor?.unresolvedBalanceSet(playerName, absoluteBalance, metadata, reason)
    }

    /**
     * Record income (positive amount).
     */
    fun income(playerName: String, amount: Double, type: Type, comment: String) {
        require(amount >= 0) { "Income must be non-negative" }
        operation(playerName, amount, type, comment)
    }

    /**
     * Record expense (negative amount).
     */
    fun expense(playerName: String, amount: Double, type: Type, comment: String) {
        require(amount >= 0) { "Expense must be non-negative" }
        operation(playerName, -amount, type, comment)
    }

    // ==================== Queries ====================

    /**
     * Get total transaction count across all players.
     */
    fun totalWeight(): Long {
        return repository.all().sumOf { it.transactions.size.toLong() }
    }

    /**
     * Send formatted audit to audience.
     */
    fun sendAudit(audience: Audience, playerName: String, page: Int, filter: AuditFilter) {
        val data = getAuditData(playerName)
        if (data == null || data.transactions.isEmpty()) {
            audience.sendMessage(noDataMessage(playerName))
            return
        }

        val filtered = data.getFiltered(filter).reversed()
        audience.sendMessage(formatAudit(filtered, playerName, page, filter))
    }

    /**
     * Get player's audit data.
     */
    fun getAuditData(playerName: String): AuditData? {
        val matches = playerData(playerName)
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.single()
        return AuditData.create(playerName).also { combined ->
            matches.flatMap { data -> synchronized(data) { data.transactions.map(Transaction::copy) } }
                .sortedBy(Transaction::timestamp)
                .forEach(combined.transactions::add)
        }
    }

    fun economySummary(hours: Int, limit: Int, serverFilter: String? = null): Map<String, Any?> {
        val safeHours = hours.coerceIn(1, 24 * 31)
        val now = timeProvider.currentTimeMillis()
        return economySummaryAt(now - safeHours * 60L * 60L * 1000L, now, limit, serverFilter)
    }

    fun economySummarySince(sinceEpochMs: Long, limit: Int, serverFilter: String? = null): Map<String, Any?> {
        val now = timeProvider.currentTimeMillis()
        require(sinceEpochMs in (now - maximumEconomyWindowMillis)..<now) {
            "since_epoch_ms must be in the past and within the last 31 days"
        }
        return economySummaryAt(sinceEpochMs, now, limit, serverFilter)
    }

    private fun economySummaryAt(
        sinceEpochMs: Long,
        generatedAt: Long,
        limit: Int,
        serverFilter: String?,
    ): Map<String, Any?> =
        buildAuditSummary(
            data = repository.all(),
            generatedAt = generatedAt,
            since = sinceEpochMs,
            limit = limit.coerceIn(1, 100),
            serverFilter = serverFilter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" },
            anomalies = monitor?.recent(100).orEmpty(),
            rapidWindowMillis = config.rapidIncomeWindowSeconds * 1000L,
            rapidAmount = config.rapidIncomeAmount,
            rapidTransactions = config.rapidIncomeTransactions,
            largeTransactionAmount = config.largeTransactionAmount,
            slimefunBuyOnlyPolicyEnabled = config.slimefunBuyOnlyPolicyEnabled,
            slimefunBuyOnlyPolicyActivatedAt = config.slimefunBuyOnlyPolicyActivatedAt,
        )

    // ==================== Clear ====================

    /**
     * Clear specific player's audit.
     */
    fun clearPlayer(playerName: String) {
        ownedData().filter { it.name.equals(playerName, ignoreCase = true) }.forEach { data ->
            synchronized(data) {
                data.clear()
                repository.save(data)
            }
        }
    }

    /**
     * Clear all audit data.
     */
    fun clearAll() {
        ownedData().forEach { data ->
            synchronized(data) {
                data.clear()
                repository.save(data)
            }
        }
    }

    // ==================== Maintenance ====================

    /**
     * Enforce retention first, then shorten the window if the global weight is still too high.
     */
    fun pruneOldData() {
        val now = timeProvider.currentTimeMillis()
        trimAll(config.maxAgeSeconds * 1000L, now)
        trimGlobalWeight(if (localServer == null) config.maxWeight else config.shardMaxWeight)
    }

    private fun trimAll(maxAgeMillis: Long, now: Long) {
        ownedData().forEach { data ->
            synchronized(data) {
                if (data.trim(maxAgeMillis, config.maxTransactions, now) > 0) {
                    repository.save(data)
                }
            }
        }
    }

    private fun trimGlobalWeight(maxWeight: Int) {
        val safeMaxWeight = maxWeight.coerceAtLeast(0)
        val owned = ownedData()
        val ownedWeight = owned.sumOf { it.transactions.size.toLong() }
        val excess = (ownedWeight - safeMaxWeight).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (excess == 0) return
        info("Pruning {} globally oldest audit records to enforce max weight {}", excess, safeMaxWeight)
        val oldest =
            owned
                .flatMap { data -> synchronized(data) { data.transactions.map { transaction -> data to transaction } } }
                .sortedBy { (_, transaction) -> transaction.timestamp2 }
                .take(excess)
                .groupBy({ it.first }, { it.second })
        oldest.forEach { (data, transactions) ->
            synchronized(data) {
                transactions.forEach(data.transactions::remove)
                repository.save(data)
            }
        }
    }

    private fun playerData(playerName: String): List<AuditData> =
        repository.all().filter { it.name.equals(playerName, ignoreCase = true) }

    private fun ownedData(): List<AuditData> {
        val server = localServer?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return repository.all().toList()
        return repository.all().filter { data ->
            data.storageId?.startsWith("$server:") == true || (server == "spawn" && data.storageId.isNullOrBlank())
        }
    }

    private fun entityId(playerName: String, metadata: AuditMetadata): String {
        val playerId = playerName.lowercase()
        val server = metadata.server.trim().lowercase().takeUnless { it.isEmpty() || it == "unknown" }
        return if (server == null) playerId else "$server:$playerId"
    }


    // ==================== Formatting ====================

    private fun noDataMessage(playerName: String): Component {
        return mm(config.noDataMessage.replace("%player_name%", playerName))
    }

    private fun formatAudit(
        transactions: List<Transaction>,
        playerName: String,
        page: Int,
        filter: AuditFilter
    ): Component {
        val totalPages = maxOf(1, (transactions.size + config.pageSize - 1) / config.pageSize)
        val safePage = page.coerceIn(1, totalPages)

        val start = config.pageSize * (safePage - 1)
        val end = minOf(start + config.pageSize, transactions.size)

        val lines = buildList {
            add(formatHeader(playerName))

            transactions.subList(start, end).forEachIndexed { index, transaction ->
                add(formatTransaction(start + index + 1, transaction))
            }

            add(formatFooter(playerName, safePage, totalPages, filter))
        }

        return mm(lines.joinToString("\n"))
    }

    private fun formatHeader(playerName: String): String {
        return config.headerFormat.replace("%player_name%", playerName)
    }

    private fun formatTransaction(index: Int, transaction: Transaction): String {
        val amountFormat = if (transaction.isIncome) config.incomeFormat else config.expenseFormat
        val formattedAmount = amountFormat.replace("%amount%", String.format("%.2f", transaction.absoluteAmount))

        return config.transactionFormat
            .replace("%counter%", String.format("%03d", index))
            .replace("%date%", DateUtils.formatDate(transaction.timestamp))
            .replace("%type%", transaction.type.name)
            .replace("%amount%", formattedAmount)
            .replace("%date2%", DateUtils.formatDate(transaction.timestamp2))
            .replace("%comment%", transaction.comment.ifEmpty { "-" })
            .replace("%source%", transaction.normalizedSource.label)
            .replace("%flow%", transaction.normalizedFlow.label)
            .replace("%currency%", transaction.normalizedCurrency)
            .replace("%server%", transaction.normalizedServer)
            .replace("%occurrences%", transaction.occurrenceCount.toString())
    }

    private fun formatFooter(playerName: String, page: Int, totalPages: Int, filter: AuditFilter): String {
        val prevPage = if (page > 1) {
            config.prevPageFormat
                .replace("%player_name%", playerName)
                .replace("%prev_page%", (page - 1).toString())
                .replace("%filter%", filter.name.lowercase())
        } else ""

        val nextPage = if (page < totalPages) {
            config.nextPageFormat
                .replace("%player_name%", playerName)
                .replace("%next_page%", (page + 1).toString())
                .replace("%filter%", filter.name.lowercase())
        } else ""

        return config.footerFormat
            .replace("%prev%", prevPage)
            .replace("%page%", page.toString())
            .replace("%total_pages%", totalPages.toString())
            .replace("%next%", nextPage)
            .replace("%filter%", filter.name.lowercase())
    }
}
