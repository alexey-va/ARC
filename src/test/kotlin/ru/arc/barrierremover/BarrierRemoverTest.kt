package ru.arc.barrierremover

import de.tr7zw.changeme.nbtapi.iface.ReadableItemNBT
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.IdentityHashMap
import java.util.UUID

class BarrierRemoverTest : FreeSpec({
    "registered handler accepts byte and integer marker presence even when native block use starts denied" {
        scenario { fixture ->
            val block = spyk(fixture.world.getBlockAt(2, 65, 2))
            val player = fixture.player()
            val observed = mutableListOf<BlockBreakEvent>()
            fixture.breakListener { observed += it }
            listOf<Number>(1.toByte(), 1, 0.toByte(), 0).forEach { value ->
                val tool = fixture.item(value)
                every { player.inventory.itemInMainHand } returns tool
                block.type = Material.BARRIER
                val event = fixture.interact(player, block).apply { setUseInteractedBlock(Event.Result.DENY) }
                event.isCancelled shouldBe true
                event.useItemInHand() shouldBe Event.Result.DEFAULT

                fixture.paper.callEvent(event)

                block.type shouldBe Material.AIR
                event.useItemInHand() shouldBe Event.Result.DENY
                tool.amount shouldBe 1
                observed.last().isDropItems shouldBe false
                observed.last().expToDrop shouldBe 0
                fixture.scheduler.tick(2)
            }
            observed.size shouldBe 4
            verify(exactly = 4) { block.setType(Material.AIR, false) }
            verify(exactly = 4) { player.playSound(any<Location>(), "minecraft:block.glass.break", 0.8f, 1.2f) }
            fixture.world.getEntitiesByClass(org.bukkit.entity.Item::class.java).size shouldBe 0
        }
    }

    "offhand unmarked other blocks explicit interaction veto and stale held items cannot remove a barrier" {
        scenario { fixture ->
            val block = fixture.world.getBlockAt(2, 65, 2).apply { type = Material.BARRIER }
            val player = fixture.player()
            var breaks = 0
            fixture.breakListener { breaks++ }
            fixture.paper.callEvent(fixture.interact(player, block, EquipmentSlot.OFF_HAND))
            fixture.paper.callEvent(fixture.interact(player, block, action = Action.LEFT_CLICK_BLOCK))
            fixture.paper.callEvent(fixture.interact(player, block).apply { isCancelled = true })
            fixture.paper.callEvent(fixture.interact(player, block).apply { setUseItemInHand(Event.Result.DENY) })
            block.type shouldBe Material.BARRIER

            val stale = fixture.interact(player, block)
            val ordinary = fixture.item(null).apply { editMeta { it.displayName(Component.text("Удалитель барьеров")) } }
            every { player.inventory.itemInMainHand } returns ordinary
            fixture.paper.callEvent(stale)
            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.BARRIER
            every { player.inventory.itemInMainHand } returns fixture.item(1)
            block.type = Material.STONE
            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.STONE
            breaks shouldBe 0
        }
    }

    "protection vetoes and post-event replacement or protection leave world and tool untouched" {
        scenario { fixture ->
            val block = fixture.world.getBlockAt(2, 65, 2).apply { type = Material.BARRIER }
            val player = fixture.player()
            var protected = true
            var breaks = 0
            var breakAction: (BlockBreakEvent) -> Unit = { it.isCancelled = true }
            fixture.paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { _, _, _, _ -> protected }, fixture.plugin, ServicePriority.Normal)
            fixture.breakListener { breaks++; breakAction(it) }

            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.BARRIER
            breaks shouldBe 0
            protected = false
            fixture.scheduler.tick(2)
            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.BARRIER
            breaks shouldBe 1

            fixture.scheduler.tick(2)
            breakAction = { it.block.type = Material.STONE }
            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.STONE
            fixture.scheduler.tick(2)
            block.type = Material.BARRIER
            breakAction = { protected = true }
            fixture.paper.callEvent(fixture.interact(player, block))
            block.type shouldBe Material.BARRIER
            player.inventory.itemInMainHand.amount shouldBe 1
            verify(exactly = 4) {
                player.sendActionBar(Component.text("Здесь нельзя ломать барьеры.", NamedTextColor.RED))
                player.playSound(any<Location>(), "minecraft:block.note_block.bass", 0.6f, 0.7f)
            }
        }
    }

    "multiple holders share the scan budget and only loaded in-radius barriers receive at most 64 markers" {
        scenario(maximumSteps = 32) { fixture ->
            val world = mockk<World>()
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { firstArg<Int>() == 0 && secondArg<Int>() == 0 }
            var reads = 0
            val barrier = mockk<Block> { every { type } returns Material.BARRIER }
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                (firstArg<Int>() shr 4) shouldBe 0
                (thirdArg<Int>() shr 4) shouldBe 0
                reads++
                barrier
            }
            every { world.getBlockAt(any<Location>()) } answers {
                val at = firstArg<Location>()
                world.getBlockAt(at.blockX, at.blockY, at.blockZ)
            }
            val origin = Location(world, 15.5, 64.5, 15.5)
            val particles = mutableListOf<Location>()
            val players = List(2) { fixture.player(world, origin) }
            players.forEach { player ->
                every { player.spawnParticle(Particle.DUST, any<Location>(), 6, 0.45, 0.45, 0.45, any<Particle.DustOptions>()) } answers {
                    val at = secondArg<Location>()
                    at.distanceSquared(origin).let { it <= 64.0 } shouldBe true
                    arg<Particle.DustOptions>(6).size shouldBe 0.8f
                    arg<Particle.DustOptions>(6).color shouldBe org.bukkit.Color.RED
                    particles += at
                }
            }
            // The two 17^3 scans span many refresh periods. A holder must not accumulate
            // overlapping scans, and one tick can never exceed the host's aggregate allowance.
            var ticks = 0
            while (particles.size < 128 && ticks++ < 400) {
                val before = reads
                fixture.scheduler.tick()
                (reads - before <= 32) shouldBe true
            }
            particles.size shouldBe 128
            particles.distinct().size shouldBe 64
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }

            players.forEach { every { it.inventory.itemInMainHand } returns fixture.item(null) }
            val before = particles.size
            fixture.scheduler.tick(25)
            particles.size shouldBe before
        }
    }

    "two-tick rate limit and lifecycle cleanup survive replacement without retaining listeners or scans" {
        scenario(maximumSteps = 2) { fixture ->
            val player = fixture.player()
            val first = fixture.world.getBlockAt(2, 65, 2).apply { type = Material.BARRIER }
            val second = fixture.world.getBlockAt(3, 65, 2).apply { type = Material.BARRIER }
            fixture.paper.callEvent(fixture.interact(player, first))
            fixture.paper.callEvent(fixture.interact(player, second))
            first.type shouldBe Material.AIR
            second.type shouldBe Material.BARRIER
            fixture.scheduler.tick(11)
            fixture.runtime.close()
            fixture.moduleTasks.trackedTaskCount() shouldBe 0
            fixture.paper.callEvent(fixture.interact(player, second))
            second.type shouldBe Material.BARRIER
            fixture.scheduler.tick(20)

            val replacementTasks = LifecycleTaskScope(fixture.scheduler)
            BarrierRemover(fixture.work, replacementTasks, fixture::marked).use { replacement ->
                replacement.start(fixture.plugin)
                fixture.paper.callEvent(fixture.interact(player, second))
                second.type shouldBe Material.AIR
            }
            replacementTasks.trackedTaskCount() shouldBe 0
            fixture.scheduler.tick(20)
            fixture.scheduler.pendingCount() shouldBe 0
            fixture.scheduler.timerCount() shouldBe 0
        }
    }
})

