package ru.arc.audit

import com.google.gson.Gson
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AuditWriterSettings(
    val batchSize: Int,
    val flushIntervalMillis: Long,
    val maximumPendingEvents: Int,
    val retryIntervalMillis: Long,
    val shutdownTimeoutSeconds: Long,
    val jobsCoalesceWindowMillis: Long = 60_000L,
    val jobsCoalesceMaximumEvents: Int = 1_000,
    val jobsCoalescingEnabled: Boolean = true,
) {
    init {
        require(batchSize > 0) { "Audit SQL batch size must be positive" }
        require(maximumPendingEvents >= batchSize) { "Audit SQL pending-event bound must fit one batch" }
        require(flushIntervalMillis > 0) { "Audit SQL flush interval must be positive" }
        require(retryIntervalMillis > 0) { "Audit SQL retry interval must be positive" }
        require(shutdownTimeoutSeconds > 0) { "Audit SQL shutdown timeout must be positive" }
        require(jobsCoalesceWindowMillis > 0) { "Jobs audit coalescing window must be positive" }
        require(jobsCoalesceMaximumEvents in 1..maximumPendingEvents) {
            "Jobs audit coalescing group bound must fit the pending-event bound"
        }
    }
}

data class AuditMaintenanceSettings(
    val enabled: Boolean,
    val policy: AuditRetentionPolicy,
    val intervalHours: Long,
    val maxCompactionDaysPerRun: Int,
    val deleteBatchSize: Int,
) {
    init {
        require(intervalHours > 0) { "Audit maintenance interval must be positive" }
        require(maxCompactionDaysPerRun > 0) { "Audit compaction day limit must be positive" }
        require(deleteBatchSize > 0) { "Audit deletion batch size must be positive" }
    }
}

data class AuditMaintenanceReport(
    val compactedDays: Int,
    val compactedRows: Int,
    val compactedSourceRows: Int,
    val expiredRows: Int,
)

internal data class AuditSqlRecord(
    val eventId: String,
    val playerName: String,
    val playerKey: String,
    val type: String,
    val amount: BigDecimal,
    val comment: String,
    val timestamp: Long,
    val timestamp2: Long,
    val source: String,
    val flow: String,
    val currency: String,
    val server: String,
    val origin: String,
    val occurrences: Int,
    val contextJson: String?,
    val recordKind: String = "",
    val status: String = "",
    val action: String = "",
    val compacted: Boolean = false,
)

internal object AuditSqlCodec {
    private val gson = Gson()

