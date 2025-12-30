package ru.arc.configs

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ConfigManagerTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("arc-test-configs")
    }

    @Test
    fun testCreateConfig() {
        val config = ConfigManager.create(tempDir, "test.yml", "test-config")

        assertNotNull(config, "Config should be created")
        assertNotNull(ConfigManager.get("test-config"), "Config should be retrievable")
    }

    @Test
    fun testOfConfig() {
        val config1 = ConfigManager.of(tempDir, "test.yml")
        val config2 = ConfigManager.of(tempDir, "test.yml")

        assertSame(config1, config2, "Should return same config instance")
    }

    @Test
    fun testGetConfig() {
        val created = ConfigManager.create(tempDir, "test.yml", "test-config")
        val retrieved = ConfigManager.get("test-config")

        assertSame(created, retrieved, "Should retrieve the same config")
    }

    @Test
    fun testGetConfigNonExistent() {
        val config = ConfigManager.get("non-existent")
        assertNull(config, "Should return null for non-existent config")
    }

    @Test
    fun testReloadAll() {
        val initialVersion = ConfigManager.getVersion()
        ConfigManager.reloadAll()
        val newVersion = ConfigManager.getVersion()

        assertTrue(newVersion > initialVersion, "Version should increment after reload")
    }

    @Test
    fun testMultipleConfigs() {
        ConfigManager.create(tempDir, "config1.yml", "config1")
        ConfigManager.create(tempDir, "config2.yml", "config2")
        ConfigManager.create(tempDir, "config3.yml", "config3")

        assertNotNull(ConfigManager.get("config1"))
        assertNotNull(ConfigManager.get("config2"))
        assertNotNull(ConfigManager.get("config3"))
    }
}

