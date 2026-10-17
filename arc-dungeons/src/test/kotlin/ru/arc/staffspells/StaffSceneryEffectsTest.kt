package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.abs

class StaffSceneryEffectsTest : FreeSpec({
    "impact caps blocks and viewers, masks only received chunks, and restores current data after 40 ticks" {
        MockBukkitTestRuntime.open().use {
            val center = Location(mockk(relaxed = true), 15.5, 64.5, 8.5)
            val viewers = (1L..20L).map { index ->
                sceneryViewer(center.world, UUID(0, index), Location(center.world, 23.5 + index, 64.5, 8.5))
            }
            val visibleTo = viewers
            val solids = (12..18).flatMap { x -> (5..11).map { z -> GridPos(x, 64, z) } }.toSet()
            val fixture = sceneryWorld("arena", solids, viewers, visibleTo, loadedChunk = { x, _ -> x == 0 }) { _, read ->
                Bukkit.createBlockData(if (read == 0) Material.STONE else Material.GOLD_BLOCK)
            }
            val recorder = SceneryDisplayRecorder()
            val scheduler = TestTaskScheduler()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(scheduler))

            effects.impact(UUID.randomUUID(), center, 3.0)

            recorder.records.size shouldBe 12
            recorder.records.forEach { record ->
                viewers.take(16).forEach { viewer -> record.shownTo.contains(viewer.uniqueId) shouldBe true }
                viewers.drop(16).forEach { viewer -> record.shownTo.contains(viewer.uniqueId) shouldBe false }
                record.teleportDuration shouldBe 4
            }
            visibleTo.take(16).forEach { viewer ->
                verify(exactly = 1) {
                    viewer.sendMultiBlockChange(match { changes ->
                        changes.size == 12 && changes.values.all { it.material == Material.AIR }
                    })
                }
            }
            viewers.drop(16).forEach { verify(exactly = 0) { it.sendMultiBlockChange(any()) } }
            fixture.solidBlocks.values.forEach { verify(exactly = 0) { it.setType(any<Material>()) } }
            fixture.solidBlocks.keys.filter { it.x >= 16 }.size shouldBe 0
            fixture.solidPositions.size shouldBe 49

            scheduler.tick(39)
            visibleTo.take(16).forEach { verify(exactly = 0) {
                it.sendMultiBlockChange(match { changes -> changes.values.any { data -> data.material == Material.GOLD_BLOCK } })
            } }
            scheduler.tick(1)

            visibleTo.take(16).forEach { viewer ->
                verify(exactly = 1) {
                    viewer.sendMultiBlockChange(match { changes ->
                        changes.size == 12 && changes.values.all { it.material == Material.GOLD_BLOCK }
                    })
                }
            }
            recorder.records.forEach { it.removals shouldBe 1 }
            effects.close()
            recorder.closeCount shouldBe 1
        }
    }

    "fake blocks and debris only reach viewers whose source chunks are already received" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 64.5, .5)
            val received = sceneryViewer(world, UUID.randomUUID(), center.clone().add(0.0, 0.0, 5.0))
            val notReceived = sceneryViewer(world, UUID.randomUUID(), center.clone().add(0.0, 0.0, 5.0))
            val fixture = sceneryWorld("arena", setOf(GridPos(0, 64, 0)), listOf(received, notReceived), listOf(received)) { _, _ ->
                Bukkit.createBlockData(Material.STONE)
            }
            val recorder = SceneryDisplayRecorder()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(TestTaskScheduler()))

            effects.impact(UUID.randomUUID(), center, .1)

            recorder.records.size shouldBe 1
            recorder.records.single().shownTo shouldBe setOf(received.uniqueId)
            verify(exactly = 1) {
                received.sendMultiBlockChange(match { changes -> changes.values.single().material == Material.AIR })
            }
            verify(exactly = 0) { notReceived.sendMultiBlockChange(any()) }
            effects.close()
            verify(exactly = 1) {
                received.sendMultiBlockChange(match { changes -> changes.values.single().material == Material.STONE })
            }
            recorder.records.single().removals shouldBe 1
        }
    }

    "debris audience is removed when its viewer leaves the world" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 64.5, .5)
            val viewer = sceneryViewer(world, UUID.randomUUID(), center.clone().add(0.0, 0.0, 5.0))
            sceneryWorld("arena", setOf(GridPos(0, 64, 0)), listOf(viewer), listOf(viewer)) { _, _ ->
                Bukkit.createBlockData(Material.STONE)
            }
            val recorder = SceneryDisplayRecorder()
            val scheduler = TestTaskScheduler()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(scheduler))

            effects.impact(UUID.randomUUID(), center, .1)
            val otherWorld = mockk<World>(relaxed = true)
            every { otherWorld.uid } returns UUID.randomUUID()
            every { viewer.world } returns otherWorld
            scheduler.tick(4)

            recorder.records.single().hiddenFrom shouldBe setOf(viewer.uniqueId)
            effects.close()
            recorder.records.single().removals shouldBe 1
            verify(exactly = 1) { viewer.sendMultiBlockChange(any()) }
        }
    }

    "overlapping impacts do not refresh masked blocks, while cancel restores their live state" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 64.5, .5)
            val viewer = sceneryViewer(world, UUID.randomUUID(), center.clone().add(0.0, 0.0, 5.0))
            val fixture = sceneryWorld("arena", setOf(GridPos(0, 64, 0)), listOf(viewer), listOf(viewer)) { _, read ->
                Bukkit.createBlockData(when (read) {
                    0 -> Material.STONE
                    1 -> Material.DIRT
                    else -> Material.GOLD_BLOCK
                })
            }
            val recorder = SceneryDisplayRecorder()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(TestTaskScheduler()))
            val caster = UUID.randomUUID()

            effects.impact(caster, center, .1)
            effects.impact(UUID.randomUUID(), center, .1)
            recorder.records.size shouldBe 1
            effects.cancel(caster)
            verify(exactly = 1) {
                viewer.sendMultiBlockChange(match { changes -> changes.values.single().material == Material.DIRT })
            }
            recorder.records.single().removals shouldBe 1

            effects.impact(caster, center, .1)
            recorder.records.size shouldBe 2
            effects.close()
            verify(exactly = 1) {
                viewer.sendMultiBlockChange(match { changes -> changes.values.single().material == Material.GOLD_BLOCK })
            }
            recorder.records.forEach { it.removals shouldBe 1 }
            recorder.closeCount shouldBe 1
            fixture.solidPositions.size shouldBe 1
        }
    }

    "transient restore packet failures keep ownership until current data is restored" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 64.5, .5)
            val viewer = sceneryViewer(world, UUID.randomUUID(), center.clone().add(0.0, 0.0, 5.0))
            sceneryWorld("arena", setOf(GridPos(0, 64, 0)), listOf(viewer), listOf(viewer)) { _, read ->
                Bukkit.createBlockData(if (read == 0) Material.STONE else Material.GOLD_BLOCK)
            }
            val recorder = SceneryDisplayRecorder()
            val scheduler = TestTaskScheduler()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(scheduler))
            var failRestore = true
            every { viewer.sendMultiBlockChange(any()) } answers {
                val changes = firstArg<Map<Location, BlockData>>()
                if (changes.values.any { it.material == Material.GOLD_BLOCK } && failRestore) {
                    throw IllegalStateException("transient multi-block failure")
                }
            }
            every { viewer.sendBlockChange(any(), any()) } answers {
                if (secondArg<BlockData>().material == Material.GOLD_BLOCK && failRestore) {
                    failRestore = false
                    throw IllegalStateException("transient block failure")
                }
            }
            val caster = UUID.randomUUID()

            effects.impact(caster, center, .1)
            effects.cancel(caster)
            recorder.records.single().removals shouldBe 1

            effects.impact(UUID.randomUUID(), center, .1)
            recorder.records.size shouldBe 1
            scheduler.tick(1)

            verify(exactly = 2) {
                viewer.sendMultiBlockChange(match { changes -> changes.values.all { it.material == Material.GOLD_BLOCK } })
            }
            verify(exactly = 1) {
                viewer.sendBlockChange(any(), match { it.material == Material.GOLD_BLOCK })
            }
            effects.impact(UUID.randomUUID(), center, .1)
            recorder.records.size shouldBe 2
            effects.close()
            recorder.records.forEach { it.removals shouldBe 1 }
            recorder.closeCount shouldBe 1
        }
    }

    "empty worlds disable effects and wildcard allows bounded per-caster and global scenes" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val viewer = sceneryViewer(world, UUID.randomUUID(), Location(world, 18.5, 64.5, .5))
            val positions = listOf(0, 4, 8, 12, 16, 20, 24, 28, 32, 36).map { GridPos(it, 64, 0) }.toSet()
            val fixture = sceneryWorld("arena", positions, listOf(viewer), listOf(viewer)) { _, _ ->
                Bukkit.createBlockData(Material.STONE)
            }
            val disabledRecorder = SceneryDisplayRecorder()
            val disabled = StaffSceneryEffects(disabledRecorder.displays, emptySet(), LifecycleTaskScope(TestTaskScheduler()))
            disabled.impact(UUID.randomUUID(), Location(world, .5, 64.5, .5), .1)
            disabledRecorder.records.size shouldBe 0
            disabled.close()
            disabledRecorder.closeCount shouldBe 1

            val recorder = SceneryDisplayRecorder()
            val effects = StaffSceneryEffects(recorder.displays, setOf("*"), LifecycleTaskScope(TestTaskScheduler()))
            val caster = UUID.randomUUID()
            effects.impact(caster, Location(world, .5, 64.5, .5), .1)
            effects.impact(caster, Location(world, 4.5, 64.5, .5), .1)
            effects.impact(caster, Location(world, 8.5, 64.5, .5), .1)
            listOf(12, 16, 20, 24, 28, 32).forEachIndexed { index, x ->
                effects.impact(UUID(1, index.toLong() + 1), Location(world, x + .5, 64.5, .5), .1)
            }
            effects.impact(UUID(2, 1), Location(world, 36.5, 64.5, .5), .1)

            recorder.records.size shouldBe 8
            effects.close()
            recorder.records.forEach { it.removals shouldBe 1 }
            recorder.closeCount shouldBe 1
            fixture.solidPositions.size shouldBe 10
        }
    }

    "scenery never hides collision blocks already near a viewer" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 64.5, .5)
            val close = sceneryViewer(world, UUID.randomUUID(), Location(world, .5, 65.0, .5))
            val distant = sceneryViewer(world, UUID.randomUUID(), Location(world, .5, 65.0, 8.5))
            val viewers = listOf(close, distant)
            val fixture = sceneryWorld("arena", setOf(GridPos(0, 64, 0)), viewers, viewers) { _, _ ->
                Bukkit.createBlockData(Material.STONE)
            }
            val recorder = SceneryDisplayRecorder()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(TestTaskScheduler()))

            effects.impact(UUID.randomUUID(), center, .1)

            recorder.records.single().shownTo shouldBe setOf(distant.uniqueId)
            verify(exactly = 0) { close.sendMultiBlockChange(any()) }
            verify(exactly = 1) { distant.sendMultiBlockChange(match { it.values.single().material == Material.AIR }) }
            fixture.solidBlocks.values.forEach { verify(exactly = 0) { it.setType(any<Material>()) } }
            effects.close()
            verify(exactly = 0) { close.sendMultiBlockChange(any()) }
        }
    }

    "approach restores live blocks for one viewer across owners before contact without remasking them" {
        for (moveEvent in listOf(true, false)) MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val from = Location(world, 2.5, 65.0, 6.5)
            val approaching = sceneryViewer(world, UUID.randomUUID(), from)
            val distant = sceneryViewer(world, UUID.randomUUID(), Location(world, 2.5, 65.0, 10.5))
            val viewers = listOf(approaching, distant)
            var material = Material.STONE
            sceneryWorld("arena", setOf(GridPos(0, 64, 0), GridPos(4, 64, 0)), viewers, viewers) { _, _ ->
                Bukkit.createBlockData(material)
            }
            val recorder = SceneryDisplayRecorder()
            val scheduler = TestTaskScheduler()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(scheduler))
            val firstOwner = UUID.randomUUID()
            val secondOwner = UUID.randomUUID()
            val first = Location(world, .5, 64.5, .5)
            effects.impact(firstOwner, first, .1)
            effects.impact(secondOwner, Location(world, 4.5, 64.5, .5), .1)
            material = Material.GOLD_BLOCK

            // The body is still .7 blocks from the nearest obstacle when restoration is sent.
            val to = Location(world, 2.5, 65.0, 2.0)
            if (moveEvent) effects.onMove(PlayerMoveEvent(approaching, from, to))
            else {
                every { approaching.location } returns to
                scheduler.tick(1)
            }

            verify(exactly = 2) {
                approaching.sendMultiBlockChange(match { it.values.single().material == Material.GOLD_BLOCK })
            }
            verify(exactly = 0) {
                distant.sendMultiBlockChange(match { it.values.any { data -> data.material != Material.AIR } })
            }
            recorder.records.forEach { it.hiddenFrom shouldBe setOf(approaching.uniqueId) }
            effects.impact(UUID.randomUUID(), first, .1)
            recorder.records.size shouldBe 2 // The distant viewer's original owner still holds this block.
            effects.cancel(firstOwner)
            verify(exactly = 1) {
                distant.sendMultiBlockChange(match { it.values.single().material == Material.GOLD_BLOCK })
            }
            effects.close()
            verify(exactly = 2) {
                approaching.sendMultiBlockChange(match { it.values.single().material == Material.GOLD_BLOCK })
            }
            verify(exactly = 2) {
                distant.sendMultiBlockChange(match { it.values.single().material == Material.GOLD_BLOCK })
            }
        }
    }

    "gravity depression sinks an annulus and restores live terrain on cancel without block mutation" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 65.0, .5)
            val viewer = sceneryViewer(world, UUID.randomUUID(), center)
            val solids = (-5..5).flatMap { x -> (-5..5).map { z -> GridPos(x, 64, z) } }.toSet()
            val fixture = sceneryWorld("arena", solids, listOf(viewer), listOf(viewer)) { _, read ->
                Bukkit.createBlockData(if (read == 0) Material.GRASS_BLOCK else Material.GOLD_BLOCK)
            }
            val recorder = SceneryDisplayRecorder()
            val scheduler = TestTaskScheduler()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(scheduler))
            val owner = UUID.randomUUID()
            effects.depress(owner, center, 6.5)
            (recorder.records.size in 24..32) shouldBe true
            recorder.records.all { it.teleportDuration == 2 } shouldBe true
            recorder.records.none { it.origin.blockX == 0 && it.origin.blockZ == 0 } shouldBe true
            scheduler.tick(4)
            recorder.records.all { abs(it.teleports.last().y - (it.origin.y - .8)) < 1e-6 } shouldBe true
            recorder.records.all { it.teleports.last().x == it.origin.x && it.teleports.last().z == it.origin.z } shouldBe true
            effects.cancel(owner)
            verify { viewer.sendMultiBlockChange(match { it.values.all { data -> data.material == Material.GOLD_BLOCK } }) }
            recorder.records.all { it.removals == 1 } shouldBe true
            fixture.solidBlocks.values.forEach { verify(exactly = 0) { it.setType(any<Material>()) } }
            effects.close()
        }
        (abs(staffSceneryDepressionY(0)) < 1e-9) shouldBe true
        staffSceneryDepressionY(16) shouldBe -.8
        (abs(staffSceneryDepressionY(26)) < 1e-9) shouldBe true
        staffSceneryDepressionFootprint(6.5).size shouldBe 32
    }

    "complete depression rings share the original ninety-six scenery-block ceiling" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val center = Location(world, .5, 65.0, .5)
            val viewer = sceneryViewer(world, UUID.randomUUID(), center)
            val solids = (-5..29).flatMap { x -> (-5..5).map { z -> GridPos(x, 64, z) } }.toSet()
            sceneryWorld("arena", solids, listOf(viewer), listOf(viewer)) { _, _ -> Bukkit.createBlockData(Material.STONE) }
            val recorder = SceneryDisplayRecorder()
            val effects = StaffSceneryEffects(recorder.displays, setOf("arena"), LifecycleTaskScope(TestTaskScheduler()))
            val owners = List(4) { UUID.randomUUID() }
            owners.forEachIndexed { index, owner -> effects.depress(owner, center.clone().add(index * 8.0, 0.0, 0.0), 6.5) }
            recorder.records.size shouldBe 96
            effects.cancel(owners.first())
            effects.depress(owners.last(), center.clone().add(24.0, 0.0, 0.0), 6.5)
            recorder.records.size shouldBe 128
            effects.close()
        }
    }

    "ballistic debris uses a deterministic four-tick trajectory" {
        val start = staffSceneryBallisticOffset(0, 0.0, .1, .16)
        start.x shouldBe 0.0
        start.y shouldBe 0.0
        start.z shouldBe 0.0

        val frame = staffSceneryBallisticOffset(4, 0.0, .1, .16)
        (abs(frame.x - .4) < 1.0e-9) shouldBe true
        (abs(frame.y - .496) < 1.0e-9) shouldBe true
        frame.z shouldBe 0.0
    }
})