    fun encode(event: AuditEvent): AuditSqlRecord {
        val transaction = event.transaction
        val context = transaction.context
        require(transaction.amount.isFinite()) { "Audit amount must be finite" }
        val source = reclassifyLegacySource(transaction)
        return AuditSqlRecord(
            eventId = event.eventId,
            playerName = event.playerName.trim().take(MAX_PLAYER_NAME),
            playerKey = event.playerKey.take(MAX_PLAYER_NAME),
            type = transaction.type.name,
            amount = BigDecimal.valueOf(transaction.amount).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN),
            comment = transaction.comment.take(MAX_COMMENT),
            timestamp = transaction.timestamp,
            timestamp2 = transaction.timestamp2,
            source = source.label,
            flow = transaction.normalizedFlow.label,
            currency = transaction.normalizedCurrency.take(MAX_LABEL),
            server = transaction.normalizedServer.take(MAX_LABEL),
            origin = transaction.origin.orEmpty().take(MAX_ORIGIN),
            occurrences = transaction.occurrenceCount,
            contextJson = context?.let(gson::toJson),
            recordKind = context?.normalizedRecordKind?.name.orEmpty(),
            status = context?.normalizedStatus?.name.orEmpty(),
            action = context?.action.orEmpty().take(MAX_ACTION),
            compacted = false,
        )
    }

    /**
     * The Redis snapshot predates bounded source labels. Reclassify only unresolved rows while
     * they cross the migration boundary; explicit historical labels are never overwritten.
     */
    private fun reclassifyLegacySource(transaction: Transaction): EconomySource {
        val persisted = transaction.normalizedSource
        if (persisted !in setOf(EconomySource.UNKNOWN, EconomySource.LEGACY)) return persisted
        val rawReason = buildString {
            append(transaction.comment)
            transaction.origin?.takeIf(String::isNotBlank)?.let {
                append('\n')
                append("Call:")
                append(it)
            }
        }
        val resolved =
            EconomyAttributionResolver.resolve(
                rawReason = rawReason,
                amount = transaction.amount,
                currency = transaction.currency,
                server = transaction.server,
            ).metadata.source
        return resolved.takeUnless { it == EconomySource.UNKNOWN } ?: persisted
    }

    fun decode(row: AuditSqlRecord): AuditEvent {
        val source =
            EconomySource.entries.firstOrNull {
                it.label.equals(row.source, ignoreCase = true) || it.name.equals(row.source, ignoreCase = true)
            } ?: EconomySource.LEGACY
        val flow =
            EconomyFlow.entries.firstOrNull {
                it.label.equals(row.flow, ignoreCase = true) || it.name.equals(row.flow, ignoreCase = true)
            } ?: EconomyFlow.UNKNOWN
        val type = Type.entries.firstOrNull { it.name.equals(row.type, ignoreCase = true) } ?: Type.OTHER
        val persistedContext = row.contextJson?.takeIf(String::isNotBlank)?.let {
            gson.fromJson(it, EconomyLedgerContext::class.java)
        }
        val context =
            persistedContext ?: row.takeIf { it.compacted || it.action.isNotBlank() }?.let {
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.entries.firstOrNull { kind -> kind.name == row.recordKind },
                    status = EconomyEventStatus.entries.firstOrNull { status -> status.name == row.status },
                    action = row.action.takeIf(String::isNotBlank),
                )
            }
        return AuditEvent(
            playerName = row.playerName,
            transaction =
                Transaction(
                    type = type,
                    amount = row.amount.toDouble(),
                    comment = row.comment,
                    timestamp = row.timestamp,
                    timestamp2 = row.timestamp2,
                    source = source,
                    flow = flow,
                    currency = row.currency,
                    server = row.server,
                    origin = row.origin,
                    occurrences = row.occurrences,
                    eventId = row.eventId,
                    context = context,
                ),
        )
    }

    private const val MONEY_SCALE = 6
    private const val MAX_PLAYER_NAME = 64
    private const val MAX_COMMENT = 240
    private const val MAX_LABEL = 32
    private const val MAX_ORIGIN = 512
    private const val MAX_ACTION = 96
}

