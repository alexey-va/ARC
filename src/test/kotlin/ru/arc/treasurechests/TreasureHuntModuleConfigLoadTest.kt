package ru.arc.treasurechests

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Particle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.configs.ConfigManager
import java.nio.file.Path

/**
 * Verifies that [TreasureHuntModuleConfig.load] reads values from the bundled `modules/treasure-hunt.yml`.
 */
class TreasureHuntModuleConfigLoadTest {

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
    fun `bundled YAML exposes easter hunt type`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val types = cfg.loadHuntTypes()
        assertTrue(types.containsKey("easter"), "Expected 'easter' hunt type from bundled YAML")
    }

    @Test
    fun `easter hunt type has correct location pool`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val easter = cfg.loadHuntTypes()["easter"]!!
        assertEquals("spawn", easter.locationPoolId)
    }

    @Test
    fun `easter hunt type has correct boss bar settings`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val easter = cfg.loadHuntTypes()["easter"]!!
        assertTrue(easter.bossBar.visible)
        assertEquals(BossBar.Color.GREEN, easter.bossBar.color)
    }

    @Test
    fun `easter hunt type has correct chest types count`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val easter = cfg.loadHuntTypes()["easter"]!!
        // Bundled YAML has keys 2..6 (5 chest types)
        assertEquals(5, easter.chestTypes.size())
    }

    @Test
    fun `easter hunt type announces start globally`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val easter = cfg.loadHuntTypes()["easter"]!!
        assertTrue(easter.announcements.announceStartGlobally)
        assertTrue(easter.announcements.announceStart)
    }

    @Test
    fun `easter hunt type has correct TTL`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val easter = cfg.loadHuntTypes()["easter"]!!
        assertEquals(3600L, easter.timeoutSeconds)
    }

    @Test
    fun `bundled YAML has correct aliases`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val aliases = cfg.aliases
        assertTrue(aliases.containsKey("easter"))
        assertTrue(aliases.containsKey("pot"))
    }

    @Test
    fun `particle settings idle ticks loaded correctly`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        assertEquals(5L, cfg.particles.idleTicks)
    }

    @Test
    fun `particle settings player sound each loaded correctly`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        assertEquals(1, cfg.particles.playerSoundEach)
    }

    @Test
    fun `particle settings default idle uses FLAME`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val idle = cfg.particles.getIdleConfig("default")
        assertEquals(Particle.FLAME, idle.particle)
        assertEquals(20, idle.count)
    }

    @Test
    fun `particle settings halloween idle uses END_ROD`() {
        val cfg = TreasureHuntModuleConfig.load(tempDir)
        val idle = cfg.particles.getIdleConfig("halloween")
        assertEquals(Particle.END_ROD, idle.particle)
        assertEquals(10, idle.count)
    }
}
