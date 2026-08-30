package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.time.Instant

class SqlAuditEventStoreIntegrationTest : FreeSpec({
    lateinit var mysql: MySqlTestService
    lateinit var connection: SqlConnectionConfig

    beforeSpec {
        mysql = MySqlTestService.start(MySqlTestSettings(database = "arc_audit_test"))
        connection =
            SqlConnectionConfig(
                host = mysql.endpoint.host,
                port = mysql.endpoint.port,
                database = mysql.endpoint.database,
                username = mysql.endpoint.username,
                password = mysql.endpoint.password,
                sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0,
                maximumPoolSize = 2,
            )
    }

    afterSpec { mysql.close() }

    fun open(
        retentionDays: Int = 30,
        jobsRawDays: Int = 7,
    ): SqlAuditEventStore =
        SqlAuditEventStore.open(
            config = connection,
            runtimeName = "ARC-audit-integration",
            writerSettings =
                AuditWriterSettings(
                    batchSize = 10,
                    flushIntervalMillis = 3_600_000,
                    maximumPendingEvents = 100,
                    retryIntervalMillis = 100,
                    shutdownTimeoutSeconds = 5,
                    jobsCoalesceMaximumEvents = 100,
                ),
            maintenanceSettings =
                AuditMaintenanceSettings(
                    enabled = true,
                    policy = AuditRetentionPolicy(retentionDays, jobsRawDays),
                    intervalHours = 24,
                    maxCompactionDaysPerRun = 31,
                    deleteBatchSize = 1_000,
                ),
        )

    fun event(
        id: String,
        amount: Double,
        at: Long,
        player: String = "Player",
        source: EconomySource = EconomySource.JOBS,
        server: String = "survival",
        type: Type = source.type,
    ) =
        AuditEvent(
            player,
            Transaction(
                type = type,
                amount = amount,
                comment = "Deposit",
                timestamp = at,
                timestamp2 = at,
                source = source,
                flow = if (amount > 0.0) EconomyFlow.MINT else EconomyFlow.BURN,
                currency = "vault",
                server = server,
                origin = "integration-test",
                eventId = id,
                context = EconomyLedgerContext(action = if (source == EconomySource.JOBS) "job_reward" else null),
            ),
        )

    "producer writes are batched and duplicate replay stays idempotent" {
        open().use { store ->
            val at = Instant.parse("2026-08-30T10:00:00Z").toEpochMilli()
            val first = store.append(event("batch-one", 4.25, at))
            val second = store.append(event("batch-two", 2.00, at + 1))

            store.count().join() shouldBe 0L
            store.flushNow().join()

            first.join().inserted shouldBe true
            second.join().inserted shouldBe true
            store.count().join() shouldBe 2L

            val duplicate = store.append(event("batch-one", 4.25, at))
            store.flushNow().join()
            duplicate.join().inserted shouldBe false
            store.count().join() shouldBe 2L
        }
    }

    "indexed pages filters and chronological scans preserve event order" {
        open().use { store ->
            store.clearAll().join()
            val at = Instant.parse("2026-08-30T11:00:00Z").toEpochMilli()
            listOf(
                event("page-one", 1.0, at, type = Type.JOB),
                event("page-two", -2.0, at + 1, type = Type.PAY),
                event("page-three", 3.0, at + 2, type = Type.JOB),
            ).forEach(store::append)
            store.flushNow().join()

            val page = store.page(AuditPageRequest("Player", 1, 10, AuditFilter.JOB)).join()
            page.totalRecords shouldBe 2L
            page.records.map(Transaction::eventId) shouldContainExactly listOf("page-three", "page-one")

            val scanned = mutableListOf<String>()
            store.scan(AuditScanRequest(at, at + 10, "survival")) { scanned += it.eventId }.join() shouldBe 3L
            scanned shouldContainExactly listOf("page-one", "page-two", "page-three")
        }
    }

    "daily maintenance compacts old Jobs rows and deletes expired non Jobs rows" {
        open(retentionDays = 30, jobsRawDays = 7).use { store ->
            store.clearAll().join()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val oldJobs = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()
            val expired = Instant.parse("2026-07-01T10:00:00Z").toEpochMilli()
            store.append(event("jobs-old-one", 1.25, oldJobs))
            store.append(event("jobs-old-two", 2.75, oldJobs + 1))
            store.append(event("expired-shop", 9.0, expired, source = EconomySource.SHOP, type = Type.SHOP))
            store.flushNow().join()

            val report = store.maintainNow(now).join()

            report.compactedDays shouldBe 1
            report.compactedRows shouldBe 1
            report.compactedSourceRows shouldBe 2
            report.expiredRows shouldBe 1
            store.count().join() shouldBe 1L
            val compacted = store.page(AuditPageRequest("Player", 1, 10, AuditFilter.JOB)).join().records.single()
            compacted.amount shouldBe 4.0
            compacted.occurrenceCount shouldBe 2
            compacted.eventId?.length shouldBe 64
        }
    }

    "schema migration is safe to reopen" {
        open().close()
        open().use { reopened ->
            reopened.status().join().schemaVersion shouldBe SqlAuditEventStore.SCHEMA_VERSION
            reopened.status().join().ready shouldBe true
        }
    }
})
