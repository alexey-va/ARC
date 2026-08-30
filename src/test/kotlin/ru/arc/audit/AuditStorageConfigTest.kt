package ru.arc.audit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

class AuditStorageConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ConfigManager.clear()
    }

    @AfterEach
    fun tearDown() {
        ConfigManager.clear()
    }

    @Test
    fun `SQL storage config builds a bounded redacted connection`() {
        writeAuditYaml(
            """
            storage:
              mode: sql
              shutdown-timeout-seconds: 17
              mysql:
                host: db.internal
                port: 3307
                database: common
                username: arc_audit
                password: unit-test-secret
                ssl-mode: REQUIRED
                pool:
                  minimum-idle: 0
                  maximum-size: 3
                  connection-timeout-ms: 4000
                  socket-timeout-ms: 9000
                  validation-timeout-ms: 1000
                  max-lifetime-ms: 900000
                writer:
                  batch-size: 250
                  flush-interval-ms: 250
                  maximum-pending-events: 12000
                  retry-interval-ms: 1500
                  jobs-coalesce-window-seconds: 75
                  jobs-coalesce-maximum-events: 900
              migration:
                owner-server: survival
                batch-size: 750
              cleanup:
                owner-server: survival
                interval-hours: 24
                retention-days: 30
                jobs-raw-retention-days: 7
                max-compaction-days-per-run: 5
                delete-batch-size: 10000
            """.trimIndent(),
        )

        val config = AuditConfig.fromFile(tempDir)

        assertEquals(AuditStorageMode.SQL, config.storageMode)
        assertEquals(17, config.shutdownTimeoutSeconds)
        assertEquals("survival", config.migrationOwnerServer)
        assertEquals(750, config.migrationBatchSize)
        assertEquals(250, config.writeBatchSize)
        assertEquals(250L, config.writeFlushIntervalMillis)
        assertEquals(12_000, config.maximumPendingEvents)
        assertEquals(1_500L, config.writeRetryIntervalMillis)
        assertEquals(75, config.jobsCoalesceWindowSeconds)
        assertEquals(900, config.jobsCoalesceMaximumEvents)
        assertEquals(24, config.cleanupIntervalHours)
        assertEquals("survival", config.cleanupOwnerServer)
        assertEquals(30, config.retentionDays)
        assertEquals(7, config.jobsRawRetentionDays)
        assertEquals(5, config.maxCompactionDaysPerRun)
        assertEquals(10_000, config.cleanupDeleteBatchSize)
        val mysql = requireNotNull(config.mysql)
        assertEquals("db.internal", mysql.host)
        assertEquals(3307, mysql.port)
        assertEquals("common", mysql.database)
        assertEquals(3, mysql.maximumPoolSize)
        assertFalse(mysql.toString().contains("unit-test-secret"))
        assertTrue(mysql.toString().contains("password=<redacted>"))
    }

    @Test
    fun `Redis mode does not require SQL credentials`() {
        writeAuditYaml("storage:\n  mode: redis")

        val config = AuditConfig.fromFile(tempDir)

        assertEquals(AuditStorageMode.REDIS, config.storageMode)
        assertNull(config.mysql)
    }

    @Test
    fun `invalid storage mode fails closed`() {
        writeAuditYaml("storage:\n  mode: memory")

        val config = AuditConfig.fromFile(tempDir)

        assertThrows(IllegalArgumentException::class.java) { config.storageMode }
    }

    @Test
    fun `migration and shutdown bounds are enforced`() {
        writeAuditYaml(
            """
            storage:
              mode: redis
              shutdown-timeout-seconds: 500
              migration:
                batch-size: 2
              mysql:
                writer:
                  batch-size: 50000
                  flush-interval-ms: 1
                  maximum-pending-events: 10
                  retry-interval-ms: 1
              cleanup:
                interval-hours: 100
                retention-days: 2
                jobs-raw-retention-days: 90
                max-compaction-days-per-run: 100
                delete-batch-size: 2
            """.trimIndent(),
        )

        val config = AuditConfig.fromFile(tempDir)

        assertEquals(60, config.shutdownTimeoutSeconds)
        assertEquals(100, config.migrationBatchSize)
        assertEquals(1_000, config.writeBatchSize)
        assertEquals(25L, config.writeFlushIntervalMillis)
        assertEquals(1_000, config.maximumPendingEvents)
        assertEquals(100L, config.writeRetryIntervalMillis)
        assertEquals(24, config.cleanupIntervalHours)
        assertEquals(7, config.retentionDays)
        assertEquals(7, config.jobsRawRetentionDays)
        assertEquals(31, config.maxCompactionDaysPerRun)
        assertEquals(1_000, config.cleanupDeleteBatchSize)
    }

    @Test
    fun `page request derives an indexed offset without loading full history`() {
        val request = AuditPageRequest("Player", page = 3, pageSize = 20, filter = AuditFilter.JOB)

        assertEquals("player", request.playerKey)
        assertEquals(40L, request.offset)
        assertEquals(20, request.limit)
        assertEquals(AuditFilter.JOB, request.filter)
    }

    @Test
    fun `audit event requires and preserves one stable event id`() {
        val transaction = Transaction(Type.JOB, 4.25, "Deposit", eventId = "event-123")

        val event = AuditEvent("Player", transaction)

        assertEquals("Player", event.playerName)
        assertEquals("player", event.playerKey)
        assertEquals("event-123", event.eventId)
        assertEquals("event-123", event.transaction.eventId)
    }

    @Test
    fun `audit event rejects a missing event id`() {
        val transaction = Transaction(Type.JOB, 4.25, "Deposit", eventId = null)

        assertThrows(IllegalArgumentException::class.java) {
            AuditEvent("Player", transaction)
        }
    }

    private fun writeAuditYaml(content: String) {
        val modules = Files.createDirectories(tempDir.resolve("modules"))
        Files.writeString(modules.resolve("audit.yml"), content)
    }
}
