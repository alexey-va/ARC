package ru.arc.common.locationpools

import org.bukkit.Particle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import java.nio.file.Path

/**
 * Verifies that [LocationPoolModuleConfig.load] reads values from the bundled `modules/location-pools.yml`.
 */
class LocationPoolConfigLoadTest {

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
    fun `bundled YAML has correct message for start editing`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        val msg = cfg.messages.startEditing
        assertEquals("<green>Начато редактирование пула локаций %name%!", msg)
    }

    @Test
    fun `bundled YAML has correct message for cancel editing`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        assertEquals("<red>Редактирование пула локаций %name% отменено!", cfg.messages.cancelEditing)
    }

    @Test
    fun `bundled YAML has correct message for block added`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        assertEquals("<green>Блок добавлен!", cfg.messages.blockAdded)
    }

    @Test
    fun `bundled YAML has correct message for block removed`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        assertEquals("<green>Блок удален!", cfg.messages.blockRemoved)
    }

    @Test
    fun `bundled YAML has correct editor particle intervals`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        val editor = cfg.editorSettings
        assertEquals(5L, editor.particleShowIntervalTicks)
        assertEquals(10L, editor.particleShowDelayTicks)
        assertEquals(1200L, editor.timeoutCheckIntervalTicks)
        assertEquals(10L, editor.timeoutCheckDelayTicks)
    }

    @Test
    fun `bundled YAML has correct editor nearby radius`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        assertEquals(50.0, cfg.editorSettings.nearbyRadius)
    }

    @Test
    fun `bundled YAML has correct editor particle type`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        assertEquals(Particle.END_ROD, cfg.editorSettings.particleType)
    }

    @Test
    fun `bundled YAML has correct editor particle count and offsets`() {
        val cfg = LocationPoolModuleConfig.load(tempDir)
        val editor = cfg.editorSettings
        assertEquals(10, editor.particleCount)
        assertEquals(0.0, editor.particleExtra)
        assertEquals(0.1, editor.particleOffset)
    }
}
