
package ru.arc.autobuild

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.hooks.HookRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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

    @Test
    fun `constructed containers never generate bonus loot`() {
        listOf(Material.CHEST, Material.BARREL).forEachIndexed { index, material ->
            val location = Location(world, index * 3.0, 64.0, 0.0)
            val block = world.getBlockAt(location).also { it.type = material }

            ConstructionBlockPlacement.apply(block, material.createBlockData())

            val state = world.getBlockAt(location).state
            assertTrue(state is Container, "$material should be placed as a container")
            assertTrue((state as Container).inventory.isEmpty, "$material must remain empty after construction")
        }
    }

    @Test
    fun `cancelling while async preparation is running cannot schedule block placement`() {
        val preparationStarted = CountDownLatch(1)
        val allowPreparationToFinish = CountDownLatch(1)
        val preparationFinished = CountDownLatch(1)
        val scheduler = TestTaskScheduler(Executor { task -> Thread(task, "construction-race-test").start() })
        val construction = Construction(
            site,
            LifecycleTaskScope(scheduler),
        ) {
            preparationStarted.countDown()
            assertTrue(allowPreparationToFinish.await(2, TimeUnit.SECONDS), "Preparation release timed out")
            preparationFinished.countDown()
            mutableListOf()
        }

        construction.startBuilding()
        assertEquals(1, scheduler.pendingCount(), "Async preparation should be owned by the construction")
        scheduler.executeImmediate()
        assertTrue(preparationStarted.await(2, TimeUnit.SECONDS), "Async preparation did not start")

        construction.cancel(0)
        allowPreparationToFinish.countDown()
        assertTrue(preparationFinished.await(2, TimeUnit.SECONDS), "Async preparation did not finish")

        assertEquals(0, scheduler.pendingCount(), "Cancellation should remove the pending preparation callback")
        assertEquals(0, scheduler.timerCount(), "A cancelled construction must not attach a placement timer")
    }

    @Test
    fun `completed async preparation schedules one owned placement timer`() {
        val scheduler = TestTaskScheduler()
        val construction = Construction(
            site,
            LifecycleTaskScope(scheduler),
        ) { emptyList() }

        construction.startBuilding()
        scheduler.executeImmediate()
        scheduler.executeImmediate()

        assertEquals(0, scheduler.pendingCount(), "Preparation handoff should finish")
        assertEquals(1, scheduler.timerCount(), "Exactly one placement timer should be scheduled")

        construction.cancel(0)
        assertEquals(0, scheduler.timerCount(), "The placement timer should remain owned by the construction")
    }
}
