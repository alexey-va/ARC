package ru.arc.audit

import ru.arc.ARC
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Configuration for audit module.
 *
 * Immutable data class for easy testing - just create with desired values.
 */
data class AuditConfig(
    /** Interval for saving to Redis (ticks) */
    val saveInterval: Long = 20,

    /** Interval for pruning old data (ticks) */
    val pruneInterval: Long = 6000,

    /** Maximum age of transactions (seconds) */
    val maxAgeSeconds: Int = 86400 * 30,

    /** Maximum total weight (transaction count) */
    val maxWeight: Int = 100000,

    /** Maximum transactions per player */
    val maxTransactions: Int = 50000,

    /** Enable balance history recording */
    val balanceHistoryEnabled: Boolean = false,

    /** Page size for audit display */
    val pageSize: Int = 20,

    // Message formats
    val headerFormat: String = "\n<gold>%player_name%'s Audit Data",
    val transactionFormat: String = "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>",
    val incomeFormat: String = "<green>+%amount%",
    val expenseFormat: String = "<red>-%amount%",
    val footerFormat: String = "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n",
    val prevPageFormat: String = "<click:run_command:/arc audit %player_name% %prev_page% %filter%><hover:show_text:'Previous page'><gold><</hover></click>",
    val nextPageFormat: String = "<click:run_command:/arc audit %player_name% %next_page% %filter%><hover:show_text:'Next page'><gold>></hover></click>",
    val noDataMessage: String = "<red>No audit data found for %player_name%"
) {
    companion object {
        /**
         * Load config from file.
         */
        fun fromFile(dataPath: Path): AuditConfig {
            val config = ConfigManager.of(dataPath, "audit.yml")

            return AuditConfig(
                saveInterval = config.integer("save-interval", 20).toLong(),
                pruneInterval = config.integer("prune-interval", 6000).toLong(),
                maxAgeSeconds = config.integer("max-age-seconds", 86400 * 30),
                maxWeight = config.integer("max-weight", 100000),
                maxTransactions = config.integer("max-transactions", 50000),
                balanceHistoryEnabled = config.bool("balance-history", false),
                pageSize = config.integer("messages.page-size", 20),
                headerFormat = config.string("messages.header-format", "\n<gold>%player_name%'s Audit Data"),
                transactionFormat = config.string(
                    "messages.transaction-format",
                    "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>"
                ),
                incomeFormat = config.string("messages.income-format", "<green>+%amount%"),
                expenseFormat = config.string("messages.expense-format", "<red>-%amount%"),
                footerFormat = config.string(
                    "messages.footer-format",
                    "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n"
                ),
                prevPageFormat = config.string(
                    "messages.prev-page",
                    "<click:run_command:/arc audit %player_name% %prev_page% %filter%><hover:show_text:'Previous page'><gold><</hover></click>"
                ),
                nextPageFormat = config.string(
                    "messages.next-page",
                    "<click:run_command:/arc audit %player_name% %next_page% %filter%><hover:show_text:'Next page'><gold>></hover></click>"
                ),
                noDataMessage = config.string("messages.no-audit-data", "<red>No audit data found for %player_name%")
            )
        }

        /**
         * Default config for testing.
         */
        fun default(): AuditConfig = AuditConfig()

        /**
         * Load from plugin data path.
         */
        fun load(): AuditConfig = fromFile(ARC.plugin.dataPath)
    }
}

