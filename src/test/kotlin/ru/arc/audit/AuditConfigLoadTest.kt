package ru.arc.audit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.configs.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that [AuditConfig] reads values correctly from YAML via ConfigSection-scoped paths.
 */
class AuditConfigLoadTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ConfigManager.clear()
        writeAuditYaml(
            """
            save-interval: 40
            prune-interval: 12000
            max-age-seconds: 604800
            max-weight: 50000
            max-transactions: 25000
            balance-history: true
            messages:
              page-size: 10
              header-format: "<blue>Аудит %player_name%"
              transaction-format: "<gray>%counter%. %type% %amount%"
              income-format: "<green>+%amount%"
              expense-format: "<red>-%amount%"
              footer-format: "Page %page%/%total_pages%"
              prev-page: "<gold>< Назад"
              next-page: "<gold>Вперёд >"
              no-audit-data: "<yellow>Нет данных для %player_name%"
            """.trimIndent()
        )
    }

    @AfterEach
    fun tearDown() {
        ConfigManager.clear()
    }

    @Test
    fun `save interval loaded from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(40L, cfg.saveInterval)
    }

    @Test
    fun `prune interval loaded from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(12000L, cfg.pruneInterval)
    }

    @Test
    fun `max age seconds loaded from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(604800, cfg.maxAgeSeconds)
    }

    @Test
    fun `max weight loaded from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(50000, cfg.maxWeight)
    }

    @Test
    fun `max transactions loaded from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(25000, cfg.maxTransactions)
    }

    @Test
    fun `balance history enabled from YAML`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(true, cfg.balanceHistoryEnabled)
    }

    @Test
    fun `messages page size loaded via section`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals(10, cfg.pageSize)
    }

    @Test
    fun `messages header format loaded via section`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals("<blue>Аудит %player_name%", cfg.headerFormat)
    }

    @Test
    fun `messages income format loaded via section`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals("<green>+%amount%", cfg.incomeFormat)
    }

    @Test
    fun `messages expense format loaded via section`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals("<red>-%amount%", cfg.expenseFormat)
    }

    @Test
    fun `messages no data loaded via section`() {
        val cfg = AuditConfig.fromFile(tempDir)
        assertEquals("<yellow>Нет данных для %player_name%", cfg.noDataMessage)
    }

    @Test
    fun `default config uses sensible defaults`() {
        val cfg = TestAuditConfig()
        assertEquals(20L, cfg.saveInterval)
        assertEquals(20, cfg.pageSize)
        assertFalse(cfg.balanceHistoryEnabled)
    }

    private fun writeAuditYaml(content: String) {
        Files.writeString(tempDir.resolve("audit.yml"), content)
    }
}
