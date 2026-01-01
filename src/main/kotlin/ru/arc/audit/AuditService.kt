package ru.arc.audit

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TimeProvider
import ru.arc.core.SystemTimeProvider
import ru.arc.util.DateUtils
import ru.arc.util.Logging.info
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
    private val timeProvider: TimeProvider = SystemTimeProvider
) {
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
    fun operation(playerName: String, amount: Double, type: Type, comment: String) {
        repository.getOrCreate(playerName.lowercase()) {
            AuditData.create(playerName)
        }.thenAccept { data ->
            data.operation(amount, type, comment)
        }
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
        repository.getOrCreate(playerName.lowercase()) {
            AuditData.create(playerName)
        }.thenAccept { data ->
            if (data.transactions.isEmpty()) {
                audience.sendMessage(noDataMessage(playerName))
                return@thenAccept
            }

            val filtered = data.getFiltered(filter).reversed()
            audience.sendMessage(formatAudit(filtered, playerName, page, filter))
        }
    }

    /**
     * Get player's audit data.
     */
    fun getAuditData(playerName: String): AuditData? {
        return repository.get(playerName.lowercase()).join()
    }

    // ==================== Clear ====================

    /**
     * Clear specific player's audit.
     */
    fun clearPlayer(playerName: String) {
        repository.get(playerName.lowercase()).thenAccept { data ->
            data?.clear()
        }
    }

    /**
     * Clear all audit data.
     */
    fun clearAll() {
        repository.all().forEach { it.clear() }
    }

    // ==================== Maintenance ====================

    /**
     * Prune old data to stay under weight limit.
     */
    fun pruneOldData() {
        var currentMaxAge = config.maxAgeSeconds * 1000L
        var currentWeight = totalWeight()
        var iterations = 0

        while (currentWeight > config.maxWeight && iterations < 10) {
            info("Pruning audit data, weight: {}, maxAge: {}ms", currentWeight, currentMaxAge)

            repository.all().forEach { data ->
                data.trim(currentMaxAge, config.maxTransactions)
            }

            currentWeight = totalWeight()
            currentMaxAge /= 2
            iterations++
        }

        if (iterations >= 10 && currentWeight > config.maxWeight) {
            warn("Pruning failed to reduce weight below {}", config.maxWeight)
        }
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

