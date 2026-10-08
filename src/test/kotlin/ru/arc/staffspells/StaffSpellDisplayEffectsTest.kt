package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class StaffSpellDisplayEffectsTest : FreeSpec({
    "all spell shapes stay finite and under the packet-part budget" {
        StaffSpell.entries.forEach { spell ->
            listOf(false, true).forEach { impact ->
                val parts = staffDisplayParts(spell, ageTicks = 160, durationTicks = 1,
                    length = 32.0, radius = 8.0, impact = impact)

                (parts.size <= StaffSpellDisplayEffects.MAX_PARTS) shouldBe true
                parts.isNotEmpty() shouldBe true
                parts.all { part ->
                    listOf(part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z,
                        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
                } shouldBe true
            }
        }
    }

    "nova leaves the caster eye outside every solid piece for its whole visible lifetime" {
        (0 until 30 step StaffSpellDisplayEffects.FRAME_TICKS).forEach { age ->
            staffDisplayParts(StaffSpell.NOVA, age, 30, 4.0, 8.0, true).forEach { part ->
                val eye = org.joml.Vector3f(0f, 1.62f, 0f).sub(part.center)
                org.joml.Quaternionf(part.rotation).invert().transform(eye)
                val outside = kotlin.math.abs(eye.x) > part.scale.x / 2 ||
                    kotlin.math.abs(eye.y) > part.scale.y / 2 || kotlin.math.abs(eye.z) > part.scale.z / 2
                outside shouldBe true
            }
        }
    }

    "transient spells grow in and dissolve before removal with stable part identities" {
        StaffSpell.entries.forEach { spell ->
            fun parts(age: Int) = staffDisplayParts(spell, age, 30, 8.0, 3.0, true)
            val first = parts(0)
            val body = parts(8)
            val last = parts(28)
            first.size shouldBe body.size
            last.size shouldBe body.size
            fun volume(parts: List<StaffDisplayPart>) = parts.sumOf {
                (it.scale.x * it.scale.y * it.scale.z).toDouble()
            }
            (volume(first) < volume(body) * 0.01) shouldBe true
            (volume(last) < volume(body) * 0.01) shouldBe true
        }
    }

    "frost advances through stable ground rows instead of exposing the whole fan" {
        val early = staffDisplayParts(StaffSpell.FROST, 6, 24, 7.0, 8.3, false)
        val late = staffDisplayParts(StaffSpell.FROST, 14, 24, 7.0, 8.3, false)
        early.size shouldBe late.size
        (early[0].scale.y > early[32].scale.y * 100) shouldBe true
        (late[32].scale.y > early[32].scale.y * 100) shouldBe true
        early.zip(late).forEach { (a, b) ->
            a.center.x shouldBe b.center.x
            a.center.z shouldBe b.center.z
        }
    }

    "comet flight is steady and its impact shards travel outward without pulsing" {
        val flight = staffDisplayParts(StaffSpell.EMBER, 6, 24, 1.0, 0.85, false)
        val later = staffDisplayParts(StaffSpell.EMBER, 16, 24, 1.0, 0.85, false)
        flight.zip(later).forEach { (a, b) ->
            a.center shouldBe b.center
            a.scale shouldBe b.scale
        }
        var previous = List(12) { 0f }
        (0 until 20 step StaffSpellDisplayEffects.FRAME_TICKS).forEach { age ->
            val shards = staffDisplayParts(StaffSpell.EMBER, age, 20, 0.0, 2.8, true).drop(2)
            val radial = shards.map { it.center.x * it.center.x + it.center.z * it.center.z }
            radial.zip(previous).all { (now, before) -> now >= before } shouldBe true
            previous = radial
        }
    }

    "display handles move in place, refresh materials, expire, cancel, and close" {
        withDisplayHarness { h ->
            val caster = h.player("display-owner")
            val otherCaster = h.player("display-other")
            val world = h.world
            val entitiesBefore = world.entities.size
            val start = Location(world, 0.5, 64.0, 0.5)
            val markId = h.effects.play(caster.uniqueId, StaffSpell.MARK, start,
                radius = 1.3, durationTicks = 8)!!
            val mark = h.recorder.records.toList()
            val markParts = staffDisplayParts(StaffSpell.MARK, 0, 8, 0.0, 1.3, false)
            mark.size shouldBe markParts.size
            mark.all { it.spawn.world == world && it.spawn.x.isFinite() && it.spawn.y.isFinite() && it.spawn.z.isFinite() } shouldBe true

            val moved = Location(world, 8.0, 66.0, 9.0)
            h.effects.move(markId, moved)
            h.scheduler.tick(4)

            h.recorder.records.size shouldBe mark.size
            val movedParts = staffDisplayParts(StaffSpell.MARK, 4, 8, 0.0, 1.3, false)
            mark.forEachIndexed { index, record ->
                val center = movedParts[index].center
                val expected = moved.clone().add(center.x.toDouble(), center.y.toDouble(), center.z.toDouble())
                val actual = record.teleports.last()
                actual.x shouldBe expected.x
                actual.y shouldBe expected.y
                actual.z shouldBe expected.z
                record.poses.all(DisplayPose::isFinite) shouldBe true
            }

            h.scheduler.tick(4)
            // A tracked charge survives its nominal timer until the gameplay owner releases it.
            mark.all { it.removals == 0 } shouldBe true
            val allocatedBeforeImpact = h.recorder.records.size
            h.effects.impact(markId, moved, radius = 3.5, durationTicks = 8)
            h.recorder.records.size shouldBe allocatedBeforeImpact
            mark.all { it.removals == 0 } shouldBe true
            h.scheduler.tick(8)
            mark.all { it.removals == 1 } shouldBe true

            // A scene whose wave material changes must update its stable packet handles.
            val novaStart = Location(world, 0.5, 64.0, 0.5)
            h.effects.play(caster.uniqueId, StaffSpell.NOVA, novaStart,
                radius = 6.0, durationTicks = 28, impact = true)!!
            val nova = h.recorder.records.drop(mark.size).toList()
            nova.size shouldBe staffDisplayParts(StaffSpell.NOVA, 0, 28, 4.0, 6.0, true).size
            (nova.size <= StaffSpellDisplayEffects.MAX_PARTS) shouldBe true
            h.scheduler.tick(4)
            nova.all { it.poses.size > 1 && it.poses.all(DisplayPose::isFinite) } shouldBe true

            h.effects.play(caster.uniqueId, StaffSpell.MARK, start, durationTicks = 80)
            val cancelled = h.recorder.records.drop(mark.size + nova.size).toList()
            h.effects.play(otherCaster.uniqueId, StaffSpell.MARK, start, durationTicks = 80)
            val retainedForOtherCaster = h.recorder.records.drop(mark.size + nova.size + cancelled.size).toList()

            h.effects.cancel(caster.uniqueId)
            (nova + cancelled).all { it.removals == 1 } shouldBe true
            retainedForOtherCaster.all { it.removals == 0 } shouldBe true
            world.entities.size shouldBe entitiesBefore

            val allocated = h.recorder.records.size
            h.effects.close()
            h.effects.close()
            retainedForOtherCaster.all { it.removals == 1 } shouldBe true
            h.scheduler.tick(20)
            h.recorder.records.size shouldBe allocated
            h.recorder.displaysCloseCount shouldBe 1
        }
    }

    "oldest-first global and per-caster caps bound scenes and each viewer to four" {
        withDisplayHarness { h ->
            val casters = (1..10).map { h.player("display-caster-$it") }
            val observer = h.player("display-observer")
            val origin = Location(h.world, 0.5, 64.0, 0.5)
            val partsPerMark = staffDisplayParts(StaffSpell.MARK, 0, 160, 0.0, 1.0, false).size

            fun cast(caster: Player): List<RecordedDisplay> {
                val before = h.recorder.records.size
                h.effects.play(caster.uniqueId, StaffSpell.MARK, origin, durationTicks = 160)!!
                return h.recorder.records.drop(before)
            }

            val firstPerCaster = cast(casters.first())
            val secondPerCaster = cast(casters.first())
            repeat(3) { cast(casters.first()) }
            firstPerCaster.size shouldBe partsPerMark
            firstPerCaster.all { it.removals == 1 } shouldBe true
            secondPerCaster.all { it.removals == 0 } shouldBe true

            (1..8).forEach { cast(casters[it]) }
            val firstGlobal = secondPerCaster
            val thirteenth = cast(casters[9])
            thirteenth.size shouldBe partsPerMark
            firstGlobal.all { it.removals == 1 } shouldBe true
            h.recorder.records.count { it.removals == 0 } shouldBe
                StaffSpellDisplayEffects.MAX_SCENES * partsPerMark

            h.recorder.shownTo.clear()
            h.recorder.hiddenFrom.clear()
            h.scheduler.tick(4)
            h.recorder.shownTo.count { it == observer.uniqueId } shouldBe 0
            h.recorder.records.count { it.removals == 0 && observer.uniqueId in it.visibleViewers } shouldBe
                StaffSpellDisplayEffects.MAX_PER_VIEWER * partsPerMark

            h.recorder.hiddenFrom.clear()
            observer.teleport(Location(h.world, 100.0, 64.0, 100.0))
            h.scheduler.tick(4)
            h.recorder.hiddenFrom.count { it == observer.uniqueId } shouldBe
                StaffSpellDisplayEffects.MAX_PER_VIEWER * partsPerMark
            h.recorder.records.count { it.removals == 0 && observer.uniqueId in it.visibleViewers } shouldBe 0

            h.recorder.shownTo.clear()
            observer.teleport(origin)
            h.scheduler.tick(4)
            h.recorder.shownTo.count { it == observer.uniqueId } shouldBe
                StaffSpellDisplayEffects.MAX_PER_VIEWER * partsPerMark
            h.recorder.records.count { it.removals == 0 && observer.uniqueId in it.visibleViewers } shouldBe
                StaffSpellDisplayEffects.MAX_PER_VIEWER * partsPerMark
        }
    }
})

private data class DisplayPose(
    val values: List<Float>,
) {
    fun isFinite() = values.all(Float::isFinite)
}

private data class RecordedDisplay(
    val handle: PacketBlockDisplay,
    val spawn: Location,
    val teleports: MutableList<Location> = mutableListOf(),
    val poses: MutableList<DisplayPose> = mutableListOf(),
    val visibleViewers: MutableSet<UUID> = mutableSetOf(),
    var block: BlockData,
    var blockUpdates: Int = 0,
    var removals: Int = 0,
)

private class PacketDisplayRecorder {
    val records = mutableListOf<RecordedDisplay>()
    val shownTo = mutableListOf<UUID>()
    val hiddenFrom = mutableListOf<UUID>()
    var displaysCloseCount = 0
        private set

    val displays = mockk<PaperPacketDisplays>(relaxed = true).also { owner ->
        every { owner.spawnBlock(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            val handle = mockk<PacketBlockDisplay>(relaxed = true)
            val record = RecordedDisplay(handle, at, block = secondArg<BlockData>())
            every { handle.blockData } answers { record.block }
            every { handle.blockData = any() } answers {
                record.block = firstArg<BlockData>()
                record.blockUpdates++
                Unit
            }
            every { handle.teleport(any()) } answers { record.teleports += firstArg<Location>().clone() }
            every { handle.transformation = any() } answers {
                val pose = firstArg<Transformation>()
                record.poses += DisplayPose(listOf(
                    pose.translation.x, pose.translation.y, pose.translation.z,
                    pose.scale.x, pose.scale.y, pose.scale.z,
                    pose.leftRotation.x, pose.leftRotation.y, pose.leftRotation.z, pose.leftRotation.w,
                    pose.rightRotation.x, pose.rightRotation.y, pose.rightRotation.z, pose.rightRotation.w,
                ))
            }
            every { handle.showTo(any()) } answers {
                val viewer = firstArg<Player>().uniqueId
                shownTo += viewer
                record.visibleViewers += viewer
                Unit
            }
            every { handle.hideFrom(any()) } answers {
                val viewer = firstArg<Player>().uniqueId
                hiddenFrom += viewer
                record.visibleViewers -= viewer
                Unit
            }
            every { handle.remove() } answers { record.removals++; Unit }
            records += record
            handle
        }
        every { owner.close() } answers { displaysCloseCount++; Unit }
    }
}

private data class DisplayHarness(
    val paper: MockBukkitTestRuntime,
    val scheduler: TestTaskScheduler,
    val world: org.bukkit.World,
    val recorder: PacketDisplayRecorder,
    val effects: StaffSpellDisplayEffects,
) {
    fun player(name: String): Player = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5))
    }
}

private fun withDisplayHarness(block: (DisplayHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
    val scheduler = TestTaskScheduler()
    MockBukkitTestRuntime.open().use { paper ->
        Tasks.withScheduler(scheduler) {
            val world = paper.addSimpleWorld("staff-display-effects")
            val recorder = PacketDisplayRecorder()
            val effects = StaffSpellDisplayEffects(recorder.displays)
            try {
                block(DisplayHarness(paper, scheduler, world, recorder, effects))
            } finally {
                effects.close()
            }
        }
    }
}