private fun scenario(maximumSteps: Int = 512, test: (BarrierFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> BarrierFixture(paper, maximumSteps).use(test) }
}

private class BarrierFixture(val paper: MockBukkitTestRuntime, maximumSteps: Int) : AutoCloseable {
    val plugin = paper.createSimplePlugin("BarrierRemoverTest")
    val world = paper.addSimpleWorld("barriers")
    val scheduler = TestTaskScheduler()
    private val queueTasks = LifecycleTaskScope(scheduler)
    val moduleTasks = LifecycleTaskScope(scheduler)
    val work = BudgetedTaskQueue(queueTasks, maximumSteps = maximumSteps, nanoTime = { 0L })
    private val players = mutableListOf<Player>()
    private val data = IdentityHashMap<ItemStack, ReadableItemNBT>()
    val runtime = BarrierRemover(work, moduleTasks, ::marked)

    init {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns players
        runtime.start(plugin)
    }

    // MockBukkit cannot run NBT-API's native ItemStack decoder. Keep that seam
    // explicit while exercising the same presence contract and registered handler.
    fun marked(item: ItemStack?): Boolean =
        item != null && !item.type.isAir && BarrierRemover.hasMarker(data.getValue(item))

    fun item(marker: Number?): ItemStack = ItemStack(Material.BLAZE_ROD).also { item ->
        val values = if (marker == null) emptyMap() else mapOf(BarrierRemover.MARKER to marker)
        data[item] = mockk { every { hasTag(any()) } answers { values.containsKey(firstArg<String>()) } }
    }

    fun player(world: World = this.world, origin: Location = Location(world, 2.5, 64.5, 2.5)): Player {
        val id = UUID.randomUUID()
        val tool = item(1.toByte())
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns id
        every { player.world } returns world
        every { player.location } answers { origin.clone() }
        every { player.isOnline } returns true
        every { player.inventory.itemInMainHand } returns tool
        players += player
        return player
    }

    fun interact(player: Player, block: Block, hand: EquipmentSlot = EquipmentSlot.HAND, action: Action = Action.RIGHT_CLICK_BLOCK) =
        PlayerInteractEvent(player, action, player.inventory.itemInMainHand, block, BlockFace.UP, hand)

    fun breakListener(action: (BlockBreakEvent) -> Unit) {
        paper.server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.HIGHEST)
            fun onBreak(event: BlockBreakEvent) = action(event)
        }, plugin)
    }

    override fun close() {
        runtime.close()
        work.close()
        queueTasks.close()
        unmockkStatic(Bukkit::class)
    }
}
