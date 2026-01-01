@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

class DisplayTest : TestBase() {

    private lateinit var world: WorldMock
    private lateinit var player: PlayerMock
    private lateinit var building: Building
    private lateinit var centerBlock: Location
    private lateinit var site: ConstructionSite

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        assertNotNull(plugin, "Plugin must be loaded for tests")
        world = server.addSimpleWorld("test-world")
        player = server.addPlayer("TestPlayer")
        centerBlock = Location(world, 0.0, 64.0, 0.0)
        building = Building("amogus_1.schem")
        site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
    }

    @Test
    fun testDisplayCreation() {
        val display = Display(site)
        assertNotNull(display, "Display should be created")
    }

    @Test
    fun testDisplayCreatedSuccessfully() {
        val display = Display(site)
        // Display should be created without errors
        assertNotNull(display)
    }

    @Test
    fun testShowBorder() {

        val display = Display(site)
        assertDoesNotThrow { display.showBorder(60) }
    }

    @Test
    fun testStop() {

        val display = Display(site)
        display.showBorder(60)
        assertDoesNotThrow { display.stop() }
    }

    @Test
    fun testStopWithoutStart() {
        val display = Display(site)
        // Should not throw even if never started
        assertDoesNotThrow { display.stop() }
    }

    @Test
    fun testShowBorderMultipleTimes() {
        val display = Display(site)
        // Should be able to call showBorder multiple times
        assertDoesNotThrow {
            display.showBorder(60)
            display.showBorder(30)
            display.showBorder(10)
        }
    }
}
