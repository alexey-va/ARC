package ru.arc.audit

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import java.nio.file.Path

/**
 * Configuration for audit module.
 * Uses lazy getters for automatic reload support.
 */
open class AuditConfig(
    private val config: Config,
) {
    private val storage get() = config.section("storage")
    private val mysqlSection get() = storage.section("mysql")
    private val writerSection get() = mysqlSection.section("writer")
    private val migrationSection get() = storage.section("migration")
    private val cleanupSection get() = storage.section("cleanup")

    open val storageMode: AuditStorageMode
        get() = AuditStorageMode.parse(storage.string("mode", "redis"))

    open val shutdownTimeoutSeconds: Int
        get() = storage.int("shutdown-timeout-seconds", 15).coerceIn(1, 60)

    open val migrationOwnerServer: String
        get() = migrationSection.string("owner-server", "survival").trim().lowercase().ifBlank { "survival" }

    open val migrationBatchSize: Int
        get() = migrationSection.int("batch-size", 500).coerceIn(100, 10_000)

    open val writeBatchSize: Int
        get() = writerSection.int("batch-size", 250).coerceIn(1, 1_000)

    open val writeFlushIntervalMillis: Long
        get() = writerSection.long("flush-interval-ms", 250L).coerceIn(25L, 5_000L)

    open val maximumPendingEvents: Int
        get() = writerSection.int("maximum-pending-events", 10_000).coerceIn(1_000, 100_000)

    open val writeRetryIntervalMillis: Long
        get() = writerSection.long("retry-interval-ms", 1_000L).coerceIn(100L, 60_000L)

    open val jobsCoalesceWindowSeconds: Int
        get() = writerSection.int("jobs-coalesce-window-seconds", 60).coerceIn(1, 300)

    open val jobsCoalesceMaximumEvents: Int
        get() = writerSection.int("jobs-coalesce-maximum-events", 1_000).coerceIn(10, 10_000)

    open val cleanupIntervalHours: Int
        get() = cleanupSection.int("interval-hours", 24).coerceIn(1, 24)

    open val cleanupOwnerServer: String
        get() = cleanupSection.string("owner-server", "survival").trim().lowercase().ifBlank { "survival" }

    open val retentionDays: Int
        get() = cleanupSection.int("retention-days", 30).coerceIn(7, 365)

    open val jobsRawRetentionDays: Int
        get() = cleanupSection.int("jobs-raw-retention-days", 7).coerceIn(1, retentionDays)

    open val maxCompactionDaysPerRun: Int
        get() = cleanupSection.int("max-compaction-days-per-run", 7).coerceIn(1, 31)

    open val cleanupDeleteBatchSize: Int
        get() = cleanupSection.int("delete-batch-size", 10_000).coerceIn(1_000, 100_000)

    open val mysql: SqlConnectionConfig?
        get() {
            if (storageMode == AuditStorageMode.REDIS) return null
            val sslModeText = mysqlSection.string("ssl-mode", SqlSslMode.VERIFY_IDENTITY.name)
            val sslMode =
                SqlSslMode.entries.firstOrNull { it.name.equals(sslModeText.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unsupported audit MySQL ssl-mode: $sslModeText")
            return SqlConnectionConfig(
                host = mysqlSection.string("host").trim().also { require(it.isNotEmpty()) { "Audit MySQL host is required" } },
                port = mysqlSection.int("port", 3306),
                database = mysqlSection.string("database").trim().also { require(it.isNotEmpty()) { "Audit MySQL database is required" } },
                username = mysqlSection.string("username").trim().also { require(it.isNotEmpty()) { "Audit MySQL username is required" } },
                password = mysqlSection.string("password").also { require(it.isNotBlank()) { "Audit MySQL password is required" } },
                sslMode = sslMode,
                minimumIdle = mysqlSection.int("pool.minimum-idle", 1).coerceIn(0, 64),
                maximumPoolSize = mysqlSection.int("pool.maximum-size", 2).coerceIn(1, 64),
                connectionTimeoutMs = mysqlSection.long("pool.connection-timeout-ms", 10_000L),
                socketTimeoutMs = mysqlSection.long("pool.socket-timeout-ms", 30_000L),
                validationTimeoutMs = mysqlSection.long("pool.validation-timeout-ms", 5_000L),
                maxLifetimeMs = mysqlSection.long("pool.max-lifetime-ms", 1_700_000L),
                failFast = true,
            )
        }

    /** Interval for saving to Redis (ticks) */
    open val saveInterval: Long
        get() = config.integer("save-interval", 20).toLong()

    /** Maximum delay used to coalesce repeated remote invalidations for one player. */
    open val invalidationCoalesceSeconds: Int
        get() = config.integer("invalidation-coalesce-seconds", 5).coerceIn(0, 60)

    /** Interval for pruning old data (ticks) */
    open val pruneInterval: Long
        get() = config.integer("prune-interval", 6000).toLong()

    /** Maximum age of transactions (seconds) */
    open val maxAgeSeconds: Int
        get() = config.integer("max-age-seconds", 86400 * 30)

    /** Maximum total weight (transaction count) */
    open val maxWeight: Int
        get() = config.integer("max-weight", 100000)

    /** Per-server shard budget; configured shard totals form the network maximum. */
    open val shardMaxWeight: Int
        get() = config.integer("shard-max-weight", maxWeight).coerceAtLeast(0)

    /** Maximum transactions per player */
    open val maxTransactions: Int
        get() = config.integer("max-transactions", 50000)

    /** Enable balance history recording */
    open val balanceHistoryEnabled: Boolean
        get() = config.bool("balance-history", false)

    /** Maximum gap between records combined into one persisted transaction. */
    open val aggregationWindowSeconds: Int
        get() = config.integer("aggregation-window-seconds", 10).coerceIn(0, 300)

    open val monitoringEnabled: Boolean
        get() = config.bool("monitoring.enabled", true)

    open val largeTransactionAmount: Double
        get() = config.double("monitoring.large-transaction-amount", 100_000.0).coerceAtLeast(0.0)

    open val rapidIncomeWindowSeconds: Int
        get() = config.integer("monitoring.rapid-income-window-seconds", 300).coerceIn(10, 86_400)

    open val rapidIncomeAmount: Double
        get() = config.double("monitoring.rapid-income-amount", 250_000.0).coerceAtLeast(0.0)

    open val rapidIncomeTransactions: Int
        get() = config.integer("monitoring.rapid-income-transactions", 40).coerceAtLeast(1)

    open val anomalyCooldownSeconds: Int
        get() = config.integer("monitoring.anomaly-cooldown-seconds", 300).coerceAtLeast(1)

    /** Runtime/persisted guard for the owner policy that the admin shop never buys Slimefun items. */
    open val slimefunBuyOnlyPolicyEnabled: Boolean
        get() = config.bool("monitoring.policies.slimefun-buy-only.enabled", false)

    /** Exact production activation boundary; older ledger events are not policy violations. */
    open val slimefunBuyOnlyPolicyActivatedAt: Long
        get() = config.long("monitoring.policies.slimefun-buy-only.activated-at", 0L).coerceAtLeast(0L)

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
            val config = ConfigManager.of(dataPath, "modules/audit.yml")
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
    override val storageMode: AuditStorageMode = AuditStorageMode.REDIS,
    override val shutdownTimeoutSeconds: Int = 15,
    override val migrationOwnerServer: String = "survival",
    override val migrationBatchSize: Int = 500,
    override val mysql: SqlConnectionConfig? = null,
    override val writeBatchSize: Int = 250,
    override val writeFlushIntervalMillis: Long = 250L,
    override val maximumPendingEvents: Int = 10_000,
    override val writeRetryIntervalMillis: Long = 1_000L,
    override val jobsCoalesceWindowSeconds: Int = 60,
    override val jobsCoalesceMaximumEvents: Int = 1_000,
    override val cleanupIntervalHours: Int = 24,
    override val cleanupOwnerServer: String = "survival",
    override val retentionDays: Int = 30,
    override val jobsRawRetentionDays: Int = 7,
    override val maxCompactionDaysPerRun: Int = 7,
    override val cleanupDeleteBatchSize: Int = 10_000,
    override val saveInterval: Long = 20,
    override val pruneInterval: Long = 6000,
    override val maxAgeSeconds: Int = 86400 * 30,
    override val maxWeight: Int = 100000,
    override val shardMaxWeight: Int = maxWeight,
    override val maxTransactions: Int = 50000,
    override val balanceHistoryEnabled: Boolean = false,
    override val aggregationWindowSeconds: Int = 10,
    override val monitoringEnabled: Boolean = true,
    override val largeTransactionAmount: Double = 100_000.0,
    override val rapidIncomeWindowSeconds: Int = 300,
    override val rapidIncomeAmount: Double = 250_000.0,
    override val rapidIncomeTransactions: Int = 40,
    override val anomalyCooldownSeconds: Int = 300,
    override val slimefunBuyOnlyPolicyEnabled: Boolean = false,
    override val slimefunBuyOnlyPolicyActivatedAt: Long = 0L,
    override val pageSize: Int = 20,
    override val headerFormat: String = "\n<gold>%player_name%'s Audit Data",
    override val transactionFormat: String = "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>",
    override val incomeFormat: String = "<green>+%amount%",
    override val expenseFormat: String = "<red>-%amount%",
    override val footerFormat: String = "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n",
    override val prevPageFormat: String = "<click:run_command:/arc audit %player_name% %prev_page% %filter%><hover:show_text:'Previous page'><gold><</hover></click>",
    override val nextPageFormat: String = "<click:run_command:/arc audit %player_name% %next_page% %filter%><hover:show_text:'Next page'><gold>></hover></click>",
    override val noDataMessage: String = "<red>No audit data found for %player_name%",
) : AuditConfig(ru.arc.config.EmptyConfig) {
    /**
     * Creates a copy with modified values.
     */
    fun copy(
        storageMode: AuditStorageMode = this.storageMode,
        shutdownTimeoutSeconds: Int = this.shutdownTimeoutSeconds,
        migrationOwnerServer: String = this.migrationOwnerServer,
        migrationBatchSize: Int = this.migrationBatchSize,
        mysql: SqlConnectionConfig? = this.mysql,
        writeBatchSize: Int = this.writeBatchSize,
        writeFlushIntervalMillis: Long = this.writeFlushIntervalMillis,
        maximumPendingEvents: Int = this.maximumPendingEvents,
        writeRetryIntervalMillis: Long = this.writeRetryIntervalMillis,
        jobsCoalesceWindowSeconds: Int = this.jobsCoalesceWindowSeconds,
        jobsCoalesceMaximumEvents: Int = this.jobsCoalesceMaximumEvents,
        cleanupIntervalHours: Int = this.cleanupIntervalHours,
        cleanupOwnerServer: String = this.cleanupOwnerServer,
        retentionDays: Int = this.retentionDays,
        jobsRawRetentionDays: Int = this.jobsRawRetentionDays,
        maxCompactionDaysPerRun: Int = this.maxCompactionDaysPerRun,
        cleanupDeleteBatchSize: Int = this.cleanupDeleteBatchSize,
        saveInterval: Long = this.saveInterval,
        pruneInterval: Long = this.pruneInterval,
        maxAgeSeconds: Int = this.maxAgeSeconds,
        maxWeight: Int = this.maxWeight,
        shardMaxWeight: Int = this.shardMaxWeight,
        maxTransactions: Int = this.maxTransactions,
        balanceHistoryEnabled: Boolean = this.balanceHistoryEnabled,
        aggregationWindowSeconds: Int = this.aggregationWindowSeconds,
        monitoringEnabled: Boolean = this.monitoringEnabled,
        largeTransactionAmount: Double = this.largeTransactionAmount,
        rapidIncomeWindowSeconds: Int = this.rapidIncomeWindowSeconds,
        rapidIncomeAmount: Double = this.rapidIncomeAmount,
        rapidIncomeTransactions: Int = this.rapidIncomeTransactions,
        anomalyCooldownSeconds: Int = this.anomalyCooldownSeconds,
        slimefunBuyOnlyPolicyEnabled: Boolean = this.slimefunBuyOnlyPolicyEnabled,
        slimefunBuyOnlyPolicyActivatedAt: Long = this.slimefunBuyOnlyPolicyActivatedAt,
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
            storageMode,
            shutdownTimeoutSeconds,
            migrationOwnerServer,
            migrationBatchSize,
            mysql,
            writeBatchSize,
            writeFlushIntervalMillis,
            maximumPendingEvents,
            writeRetryIntervalMillis,
            jobsCoalesceWindowSeconds,
            jobsCoalesceMaximumEvents,
            cleanupIntervalHours,
            cleanupOwnerServer,
            retentionDays,
            jobsRawRetentionDays,
            maxCompactionDaysPerRun,
            cleanupDeleteBatchSize,
            saveInterval,
            pruneInterval,
            maxAgeSeconds,
            maxWeight,
            shardMaxWeight,
            maxTransactions,
            balanceHistoryEnabled,
            aggregationWindowSeconds,
            monitoringEnabled,
            largeTransactionAmount,
            rapidIncomeWindowSeconds,
            rapidIncomeAmount,
            rapidIncomeTransactions,
            anomalyCooldownSeconds,
            slimefunBuyOnlyPolicyEnabled,
            slimefunBuyOnlyPolicyActivatedAt,
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