class SqlAuditEventStore private constructor(
    private val runtime: SqlRuntime,
    private val writerSettings: AuditWriterSettings,
    private val maintenanceSettings: AuditMaintenanceSettings,
    private val scheduler: ScheduledExecutorService,
    private val telemetry: AuditStorageTelemetry?,
) : AuditEventStore {
    private val closed = AtomicBoolean(false)
    private val maintenanceRunning = AtomicBoolean(false)
    private val retryAfterEpochMs = AtomicLong(0L)
    private val batcher =
        AuditWriteBatcher(
            maximumPendingEvents = writerSettings.maximumPendingEvents,
            batchSize = writerSettings.batchSize,
            sink = AuditBatchSink(::writeBatch),
        )
    private val jobsCoalescer =
        JobsAuditCoalescer(
            windowMillis = writerSettings.jobsCoalesceWindowMillis,
            maximumPendingEvents = writerSettings.maximumPendingEvents,
            maximumEventsPerGroup = writerSettings.jobsCoalesceMaximumEvents,
            enabled = writerSettings.jobsCoalescingEnabled,
            sink = ::appendToBatcher,
            onCoalesced = { inputEvents -> telemetry?.coalesced(inputEvents) },
        )

    private val pendingCount: Int get() = batcher.pendingCount + jobsCoalescer.pendingCount

    init {
        scheduler.scheduleWithFixedDelay(
            ::scheduledFlush,
            writerSettings.flushIntervalMillis,
            writerSettings.flushIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
        if (maintenanceSettings.enabled) {
            scheduler.scheduleWithFixedDelay(
                ::scheduledMaintenance,
                maintenanceSettings.intervalHours,
                maintenanceSettings.intervalHours,
                TimeUnit.HOURS,
            )
        }
    }

    override fun append(event: AuditEvent): CompletableFuture<AuditAppendResult> {
        val result = jobsCoalescer.append(event)
        if (!result.isCompletedExceptionally) telemetry?.accepted(pendingCount)
        if (batcher.pendingCount >= writerSettings.batchSize && !closed.get()) {
            scheduler.execute(::scheduledFlush)
        }
        return result
    }

    private fun appendToBatcher(event: AuditEvent): CompletableFuture<AuditAppendResult> {
        val result = batcher.append(event)
        result.thenAccept { appendResult -> telemetry?.completed(appendResult, pendingCount) }
        return result
    }

    /** Test, migration and shutdown hook; production producers never call this. */
    internal fun flushNow(): CompletableFuture<Unit> {
        jobsCoalescer.flushAll()
        return drainPending()
    }

    internal fun maintainNow(now: Instant = Instant.now()): CompletableFuture<AuditMaintenanceReport> {
        if (!maintenanceRunning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(AuditMaintenanceReport(0, 0, 0, 0))
        }
        val boundaries = maintenanceSettings.policy.boundaries(now)
        val future =
            runtime.executor.transaction { connection ->
                var compactedRows = 0
                var compactedSourceRows = 0
                val dayBuckets = mutableListOf<Long>()
                connection.prepareStatement(FIND_COMPACTION_DAYS).use { statement ->
                    statement.setLong(1, MILLIS_PER_DAY)
                    statement.setLong(
                        2,
                        boundaries.jobsCompactBeforeDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    )
                    statement.setInt(3, maintenanceSettings.maxCompactionDaysPerRun)
                    statement.executeQuery().use { result ->
                        while (result.next()) dayBuckets += result.getLong("day_bucket")
                    }
                }
                dayBuckets.forEach { dayBucket ->
                    val dayStart = dayBucket * MILLIS_PER_DAY
                    val dayEnd = dayStart + MILLIS_PER_DAY
                    val sourceRows =
                        connection.prepareStatement(COUNT_RAW_JOBS_DAY).use { statement ->
                            statement.setLong(1, dayStart)
                            statement.setLong(2, dayEnd)
                            statement.executeQuery().use { result ->
                                check(result.next()) { "Audit Jobs compaction count returned no row" }
                                result.getInt(1)
                            }
                        }
                    compactedRows +=
                        connection.prepareStatement(COMPACT_JOBS_DAY).use { statement ->
                            statement.setLong(1, dayBucket)
                            statement.setLong(2, dayStart)
                            statement.setLong(3, dayEnd)
                            statement.executeUpdate()
                        }
                    val deleted =
                        connection.prepareStatement(DELETE_RAW_JOBS_DAY).use { statement ->
                            statement.setLong(1, dayStart)
                            statement.setLong(2, dayEnd)
                            statement.executeUpdate()
                        }
                    check(deleted == sourceRows) { "Audit Jobs compaction did not delete its complete source day" }
                    compactedSourceRows += deleted
                }
                val expiredRows =
                    connection.prepareStatement(DELETE_EXPIRED).use { statement ->
                        statement.setLong(1, boundaries.deleteBeforeEpochMs)
                        statement.setInt(2, maintenanceSettings.deleteBatchSize)
                        statement.executeUpdate()
                    }
                AuditMaintenanceReport(dayBuckets.size, compactedRows, compactedSourceRows, expiredRows)
            }
        future.whenComplete { report, failure ->
            maintenanceRunning.set(false)
            if (failure == null) telemetry?.maintenance(report)
        }
        return future
    }

    override fun page(request: AuditPageRequest): CompletableFuture<AuditPage> =
        runtime.executor.read { connection ->
            val predicate = filterPredicate(request.filter)
            val total =
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM `$EVENTS_TABLE` WHERE `player_key` = ?$predicate",
                ).use { statement ->
                    statement.setString(1, request.playerKey)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "Audit count query returned no row" }
                        result.getLong(1)
                    }
                }
            val records =
                connection.prepareStatement(
                    "SELECT $SELECT_COLUMNS FROM `$EVENTS_TABLE` " +
                        "WHERE `player_key` = ?$predicate " +
                        "ORDER BY `last_at` DESC, `event_id` DESC LIMIT ? OFFSET ?",
                ).use { statement ->
                    statement.setString(1, request.playerKey)
                    statement.setInt(2, request.limit)
                    statement.setLong(3, request.offset)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) add(AuditSqlCodec.decode(readRow(result)).transaction)
                        }
                    }
                }
            AuditPage(records, total)
        }

    override fun scan(
        request: AuditScanRequest,
        consumer: AuditEventConsumer,
    ): CompletableFuture<Long> =
        runtime.executor.read { connection ->
            val serverPredicate = if (request.normalizedServerFilter == null) "" else " AND `server` = ?"
            connection.prepareStatement(
                "SELECT $SELECT_COLUMNS FROM `$EVENTS_TABLE` " +
                    "WHERE `last_at` >= ? AND `occurred_at` <= ?$serverPredicate " +
                    "ORDER BY `occurred_at` ASC, `event_id` ASC",
            ).use { statement ->
                statement.setLong(1, request.sinceEpochMs)
                statement.setLong(2, request.untilEpochMs)
                request.normalizedServerFilter?.let { statement.setString(3, it) }
                var count = 0L
                statement.fetchSize = 1_000
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        consumer.accept(AuditSqlCodec.decode(readRow(result)))
                        count++
                    }
                }
                count
            }
        }

    override fun count(): CompletableFuture<Long> =
        runtime.executor.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$EVENTS_TABLE`").use { result ->
                    check(result.next()) { "Audit count query returned no row" }
                    result.getLong(1)
                }
            }
        }

    override fun clearPlayer(playerName: String): CompletableFuture<Int> =
        runtime.executor.write { connection ->
            connection.prepareStatement("DELETE FROM `$EVENTS_TABLE` WHERE `player_key` = ?").use { statement ->
                statement.setString(1, playerName.trim().lowercase())
                statement.executeUpdate()
            }
        }

    override fun clearAll(): CompletableFuture<Int> =
        runtime.executor.write { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate("DELETE FROM `$EVENTS_TABLE`") }
        }

    override fun prune(beforeEpochMs: Long): CompletableFuture<Int> {
        require(beforeEpochMs >= 0L) { "Audit retention boundary must not be negative" }
        return runtime.executor.write { connection ->
            connection.prepareStatement(
                "DELETE FROM `$EVENTS_TABLE` WHERE `last_at` < ? ORDER BY `last_at` ASC LIMIT ?",
            ).use { statement ->
                statement.setLong(1, beforeEpochMs)
                statement.setInt(2, maintenanceSettings.deleteBatchSize)
                statement.executeUpdate()
            }
        }
    }

    override fun status(): CompletableFuture<AuditStorageStatus> =
        runtime.health().thenCompose { health ->
            if (!health.ready) {
                CompletableFuture.completedFuture(
                    AuditStorageStatus(
                        mode = AuditStorageMode.SQL,
                        ready = false,
                        schemaVersion = SCHEMA_VERSION,
                        detail = health.detail,
                    ),
                )
            } else {
                count().thenApply { eventCount ->
                    AuditStorageStatus(
                        mode = AuditStorageMode.SQL,
                        ready = true,
                        schemaVersion = SCHEMA_VERSION,
                        eventCount = eventCount,
                        sqlEventCount = eventCount,
                            pendingEvents = pendingCount,
                            detail = "pending=$pendingCount, jobsPending=${jobsCoalescer.pendingCount}",
                    )
                }
            }
        }

    internal fun insertBatch(events: Collection<AuditEvent>): CompletableFuture<Pair<Int, Int>> {
        if (events.isEmpty()) return CompletableFuture.completedFuture(0 to 0)
        return writeBatch(events.toList()).thenApply { results ->
            val inserted = results.count(AuditAppendResult::inserted)
            inserted to (results.size - inserted)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobsCoalescer.stopAccepting()
        jobsCoalescer.flushAll()
        batcher.stopAccepting()
        scheduler.shutdown()
        runCatching { flushNow().get(writerSettings.shutdownTimeoutSeconds, TimeUnit.SECONDS) }
        scheduler.shutdownNow()
        runtime.close()
    }

    private fun writeBatch(events: List<AuditEvent>): CompletableFuture<List<AuditAppendResult>> {
        if (events.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        val rows = events.map(AuditSqlCodec::encode)
        return runtime.executor.transaction { connection ->
            connection.prepareStatement(INSERT_EVENT).use { statement ->
                rows.forEach { row ->
                    bind(statement, row)
                    statement.addBatch()
                }
                statement.executeBatch().map { changed ->
                    check(changed != Statement.EXECUTE_FAILED) { "Audit SQL batch execution failed" }
                    AuditAppendResult(inserted = changed > 0 || changed == Statement.SUCCESS_NO_INFO)
                }
            }
        }
    }

    private fun drainPending(): CompletableFuture<Unit> =
        batcher.flush().thenCompose {
            if (batcher.pendingCount == 0) CompletableFuture.completedFuture(Unit) else drainPending()
        }

    private fun scheduledFlush() {
        if (closed.get()) return
        jobsCoalescer.flushDue()
        if (batcher.pendingCount == 0 || System.currentTimeMillis() < retryAfterEpochMs.get()) return
        batcher.flush().whenComplete { _, failure ->
            if (failure == null) {
                retryAfterEpochMs.set(0L)
                if (batcher.pendingCount >= writerSettings.batchSize && !closed.get()) scheduler.execute(::scheduledFlush)
            } else {
                retryAfterEpochMs.set(System.currentTimeMillis() + writerSettings.retryIntervalMillis)
                telemetry?.retryFailure(pendingCount)
            }
        }
    }

    private fun scheduledMaintenance() {
        if (!closed.get()) maintainNow()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val EVENTS_TABLE = "arc_audit_events"
        private const val MILLIS_PER_DAY = 86_400_000L
        private val SELECT_COLUMNS =
            listOf(
                "event_id", "player_name", "player_key", "type", "amount", "reason", "occurred_at", "last_at",
                "source", "flow", "currency", "server", "origin", "occurrences", "context_json", "record_kind",
                "status", "action", "compacted",
            ).joinToString(", ") { "`$it`" }
        private val INSERT_EVENT =
            "INSERT IGNORE INTO `$EVENTS_TABLE` " +
                "(`event_id`, `player_name`, `player_key`, `type`, `amount`, `reason`, `occurred_at`, `last_at`, " +
                "`source`, `flow`, `currency`, `server`, `origin`, `occurrences`, `context_json`, `record_kind`, " +
                "`status`, `action`, `compacted`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private val CREATE_EVENTS =
            """
            CREATE TABLE IF NOT EXISTS `$EVENTS_TABLE` (
                `event_id` VARCHAR(64) NOT NULL,
                `player_name` VARCHAR(64) NOT NULL,
                `player_key` VARCHAR(64) NOT NULL,
                `type` VARCHAR(32) NOT NULL,
                `amount` DECIMAL(24,6) NOT NULL,
                `reason` VARCHAR(240) NOT NULL,
                `occurred_at` BIGINT NOT NULL,
                `last_at` BIGINT NOT NULL,
                `source` VARCHAR(32) NOT NULL,
                `flow` VARCHAR(32) NOT NULL,
                `currency` VARCHAR(32) NOT NULL,
                `server` VARCHAR(32) NOT NULL,
                `origin` VARCHAR(512) NOT NULL,
                `occurrences` INT UNSIGNED NOT NULL,
                `context_json` MEDIUMTEXT NULL,
                `record_kind` VARCHAR(32) NOT NULL,
                `status` VARCHAR(32) NOT NULL,
                `action` VARCHAR(96) NOT NULL,
                `compacted` BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (`event_id`),
                KEY `arc_audit_player_page_idx` (`player_key`, `last_at` DESC, `event_id`),
                KEY `arc_audit_retention_idx` (`last_at`),
                KEY `arc_audit_server_time_idx` (`server`, `occurred_at`),
                KEY `arc_audit_source_compact_time_idx` (`source`, `compacted`, `occurred_at`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent()
        private const val FIND_COMPACTION_DAYS =
            "SELECT DISTINCT FLOOR(`occurred_at` / ?) AS `day_bucket` FROM `$EVENTS_TABLE` " +
                "WHERE `source` = 'jobs' AND `compacted` = FALSE AND `occurred_at` < ? " +
                "ORDER BY `day_bucket` ASC LIMIT ?"
        private const val COUNT_RAW_JOBS_DAY =
            "SELECT COUNT(*) FROM `$EVENTS_TABLE` WHERE `source` = 'jobs' AND `compacted` = FALSE " +
                "AND `occurred_at` >= ? AND `occurred_at` < ?"
        private const val COMPACT_JOBS_DAY =
            "INSERT IGNORE INTO `$EVENTS_TABLE` " +
                "(`event_id`, `player_name`, `player_key`, `type`, `amount`, `reason`, `occurred_at`, `last_at`, " +
                "`source`, `flow`, `currency`, `server`, `origin`, `occurrences`, `context_json`, `record_kind`, " +
                "`status`, `action`, `compacted`) " +
                "SELECT SHA2(CONCAT_WS('|', 'jobs-day-v1', ?, `player_key`, `type`, `flow`, `currency`, `server`, " +
                "`record_kind`, `status`, `action`), 256), MIN(`player_name`), `player_key`, `type`, SUM(`amount`), " +
                "'Compacted Jobs daily audit', MIN(`occurred_at`), MAX(`last_at`), `source`, `flow`, `currency`, " +
                "`server`, 'daily_compaction', SUM(`occurrences`), NULL, `record_kind`, `status`, `action`, TRUE " +
                "FROM `$EVENTS_TABLE` WHERE `source` = 'jobs' AND `compacted` = FALSE " +
                "AND `occurred_at` >= ? AND `occurred_at` < ? " +
                "GROUP BY `player_key`, `type`, `source`, `flow`, `currency`, `server`, `record_kind`, `status`, `action`"
        private const val DELETE_RAW_JOBS_DAY =
            "DELETE FROM `$EVENTS_TABLE` WHERE `source` = 'jobs' AND `compacted` = FALSE " +
                "AND `occurred_at` >= ? AND `occurred_at` < ?"
        private const val DELETE_EXPIRED =
            "DELETE FROM `$EVENTS_TABLE` WHERE `last_at` < ? ORDER BY `last_at` ASC LIMIT ?"

        fun open(
            config: SqlConnectionConfig,
            runtimeName: String = "ARC-audit",
            writerSettings: AuditWriterSettings = AuditWriterSettings(250, 250, 10_000, 1_000, 10),
            maintenanceSettings: AuditMaintenanceSettings =
                AuditMaintenanceSettings(true, AuditRetentionPolicy(30, 7), 24, 7, 10_000),
            telemetry: AuditStorageTelemetry? = null,
        ): SqlAuditEventStore {
            val runtime = SqlRuntime.create(config, runtimeName)
            return runCatching {
                MySqlMigrator(runtime.dataSource, "arc_audit").migrate(
                    listOf(
                        SqlMigration(
                            version = SCHEMA_VERSION,
                            description = "create bounded append-only economy audit events",
                            statements = listOf(CREATE_EVENTS),
                        ),
                    ),
                )
                val scheduler =
                    Executors.newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "$runtimeName-maintenance").apply { isDaemon = true }
                    }
                SqlAuditEventStore(runtime, writerSettings, maintenanceSettings, scheduler, telemetry)
            }.getOrElse { failure ->
                runtime.close()
                throw failure
            }
        }

        private fun bind(statement: PreparedStatement, row: AuditSqlRecord) {
            statement.setString(1, row.eventId)
            statement.setString(2, row.playerName)
            statement.setString(3, row.playerKey)
            statement.setString(4, row.type)
            statement.setBigDecimal(5, row.amount)
            statement.setString(6, row.comment)
            statement.setLong(7, row.timestamp)
            statement.setLong(8, row.timestamp2)
            statement.setString(9, row.source)
            statement.setString(10, row.flow)
            statement.setString(11, row.currency)
            statement.setString(12, row.server)
            statement.setString(13, row.origin)
            statement.setInt(14, row.occurrences)
            if (row.contextJson == null) statement.setNull(15, Types.LONGVARCHAR) else statement.setString(15, row.contextJson)
            statement.setString(16, row.recordKind)
            statement.setString(17, row.status)
            statement.setString(18, row.action)
            statement.setBoolean(19, row.compacted)
        }

        private fun readRow(result: ResultSet): AuditSqlRecord =
            AuditSqlRecord(
                eventId = result.getString("event_id"),
                playerName = result.getString("player_name"),
                playerKey = result.getString("player_key"),
                type = result.getString("type"),
                amount = result.getBigDecimal("amount"),
                comment = result.getString("reason"),
                timestamp = result.getLong("occurred_at"),
                timestamp2 = result.getLong("last_at"),
                source = result.getString("source"),
                flow = result.getString("flow"),
                currency = result.getString("currency"),
                server = result.getString("server"),
                origin = result.getString("origin"),
                occurrences = result.getInt("occurrences"),
                contextJson = result.getString("context_json"),
                recordKind = result.getString("record_kind"),
                status = result.getString("status"),
                action = result.getString("action"),
                compacted = result.getBoolean("compacted"),
            )

        private fun filterPredicate(filter: AuditFilter): String =
            when (filter) {
                AuditFilter.ALL -> ""
                AuditFilter.INCOME -> " AND `amount` > 0"
                AuditFilter.EXPENSE -> " AND `amount` < 0"
                AuditFilter.SHOP -> " AND `type` = 'SHOP'"
                AuditFilter.JOB -> " AND `type` = 'JOB'"
                AuditFilter.PAY -> " AND `type` = 'PAY'"
            }
    }
}
