package ru.arc.farm

import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Verifies that [FarmModuleConfig.load] reads values from the bundled `modules/farms.yml`.
 */
class FarmModuleConfigLoadTest {

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
    fun `bundled YAML has correct admin permission`() {
        val cfg = FarmModuleConfig.load(tempDir)
        assertEquals("arc.farm-admin", cfg.adminPermission)
    }

    @Test
    fun `bundled YAML has no farms in the farms map`() {
        // bundled farms.yml has farms: {} (empty map)
        val cfg = FarmModuleConfig.load(tempDir)
        assertTrue(cfg.farms.isEmpty(), "bundled farms.yml has empty farms section")
    }

    @Test
    fun `bundled YAML has no lumbermills in the lumbermills map`() {
        // bundled farms.yml has lumbermills: {} (empty map)
        val cfg = FarmModuleConfig.load(tempDir)
        assertTrue(cfg.lumbermills.isEmpty(), "bundled farms.yml has empty lumbermills section")
    }

    @Test
    fun `bundled YAML has four mines`() {
        val cfg = FarmModuleConfig.load(tempDir)
        assertEquals(4, cfg.mines.size)
    }

    @Test
    fun `mines are sorted by priority descending`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val priorities = cfg.mines.map { it.priority }
        assertEquals(priorities.sortedDescending(), priorities)
    }

    @Test
    fun `bundled mine1 has correct world and region`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val mine1 = cfg.mines.first { it.id == "mine1" }
        assertEquals("sp11", mine1.worldName)
        assertEquals("mine1", mine1.regionName)
    }

    @Test
    fun `bundled mine1 has correct permission`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val mine1 = cfg.mines.first { it.id == "mine1" }
        assertEquals("arc.mine1", mine1.permission)
    }

    @Test
    fun `bundled mine1 has correct blocks per day`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val mine1 = cfg.mines.first { it.id == "mine1" }
        assertEquals(256, mine1.maxBlocksPerDay)
    }

    @Test
    fun `bundled mine1 has stone as base material`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val mine1 = cfg.mines.first { it.id == "mine1" }
        assertEquals(Material.STONE, mine1.baseBlock)
    }

    @Test
    fun `bundled mine1 has non-empty ore weights`() {
        val cfg = FarmModuleConfig.load(tempDir)
        val mine1 = cfg.mines.first { it.id == "mine1" }
        assertFalse(mine1.oreWeights.isEmpty())
    }

    @Test
    fun `mine-config expire time defaults are set`() {
        val cfg = FarmModuleConfig.load(tempDir)
        // expire-time: 60000 from bundled YAML
        cfg.mines.forEach { mine ->
            assertEquals(60000L, mine.expireTimeMs)
        }
    }

    @Test
    fun `FarmMessages alreadyBroken loaded from mine-config section`() {
        val cfg = FarmModuleConfig.load(tempDir)
        // bundled YAML: mine-config.already-broken: <red>Этот блок еще не восстановился!
        assertTrue(cfg.messages.alreadyBroken.contains("восстановился"))
    }

    @Test
    fun `FarmMessages limitMessageCooldown loaded from farm-config section`() {
        val cfg = FarmModuleConfig.load(tempDir)
        // bundled YAML: farm-config.limit-message-cooldown: 60
        assertEquals(60, cfg.messages.limitMessageCooldown)
    }
}
