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
            f.barriers.update(f.player, graves.markers())
            f.packets.size shouldBe 16
            f.packets.all { it.material == Material.BARRIER } shouldBe true
            f.barriers.update(f.player, graves.markers())
            f.packets.size shouldBe 16
            f.scheduler.tick(20)
            f.packets.size shouldBe 32
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
            f.barriers.update(f.player, listOf(grave).markers())
            val first = f.click().apply { setUseInteractedBlock(Event.Result.DENY) }
            first.isCancelled shouldBe true
            first.useItemInHand() shouldBe Event.Result.DEFAULT
            f.paper.callEvent(first)
            f.paper.callEvent(f.click())
            f.opened shouldBe listOf(grave.id)
            first.isCancelled shouldBe true
            first.useItemInHand() shouldBe Event.Result.DENY
            f.scheduler.tick()
            f.packets.size shouldBe 4
            f.nanos += 250_000_000L
            f.paper.callEvent(f.click())
            f.opened shouldBe listOf(grave.id, grave.id)
        }
    }

    test("a one-block monument masks and opens only its lower cell under a solid ceiling") {
        withBarriers { f ->
            val grave = f.grave()
            f.materials[Triple(0, 65, 0)] = Material.STONE
            f.barriers.update(f.player, listOf(grave).markers(height = 1))
            f.packets.map { it.cell to it.material } shouldBe listOf(Triple(0, 64, 0) to Material.BARRIER)
            f.scheduler.tick(20)
            f.packets.size shouldBe 2
            f.packets.all { it.cell == Triple(0, 64, 0) && it.material == Material.BARRIER } shouldBe true

            val upper = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK,
                null, f.block(y = 65), BlockFace.NORTH, EquipmentSlot.HAND)
            f.paper.callEvent(upper)
            upper.isCancelled shouldBe false
            f.opened shouldBe emptyList()
            f.paper.callEvent(f.click())
            f.opened shouldBe listOf(grave.id)
            f.barriers.update(f.player, emptyList())
            f.packets.last().cell shouldBe Triple(0, 64, 0)
            f.packets.last().material shouldBe Material.AIR
            f.packets.none { it.cell == Triple(0, 65, 0) } shouldBe true
        }
    }

    test("shrinking a monument restores the upper cell and queued work keeps only the lower mask") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave).markers())
            f.paper.callEvent(f.click())
            val previous = f.packets.size
            f.materials[Triple(0, 65, 0)] = Material.STONE
            f.barriers.update(f.player, listOf(grave).markers(height = 1))
            f.packets.drop(previous).map { it.cell to it.material } shouldBe listOf(
                Triple(0, 65, 0) to Material.STONE,
                Triple(0, 64, 0) to Material.BARRIER,
            )
            val shrunk = f.packets.size
            f.scheduler.tick(20)
            f.packets.drop(shrunk).all { it.cell == Triple(0, 64, 0) && it.material == Material.BARRIER } shouldBe true
            val upper = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK,
                null, f.block(y = 65), BlockFace.NORTH, EquipmentSlot.HAND)
            f.paper.callEvent(upper)
            upper.isCancelled shouldBe false
            f.opened shouldBe listOf(grave.id)
        }
    }

    test("a two-block monument requires both cells and invalidates both when the upper cell becomes solid") {
        withBarriers { f ->
            val grave = f.grave()
            f.materials[Triple(0, 65, 0)] = Material.STONE
            f.barriers.update(f.player, listOf(grave).markers())
            f.packets shouldBe emptyList()
            f.materials[Triple(0, 65, 0)] = Material.AIR
            f.barriers.update(f.player, listOf(grave).markers())
            f.packets.size shouldBe 2
            f.materials[Triple(0, 65, 0)] = Material.STONE
            val lower = f.click()
            f.paper.callEvent(lower)
            lower.isCancelled shouldBe false
            f.opened shouldBe emptyList()
            f.packets.takeLast(2).map { it.cell to it.material } shouldBe listOf(
                Triple(0, 64, 0) to Material.AIR,
                Triple(0, 65, 0) to Material.STONE,
            )
        }
    }

    test("a one-block monument can occupy the highest world cell while a two-block monument cannot") {
        withBarriers { f ->
            val grave = f.grave(y = 319)
            f.position = Location(f.world, .5, 318.0, 2.5)
            f.barriers.update(f.player, listOf(grave).markers(height = 1))
            f.packets.map { it.cell to it.material } shouldBe listOf(Triple(0, 319, 0) to Material.BARRIER)
            f.barriers.update(f.player, listOf(grave).markers())
            f.packets.last().cell shouldBe Triple(0, 319, 0)
            f.packets.last().material shouldBe Material.AIR
            verify(exactly = 0) { f.world.getBlockAt(0, 320, 0) }
        }
    }

    test("the upper half of a two-block monument opens the same grave and is restored on removal") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave).markers())
            val upper = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK,
                null, f.block(y = 65), BlockFace.NORTH, EquipmentSlot.HAND)
            f.paper.callEvent(upper)
            upper.isCancelled shouldBe true
            f.opened shouldBe listOf(grave.id)
            f.materials[Triple(0, 65, 0)] = Material.STONE
            f.barriers.update(f.player, emptyList())
            f.packets.takeLast(2).map { it.material } shouldBe listOf(Material.AIR, Material.STONE)
        }
    }

    test("a grave frame follows the same owner, distance, readiness and stale-state checks") {
        withBarriers { f ->
            val own = f.grave()
            val foreign = f.grave(x = 1, owner = UUID.randomUUID())
            f.barriers.update(f.player, listOf(own, foreign).markers())
            f.barriers.interactFrame(f.player, UUID.randomUUID())
            f.barriers.interactFrame(f.player, foreign.id)
            f.messages.last() shouldBe "not-owner"
            f.isReady = false
            f.barriers.interactFrame(f.player, own.id)
            f.messages.last() shouldBe "interaction-unavailable"
            f.isReady = true
            f.position = Location(f.world, 12.0, 64.0, .5)
            f.barriers.interactFrame(f.player, own.id)
            f.opened shouldBe emptyList()
            f.position = Location(f.world, .5, 64.0, 2.5)
            f.barriers.interactFrame(f.player, own.id)
            f.barriers.interactFrame(f.player, own.id)
            f.opened shouldBe listOf(own.id)
            f.current.remove(own.id)
            f.barriers.interactFrame(f.player, own.id)
            f.packets.takeLast(2).map { it.material } shouldBe listOf(Material.AIR, Material.AIR)
            f.opened shouldBe listOf(own.id)
        }
    }

    test("foreign blocked offhand explicit denial and distant inputs cannot open a grave") {
        withBarriers { f ->
            val foreign = f.grave(owner = UUID.randomUUID())
            f.barriers.update(f.player, listOf(foreign).markers())
            f.paper.callEvent(f.click())
            f.messages shouldBe listOf("not-owner")
            f.opened shouldBe emptyList()

            val own = f.grave()
            f.barriers.update(f.player, listOf(own).markers())
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
            f.barriers.update(f.player, listOf(grave).markers())
            f.materials[Triple(0, 64, 0)] = Material.STONE
            val real = f.click()
            real.isCancelled shouldBe false
            f.paper.callEvent(real)
            real.isCancelled shouldBe false
            f.packets.takeLast(2).map { it.material } shouldBe listOf(Material.STONE, Material.AIR)
            f.opened shouldBe emptyList()
            val count = f.packets.size
            f.scheduler.tick(40)
            f.packets.size shouldBe count

            f.materials[Triple(0, 64, 0)] = Material.AIR
            f.barriers.update(f.player, listOf(grave).markers())
            f.current.clear()
            val stale = f.click()
            f.paper.callEvent(stale)
            stale.isCancelled shouldBe false
            f.packets.last().material shouldBe Material.AIR
        }
    }

    test("removal restores current data and fences the previously queued reassertion") {
        withBarriers { f ->
            f.barriers.update(f.player, listOf(f.grave()).markers())
            f.paper.callEvent(f.click())
            f.materials[Triple(0, 64, 0)] = Material.WATER
            f.barriers.update(f.player, emptyList())
            f.packets.takeLast(2).map { it.material } shouldBe listOf(Material.WATER, Material.AIR)
            val count = f.packets.size
            f.scheduler.tick(40)
            f.packets.size shouldBe count
        }
    }

    test("reconnect replaces the viewer lease and old quit or queued work cannot touch it") {
        withBarriers { f ->
            val grave = f.grave()
            val old = f.player
            f.barriers.update(old, listOf(grave).markers())
            f.paper.callEvent(f.click())
            val replacement = f.newPlayer()
            f.barriers.update(replacement, listOf(grave).markers())
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
            f.barriers.update(f.player, listOf(grave).markers())
            f.paper.callEvent(f.click())
            f.playerWorld = mockk<World> { every { uid } returns UUID.randomUUID() }
            f.barriers.world(PlayerChangedWorldEvent(f.player, f.world))
            val changed = f.packets.size
            f.scheduler.tick(20)
            f.packets.size shouldBe changed
            f.playerWorld = f.world

            f.barriers.update(f.player, listOf(grave).markers())
            f.barriers.death(mockk<PlayerDeathEvent> { every { entity } returns f.player })
            f.packets.last().material shouldBe Material.AIR
            f.barriers.update(f.player, listOf(grave).markers())
            f.barriers.unload(WorldUnloadEvent(f.world))
            f.packets.last().material shouldBe Material.AIR

            val expired = grave.copy(expiresAt = System.currentTimeMillis() - 1)
            f.current[grave.id] = expired
            val count = f.packets.size
            f.barriers.update(f.player, listOf(expired).markers())
            f.packets.size shouldBe count
            f.current[grave.id] = grave
            f.sent = false
            f.barriers.update(f.player, listOf(grave).markers())
            f.packets.size shouldBe count
        }
    }

    test("close cancels queued work and a replacement generation starts independently") {
        withBarriers { f ->
            val grave = f.grave()
            f.barriers.update(f.player, listOf(grave).markers())
            f.paper.callEvent(f.click())
            f.barriers.close()
            f.packets.last().material shouldBe Material.AIR
            f.scheduler.pendingCount() shouldBe 0
            f.scheduler.timerCount() shouldBe 0
            val count = f.packets.size
            f.barriers.update(f.player, listOf(grave).markers())
            f.scheduler.tick(40)
            f.packets.size shouldBe count
            f.createBarriers().use { next ->
                next.update(f.player, listOf(grave).markers())
                f.packets.last().material shouldBe Material.BARRIER
            }
        }
    }
})

private fun withBarriers(test: (BarrierFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    BarrierFixture().use(test)
}

private fun List<GraveView>.markers(height: Int = 2): List<GraveMarker> = map { GraveMarker(it, height) }

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
            val location = firstArg<Location>()
            packets += MaskPacket(player, Triple(location.blockX, location.blockY, location.blockZ), secondArg<BlockData>().material)
        }
    }

    fun grave(x: Int = 0, owner: UUID = this.owner, y: Int = 64) = GraveView(UUID.randomUUID(), owner, "survival", "world", x, y, 0,
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

private data class MaskPacket(val player: Player, val cell: Triple<Int, Int, Int>, val material: Material)
