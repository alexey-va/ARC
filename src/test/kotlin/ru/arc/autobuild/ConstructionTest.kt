@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase
import ru.arc.hooks.HookRegistry

class ConstructionTest : TestBase() {

    private lateinit var world: WorldMock
    private lateinit var player: PlayerMock
    private lateinit var building: Building
    private lateinit var centerBlock: Location
    private lateinit var site: ConstructionSite

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
        player = server.addPlayer("TestPlayer")
        centerBlock = Location(world, 0.0, 64.0, 0.0)
        building = Building("amogus_1.schem")
        site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
    }

    @Test
    fun testConstructionCreation() {
        val construction = Construction(site)
        assertNotNull(construction, "Construction should be created")
    }

    @Test
    fun testConstructionInitialState() {
        val construction = Construction(site)
        assertEquals(-1, construction.pointer.get(), "Pointer should start at -1")
        assertEquals(-1, construction.npcId, "NPC ID should be -1 initially")
        assertFalse(construction.lookClose, "LookClose should be false initially")
    }

    @Test
    fun testCreateNpcWithoutCitizensHook() {

        try {
            val construction = Construction(site)

            // Without Citizens hook, should return -1
            HookRegistry.citizensHook = null
            val npcId = construction.createNpc(centerBlock, 60)

            assertEquals(-1, npcId, "Should return -1 without Citizens hook")
        } catch (e: NoClassDefFoundError) {
            // Skip if WorldEdit classes not available
        }
    }

    @Test
    fun testDestroyNpcWithoutCitizensHook() {
        val construction = Construction(site)

        // Should not throw even without Citizens hook
        HookRegistry.citizensHook = null
        assertDoesNotThrow { construction.destroyNpc() }
    }
}
