package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class GraveBarriersTest : FunSpec({
    test("only bounded rendered passable cells are masked and stable refreshes reuse them") {
        withBarriers { f ->
            val graves = (0..8).map { f.grave(it) }
            f.barriers.update(f.player, graves)
            f.packets.size shouldBe 8
            f.packets.all { it.material == Material.BARRIER } shouldBe true
            f.barriers.update(f.player, graves)
            f.packets.size shouldBe 8
            f.scheduler.tick(20)
            f.packets.size shouldBe 16
            f.blocks.values.forEach { block ->
                verify(exactly = 0) { block.setType(any<Material>()) }
                verify(exactly = 0) { block.setType(any<Material>(), any<Boolean>()) }
                verify(exactly = 0) { block.setBlockData(any<BlockData>(), any<Boolean>()) }
            }
            verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
        }
    }

    test("block-only denied AIR input opens once without uncancel and coalesces next-tick reassertion") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave))
            val first = f.click().apply { setUseInteractedBlock(Event.Result.DENY) }
            first.isCancelled shouldBe true
            first.useItemInHand() shouldBe Event.Result.DEFAULT
            f.paper.callEvent(first)
            f.paper.callEvent(f.click())
            f.opened shouldBe listOf(grave.id)
            first.isCancelled shouldBe true
            first.useItemInHand() shouldBe Event.Result.DENY
            f.scheduler.tick()
            f.packets.size shouldBe 2
            f.nanos += 250_000_000L
            f.paper.callEvent(f.click())
            f.opened shouldBe listOf(grave.id, grave.id)
        }
    }

    test("foreign blocked offhand explicit denial and distant inputs cannot open a grave") {
        withBarriers { f ->
            val foreign = f.grave(owner = UUID.randomUUID())
            f.barriers.update(f.player, listOf(foreign))
            f.paper.callEvent(f.click())
            f.messages shouldBe listOf("not-owner")
            f.opened shouldBe emptyList()

            val own = f.grave()
            f.barriers.update(f.player, listOf(own))
            f.isReady = false
            f.paper.callEvent(f.click())
            f.messages.last() shouldBe "interaction-unavailable"
            f.isReady = true
            f.paper.callEvent(f.click(hand = EquipmentSlot.OFF_HAND))
            f.paper.callEvent(f.click().apply { setUseItemInHand(Event.Result.DENY) })
            f.position = Location(f.world, 12.0, 64.0, .5)
            f.paper.callEvent(f.click())
            f.opened shouldBe emptyList()
            verify(exactly = 0) { f.player.inventory }
        }
    }

    test("a newly solid cell or stale grave is restored without consuming its real block interaction") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave))
            f.materials[Triple(0, 64, 0)] = Material.STONE
            val real = f.click()
            real.isCancelled shouldBe false
            f.paper.callEvent(real)
            real.isCancelled shouldBe false
            f.packets.last().material shouldBe Material.STONE
            f.opened shouldBe emptyList()
            val count = f.packets.size
            f.scheduler.tick(40)
            f.packets.size shouldBe count

            f.materials[Triple(0, 64, 0)] = Material.AIR
            f.barriers.update(f.player, listOf(grave))
            f.current.clear()
            val stale = f.click()
            f.paper.callEvent(stale)
            stale.isCancelled shouldBe false
            f.packets.last().material shouldBe Material.AIR
        }
    }

    test("removal restores current data and fences the previously queued reassertion") {
        withBarriers { f ->
            f.barriers.update(f.player, listOf(f.grave()))
            f.paper.callEvent(f.click())
            f.materials[Triple(0, 64, 0)] = Material.WATER
            f.barriers.update(f.player, emptyList())
            f.packets.last().material shouldBe Material.WATER
            val count = f.packets.size
            f.scheduler.tick(40)
            f.packets.size shouldBe count
        }
    }

    test("reconnect replaces the viewer lease and old quit or queued work cannot touch it") {
        withBarriers { f ->
            val grave = f.grave()
            val old = f.player
            f.barriers.update(old, listOf(grave))
            f.paper.callEvent(f.click())
            val replacement = f.newPlayer()
            f.barriers.update(replacement, listOf(grave))
            f.barriers.quit(mockk<PlayerQuitEvent> { every { player } returns old })
            val afterReplace = f.packets.size
            f.scheduler.tick()
            f.packets.size shouldBe afterReplace
            f.scheduler.tick(19)
            f.packets.last().player shouldBe replacement
            f.packets.last().material shouldBe Material.BARRIER
        }
    }

    test("world change death unload expiry and unsent chunks revoke masks without loading chunks") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave))
            f.paper.callEvent(f.click())
            f.playerWorld = mockk<World> { every { uid } returns UUID.randomUUID() }
            f.barriers.world(PlayerChangedWorldEvent(f.player, f.world))
            val changed = f.packets.size
            f.scheduler.tick(20)
            f.packets.size shouldBe changed
            f.playerWorld = f.world

            f.barriers.update(f.player, listOf(grave))
            f.barriers.death(mockk<PlayerDeathEvent> { every { entity } returns f.player })
            f.packets.last().material shouldBe Material.AIR
            f.barriers.update(f.player, listOf(grave))
            f.barriers.unload(WorldUnloadEvent(f.world))
            f.packets.last().material shouldBe Material.AIR

            val expired = grave.copy(expiresAt = System.currentTimeMillis() - 1)
            f.current[grave.id] = expired
            val count = f.packets.size
            f.barriers.update(f.player, listOf(expired))
            f.packets.size shouldBe count
            f.current[grave.id] = grave
            f.sent = false
            f.barriers.update(f.player, listOf(grave))
            f.packets.size shouldBe count
        }
    }

    test("close cancels queued work and a replacement generation starts independently") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave))
            f.paper.callEvent(f.click())
            f.barriers.close()
            f.packets.last().material shouldBe Material.AIR
            f.scheduler.pendingCount() shouldBe 0
            f.scheduler.timerCount() shouldBe 0
            val count = f.packets.size
            f.barriers.update(f.player, listOf(grave))
            f.scheduler.tick(40)
            f.packets.size shouldBe count
            f.createBarriers().use { next ->
                next.update(f.player, listOf(grave))
                f.packets.last().material shouldBe Material.BARRIER
            }
        }
    }
})

