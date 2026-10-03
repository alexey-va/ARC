package ru.arc.treasurechests

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
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

    it("keeps the active hunt intact when replacement is disabled and hides its bar on quit") {
        val world = server.addSimpleWorld("origin-hunt-no-replace")
        val block = world.getBlockAt(30, 70, 30)
        val pool = LocationPoolManager.createPool("origin-hunt-no-replace")
        pool.addLocation(block.location)
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
        val audience = mockk<Player>(relaxed = true)
        try {
            val existing = service.startHunt(pool, 1, ChestType.vanilla("easter")).shouldNotBeNull()
            val bar = BossBar.bossBar(
                Component.text("current hunt"),
                1f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS,
            )
            existing.bossBar = bar
            existing.bossBarAudience.add(audience)

            service.startHunt(pool, 1, ChestType.vanilla("easter"), replaceExisting = false) shouldBe existing

            service.getActiveHunts() shouldBe listOf(existing)
            existing.remainingChests shouldBe 1
            existing.bossBar shouldBe bar
            existing.bossBarAudience.contains(audience) shouldBe true
            block.type shouldBe Material.CHEST
            verify(exactly = 1) { spawner.createChest(block, ChestVariant.VANILLA, null) }

            service.onPlayerQuit(audience)

            verify(exactly = 1) { audience.hideBossBar(bar) }
            existing.bossBarAudience.contains(audience) shouldBe false
        } finally {
            service.stopAll()
        }
    }

    it("replaces an active same-pool hunt when replacement is enabled") {
        val world = server.addSimpleWorld("origin-hunt-replace")
        val block = world.getBlockAt(40, 70, 40)
        val pool = LocationPoolManager.createPool("origin-hunt-replace")
        pool.addLocation(block.location)
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
            val previous = service.startHunt(pool, 1, ChestType.vanilla("easter")).shouldNotBeNull()
            val replacement = service.startHunt(pool, 1, ChestType.vanilla("easter"), replaceExisting = true).shouldNotBeNull()

            (replacement === previous) shouldBe false
            previous.chests.isEmpty() shouldBe true
            service.getActiveHunts() shouldBe listOf(replacement)
            block.type shouldBe Material.CHEST
            verify(exactly = 2) { spawner.createChest(block, ChestVariant.VANILLA, null) }
        } finally {
            service.stopAll()
        }
    }
})
