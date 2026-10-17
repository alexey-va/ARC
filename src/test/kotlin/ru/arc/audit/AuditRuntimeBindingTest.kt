package ru.arc.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.Config
import java.nio.file.Path

class AuditRuntimeBindingTest {
    @TempDir lateinit var root: Path

    @Test
    fun `unchanged SQL settings compare by value and live policy does not require restart`() {
        val yaml = Config(root, "audit.yml")
        yaml.setStructured("storage.mode", "sql")
        yaml.setStructured("storage.mysql.host", "localhost")
        yaml.setStructured("storage.mysql.database", "audit")
        yaml.setStructured("storage.mysql.username", "test")
        yaml.setStructured("storage.mysql.password", "test")
        val config = AuditConfig(yaml)
        val initial = AuditRuntimeBinding.capture(config, "survival")
        assertEquals(initial, AuditRuntimeBinding.capture(config, "survival"))
        yaml.setStructured("max-transactions", 100)
        yaml.setStructured("messages.page-size", 30)
        yaml.setStructured("balance-history", true)
        assertEquals(initial, AuditRuntimeBinding.capture(config, "survival"))
        yaml.setStructured("storage.mysql.writer.flush-interval-ms", 700)
        assertNotEquals(initial, AuditRuntimeBinding.capture(config, "survival"))
    }

    @Test
    fun `Redis constructor settings are guarded without guarding irrelevant SQL settings`() {
        val yaml = Config(root, "audit.yml")
        yaml.setStructured("storage.mode", "redis")
        val config = AuditConfig(yaml)
        val initial = AuditRuntimeBinding.capture(config, "survival")
        yaml.setStructured("storage.mysql.writer.flush-interval-ms", 700)
        assertEquals(initial, AuditRuntimeBinding.capture(config, "survival"))
        yaml.setStructured("save-interval", 200)
        assertNotEquals(initial, AuditRuntimeBinding.capture(config, "survival"))
    }
}