private data class GridPos(val x: Int, val y: Int, val z: Int)

private data class SceneryFixture(val blocks: Map<GridPos, Block>, val solidPositions: Set<GridPos>) {
    val solidBlocks: Map<GridPos, Block> get() = blocks.filterKeys { it in solidPositions }
}

private fun sceneryViewer(world: World, id: UUID, at: Location): Player = mockk<Player>(relaxed = true).also { player ->
    every { player.uniqueId } returns id
    every { player.world } returns world
    every { player.location } returns at.clone()
    every { player.boundingBox } answers {
        val location = player.location
        BoundingBox(location.x - .3, location.y, location.z - .3, location.x + .3, location.y + 1.8, location.z + .3)
    }
    every { player.isOnline } returns true
}

private fun sceneryWorld(
    name: String,
    solids: Set<GridPos>,
    nearby: List<Player>,
    sentChunkViewers: List<Player>,
    loadedChunk: (Int, Int) -> Boolean = { _, _ -> true },
    dataAt: (GridPos, Int) -> BlockData,
): SceneryFixture {
    val world = nearby.first().world
    val worldId = UUID.randomUUID()
    every { world.name } returns name
    every { world.uid } returns worldId
    every { world.minHeight } returns 0
    every { world.maxHeight } returns 256
    every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers {
        loadedChunk(firstArg(), secondArg())
    }
    every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns nearby
    every { world.getPlayersSeeingChunk(any<Int>(), any<Int>()) } returns sentChunkViewers
    val allBlocks = mutableMapOf<GridPos, Block>()
    val dataReads = mutableMapOf<GridPos, Int>()
    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
        val pos = GridPos(firstArg(), secondArg(), thirdArg())
        allBlocks.getOrPut(pos) {
            val material = if (pos in solids) Material.STONE else Material.AIR
            mockk<Block>(relaxed = true).also { block ->
                every { block.type } returns material
                every { block.isPassable } returns (material == Material.AIR)
                every { block.isLiquid } returns false
                if (material.isSolid) {
                    every { block.state } returns mockk<BlockState>(relaxed = true)
                    every { block.blockData } answers {
                        val read = dataReads.getOrDefault(pos, 0)
                        dataReads[pos] = read + 1
                        dataAt(pos, read)
                    }
                }
            }
        }
    }
    return SceneryFixture(allBlocks, solids)
}

