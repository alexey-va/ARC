package ru.arc.mobspawn

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Verifies that [MobSpawnConfig.load] reads values from the bundled `modules/mobspawn.yml`.
 */
class MobSpawnConfigLoadTest {

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
    fun `bundled YAML disables module by default`() {
        val config = MobSpawnConfig.load(tempDir)
        assertFalse(config.enabled, "bundled mobspawn.yml has enabled: false")
    }

    @Test
    fun `bundled YAML has correct spawn window`() {
        val config = MobSpawnConfig.load(tempDir)
        assertEquals(13, config.startHour)
        assertEquals(0, config.endHour)
    }

    @Test
    fun `bundled YAML has correct spawn parameters`() {
        val config = MobSpawnConfig.load(tempDir)
        assertEquals(10 * 20L, config.intervalTicks)   // interval: 10 → 200 ticks
        assertEquals(50.0, config.radius)
        assertEquals(5, config.threshold)
        assertEquals(2, config.amount)
        assertEquals(30, config.tryMultiplier)
        assertEquals(7, config.maxLightLevel)
    }

    @Test
    fun `bundled YAML has empty mob list`() {
        val config = MobSpawnConfig.load(tempDir)
        assertEquals(emptySet<Any>(), config.trackedMobTypes)
    }

    @Test
    fun `bundled YAML enables CMI command with default spread`() {
        val config = MobSpawnConfig.load(tempDir)
        assertEquals(true, config.useCmiCommand)
        assertEquals(30, config.cmiSpread)
    }

    @Test
    fun `same load call returns cached config instance`() {
        val c1 = MobSpawnConfig.load(tempDir)
        val c2 = MobSpawnConfig.load(tempDir)
        assertEquals(c1.enabled, c2.enabled)
    }
}
