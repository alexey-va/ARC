package ru.arc.treasurechests

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Block
import ru.arc.KotestTestBase
import ru.arc.common.chests.VanillaChest
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.core.TestTaskScheduler

class TreasureHuntPlacementTest : KotestTestBase({
    it("places at free points and preserves decoration added to an authored point") {
        val world = server.addSimpleWorld("origin-hunt-placement")
        val free = world.getBlockAt(10, 70, 10)
        val occupied = world.getBlockAt(20, 70, 20)
        occupied.type = Material.STONE_BRICKS
        val pool = LocationPoolManager.createPool("origin-hunt-placement")
        pool.addLocation(free.location)
        pool.addLocation(occupied.location)
        val spawner = mockk<ChestSpawner>()
        every { spawner.createChest(any(), ChestVariant.VANILLA, null) } answers {
            VanillaChest(firstArg<Block>())
        }
        val service = TreasureHuntService(
            TreasureHuntModuleConfig.load(plugin.dataFolder.toPath()),
            TestTaskScheduler(),
            mockk(relaxed = true),
            spawner,
        )
        try {
            val hunt = service.startHunt(pool, 2, ChestType.vanilla("easter")).shouldNotBeNull()

            hunt.totalChests shouldBe 1
            free.type shouldBe Material.CHEST
            occupied.type shouldBe Material.STONE_BRICKS
            verify(exactly = 1) { spawner.createChest(free, ChestVariant.VANILLA, null) }
            verify(exactly = 0) { spawner.createChest(occupied, any(), any()) }
        } finally {
            service.stopAll()
        }
    }
})