private data class SceneryDisplayRecord(
    val handle: PacketBlockDisplay,
    val origin: Location,
    val shownTo: MutableSet<UUID> = mutableSetOf(),
    val hiddenFrom: MutableSet<UUID> = mutableSetOf(),
    val teleports: MutableList<Location> = mutableListOf(),
    var teleportDuration: Int = -1,
    var removals: Int = 0,
)

private class SceneryDisplayRecorder {
    val records = mutableListOf<SceneryDisplayRecord>()
    var closeCount = 0
        private set

    val displays = mockk<PaperPacketDisplays>(relaxed = true).also { owner ->
        every { owner.spawnBlock(any(), any()) } answers {
            val record = SceneryDisplayRecord(mockk(relaxed = true), firstArg<Location>().clone())
            every { record.handle.teleportDuration = any() } answers {
                record.teleportDuration = firstArg()
            }
            every { record.handle.showTo(any<Player>()) } answers {
                record.shownTo += firstArg<Player>().uniqueId
            }
            every { record.handle.hideFrom(any<Player>()) } answers {
                record.hiddenFrom += firstArg<Player>().uniqueId
            }
            every { record.handle.teleport(any()) } answers {
                record.teleports += firstArg<Location>().clone()
            }
            every { record.handle.remove() } answers { record.removals++; Unit }
            records += record
            record.handle
        }
        every { owner.close() } answers { closeCount++; Unit }
    }
}