private fun withBarriers(test: (BarrierFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    BarrierFixture().use(test)
}

private class BarrierFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("GraveBarriers")
    val scheduler = TestTaskScheduler()
    private val owner = UUID.randomUUID()
    val world = mockk<World>(relaxed = true)
    var playerWorld: World = world
    var position = Location(world, .5, 64.0, 2.5)
    var sent = true
    var isReady = true
    var nanos = 0L
    val packets = mutableListOf<MaskPacket>()
    val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
    val materials = mutableMapOf<Triple<Int, Int, Int>, Material>()
    val current = mutableMapOf<UUID, GraveView>()
    val opened = mutableListOf<UUID>()
    val messages = mutableListOf<String>()
    val player: Player
    val barriers: GraveBarriers

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns "world"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers { block(firstArg(), secondArg(), thirdArg()) }
        player = newPlayer()
        barriers = createBarriers()
    }

    fun createBarriers() = GraveBarriers(plugin, "survival", 32, { current[it.id] == it }, { isReady },
        { _, id -> opened += id }, { _, key -> messages += key }, scheduler, { nanos })

    fun newPlayer(): Player = mockk<Player>(relaxed = true).also { player ->
        every { player.uniqueId } returns owner
        every { player.world } answers { playerWorld }
        every { player.location } answers { position.clone() }
        every { player.eyeLocation } answers { position.clone().add(0.0, 1.62, 0.0) }
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isChunkSent(any<Long>()) } answers { sent }
        every { player.getAttribute(any()) } returns null
        every { player.sendBlockChange(any<Location>(), any<BlockData>()) } answers {
            packets += MaskPacket(player, secondArg<BlockData>().material)
        }
    }

    fun grave(x: Int = 0, owner: UUID = this.owner) = GraveView(UUID.randomUUID(), owner, "survival", "world", x, 64, 0,
        1, -1, 0, emptyList()).also { current[it.id] = it }

    fun block(x: Int = 0, y: Int = 64, z: Int = 0): Block = blocks.getOrPut(Triple(x, y, z)) {
        val cell = Triple(x, y, z)
        mockk<Block>(relaxed = true).also { block ->
            every { block.world } returns world
            every { block.x } returns x
            every { block.y } returns y
            every { block.z } returns z
            every { block.location } returns Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            every { block.type } answers { materials[cell] ?: Material.AIR }
            every { block.isPassable } answers { (materials[cell] ?: Material.AIR) in setOf(Material.AIR, Material.WATER) }
            every { block.blockData } answers { (materials[cell] ?: Material.AIR).createBlockData() }
        }
    }

    fun click(hand: EquipmentSlot = EquipmentSlot.HAND) = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
        null, block(), BlockFace.NORTH, hand)

    override fun close() {
        barriers.close()
        scheduler.close()
        paper.close()
    }
}

private data class MaskPacket(val player: Player, val material: Material)
