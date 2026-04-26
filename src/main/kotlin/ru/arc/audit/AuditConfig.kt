package ru.arc.audit

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Configuration for audit module.
 * Uses lazy getters for automatic reload support.
 */
open class AuditConfig(
    private val config: Config,
) {
    /** Interval for saving to Redis (ticks) */
    open val saveInterval: Long
        get() = config.integer("save-interval", 20).toLong()

    /** Interval for pruning old data (ticks) */
    open val pruneInterval: Long
        get() = config.integer("prune-interval", 6000).toLong()

    /** Maximum age of transactions (seconds) */
    open val maxAgeSeconds: Int
        get() = config.integer("max-age-seconds", 86400 * 30)

    /** Maximum total weight (transaction count) */
    open val maxWeight: Int
        get() = config.integer("max-weight", 100000)

    /** Maximum transactions per player */
    open val maxTransactions: Int
        get() = config.integer("max-transactions", 50000)

    /** Enable balance history recording */
    open val balanceHistoryEnabled: Boolean
        get() = config.bool("balance-history", false)

    private val msgs get() = config.section("messages")

    /** Page size for audit display */
    open val pageSize: Int
        get() = msgs.int("page-size", 20)

    // Message formats
    open val headerFormat: String
        get() = msgs.string("header-format", "\n<gold>%player_name%'s Audit Data")

    open val transactionFormat: String
        get() =
            msgs.string(
                "transaction-format",
                "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>",
            )

    open val incomeFormat: String
        get() = msgs.string("income-format", "<green>+%amount%")

    open val expenseFormat: String
        get() = msgs.string("expense-format", "<red>-%amount%")

    open val footerFormat: String
        get() =
            msgs.string(
                "footer-format",
                "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n",
            )

    open val prevPageFormat: String
        get() =
            msgs.string(
                "prev-page",
                "<click:run_command:/arc audit %player_name% %prev_page% %filter%><hover:show_text:'Previous page'><gold><</hover></click>",
            )

    open val nextPageFormat: String
        get() =
            msgs.string(
                "next-page",
                "<click:run_command:/arc audit %player_name% %next_page% %filter%><hover:show_text:'Next page'><gold>></hover></click>",
            )

    open val noDataMessage: String
        get() = msgs.string("no-audit-data", "<red>No audit data found for %player_name%")

    companion object {
        /**
         * Load config from file.
         */
        fun fromFile(dataPath: Path): AuditConfig {
            val config = ConfigManager.of(dataPath, "audit.yml")
            return AuditConfig(config)
        }

        /**
         * Load from plugin data path.
         */
        fun load(): AuditConfig = fromFile(ARC.instance.dataPath)

        /**
         * Default config for testing.
         */
        fun default(): AuditConfig = TestAuditConfig()
    }
}

/**
 * Test implementation of AuditConfig with explicit values.
 */
class TestAuditConfig(
    override val saveInterval: Long = 20,
    override val pruneInterval: Long = 6000,
    override val maxAgeSeconds: Int = 86400 * 30,
    override val maxWeight: Int = 100000,
    override val maxTransactions: Int = 50000,
    override val balanceHistoryEnabled: Boolean = false,
    override val pageSize: Int = 20,
    override val headerFormat: String = "\n<gold>%player_name%'s Audit Data",
    override val transactionFormat: String = "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>",
    override val incomeFormat: String = "<green>+%amount%",
    override val expenseFormat: String = "<red>-%amount%",
    override val footerFormat: String = "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n",
    override val prevPageFormat: String = "<click:run_command:/arc audit %player_name% %prev_page% %filter%><hover:show_text:'Previous page'><gold><</hover></click>",
    override val nextPageFormat: String = "<click:run_command:/arc audit %player_name% %next_page% %filter%><hover:show_text:'Next page'><gold>></hover></click>",
    override val noDataMessage: String = "<red>No audit data found for %player_name%",
) : AuditConfig(ru.arc.configs.EmptyConfig) {
    /**
     * Creates a copy with modified values.
     */
    fun copy(
        saveInterval: Long = this.saveInterval,
        pruneInterval: Long = this.pruneInterval,
        maxAgeSeconds: Int = this.maxAgeSeconds,
        maxWeight: Int = this.maxWeight,
        maxTransactions: Int = this.maxTransactions,
        balanceHistoryEnabled: Boolean = this.balanceHistoryEnabled,
        pageSize: Int = this.pageSize,
        headerFormat: String = this.headerFormat,
        transactionFormat: String = this.transactionFormat,
        incomeFormat: String = this.incomeFormat,
        expenseFormat: String = this.expenseFormat,
        footerFormat: String = this.footerFormat,
        prevPageFormat: String = this.prevPageFormat,
        nextPageFormat: String = this.nextPageFormat,
        noDataMessage: String = this.noDataMessage,
    ): TestAuditConfig =
        TestAuditConfig(
            saveInterval,
            pruneInterval,
            maxAgeSeconds,
            maxWeight,
            maxTransactions,
            balanceHistoryEnabled,
            pageSize,
            headerFormat,
            transactionFormat,
            incomeFormat,
            expenseFormat,
            footerFormat,
            prevPageFormat,
            nextPageFormat,
            noDataMessage,
        )
}
