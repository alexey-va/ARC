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
                listOf(false, true).forEach { secondary ->
                    val parts = staffDisplayParts(spell, ageTicks = 160, durationTicks = 1,
                        length = 32.0, radius = 8.0, impact = impact, secondary = secondary)

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

    "chain trails start at their clipped segment origin while lance spears stay clear of the near plane" {
        for (age in 4..12 step 2) {
            val chain = staffDisplayParts(StaffSpell.CHAIN, age, 20, 0.65, 0.75, false)
            (chain.first().center.z < 0.1f) shouldBe true
            (kotlin.math.abs(chain.first().center.x) < 0.1f) shouldBe true
            (kotlin.math.abs(chain.first().center.y) < 0.1f) shouldBe true

            staffDisplayParts(StaffSpell.LANCE, age, 20, 24.0, 0.75, true).forEach { part ->
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                    val corner = org.joml.Vector3f(part.scale).mul(org.joml.Vector3f(x, y, z)).mul(0.5f)
                    part.rotation.transform(corner).add(part.center)
                    (corner.z > 0.5f) shouldBe true
                }
            }
        }
    }

    "live lightning geometry grows only along the points already traveled" {
        fun route(count: Int) = (0 until count).map { index ->
            org.joml.Vector3f(0f, 0f, index * 1.25f)
        }

        val onePoint = staffLightningTrailParts(route(1))
        val early = staffLightningTrailParts(route(3))
        val middle = staffLightningTrailParts(route(8))
        val capped = staffLightningTrailParts(route(24))
        onePoint.size shouldBe 6
        (early.size > onePoint.size) shouldBe true
        (middle.size > early.size) shouldBe true
        (capped.size <= StaffSpellDisplayEffects.MAX_PARTS) shouldBe true
        (capped.size <= 48) shouldBe true

        val partialRoute = route(5)
        val partial = staffLightningTrailParts(partialRoute)
        partial.first().center.z shouldBe partialRoute.last().z
        partial.maxOf { it.center.z } shouldBe partialRoute.last().z
        val trailLinks = partial.drop(6).take(2 * (partialRoute.size - 1))
        trailLinks.all { it.scale.x in 0.10f..0.16f } shouldBe true
        trailLinks.take(2).all { part ->
            val launchCap = org.joml.Vector3f(0f, 0f, -part.scale.z / 2f)
            part.rotation.transform(launchCap).add(part.center)
            launchCap.distance(partialRoute.first()) >= 0.20f
        } shouldBe true
        trailLinks.take(2).all { it.scale.z in 1.0f..1.1f } shouldBe true
        trailLinks.drop(2).all { it.scale.z in 1.15f..1.25f } shouldBe true

        val cappedRoute = route(24)
        capped.first().center.z shouldBe cappedRoute.last().z
        (capped.minOf { it.center.z } >= cappedRoute[cappedRoute.size - 17].z) shouldBe true
        staffLightningTrailParts(route(4), fade = 0.0).isEmpty() shouldBe true
    }

    "ground ring and lightning links leave small gaps at their joins" {
        fun ringLinksAvoidCornerOverlap(parts: List<StaffDisplayPart>, angleStep: Double): Boolean {
            val center = org.joml.Vector3f()
            parts.forEach { center.add(it.center) }
            center.div(parts.size.toFloat())
            return parts.all { part ->
                val centerRadius = part.center.distance(center).toDouble()
                val fullChord = 2.0 * centerRadius * kotlin.math.tan(angleStep / 2.0)
                part.scale.z < (fullChord - 0.001).toFloat()
            }
        }

        val mark = staffDisplayParts(StaffSpell.MARK, 4, 40, 0.0, 4.5, false, true)
        ringLinksAvoidCornerOverlap(mark.subList(2, 14), kotlin.math.PI / 6.0) shouldBe true
        ringLinksAvoidCornerOverlap(mark.subList(14, 26), kotlin.math.PI / 6.0) shouldBe true

        val frost = staffDisplayParts(StaffSpell.FROST, 4, 30, 0.0, 8.0, true, true)
        ringLinksAvoidCornerOverlap(frost.take(16), kotlin.math.PI / 8.0) shouldBe true

        val ember = staffDisplayParts(StaffSpell.EMBER, 4, 20, 0.0, 2.8, true)
        ringLinksAvoidCornerOverlap(ember.subList(34, 48), 2.0 * kotlin.math.PI / 14.0) shouldBe true

        val nova = staffDisplayParts(StaffSpell.NOVA, 4, 30, 4.0, 8.0, true)
        ringLinksAvoidCornerOverlap(nova.take(16), kotlin.math.PI / 8.0) shouldBe true
        ringLinksAvoidCornerOverlap(nova.subList(16, 32), kotlin.math.PI / 8.0) shouldBe true
    }

    "transient spells grow in and dissolve before removal with stable part identities" {
        StaffSpell.entries.forEach { spell ->
            listOf(false, true).forEach { secondary ->
                fun parts(age: Int) = staffDisplayParts(spell, age, 30, 8.0, 3.0, true, secondary)
                val first = parts(0)
                val body = parts(8)
                val last = parts(28)
                first.size shouldBe body.size
                last.size shouldBe body.size
                first.map { it.material } shouldBe body.map { it.material }
                fun volume(parts: List<StaffDisplayPart>) = parts.sumOf {
                    (it.scale.x * it.scale.y * it.scale.z).toDouble()
                }
                (volume(first) < volume(body) * 0.01) shouldBe true
                (volume(last) < volume(body) * 0.01) shouldBe true
            }
        }
    }

    "frost advances through stable ground rows instead of exposing the whole fan" {
        val early = staffDisplayParts(StaffSpell.FROST, 6, 24, 7.0, 8.3, false)
        val late = staffDisplayParts(StaffSpell.FROST, 14, 24, 7.0, 8.3, false)
        early.size shouldBe late.size
        val lastRowStart = 4 * 8
        (early[0].scale.y > early[lastRowStart].scale.y * 100) shouldBe true
        (late[lastRowStart].scale.y > early[lastRowStart].scale.y * 100) shouldBe true
        early.zip(late).forEach { (a, b) ->
            a.center.x shouldBe b.center.x
            a.center.z shouldBe b.center.z
        }
    }

    "secondary mark opens a tilted accretion ring around a dark core, then collapses" {
        fun outerRadius(age: Int) = staffDisplayParts(StaffSpell.MARK, age, 20, 0.0, 3.5, true, true)
            .subList(14, 26).maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }
        val closed = outerRadius(0)
        val open = outerRadius(8)
        val collapsed = outerRadius(18)
        (open > closed * 4) shouldBe true
        (collapsed < open / 4) shouldBe true
        fun chargeRadius(age: Int) = staffDisplayParts(StaffSpell.MARK, age, 40, 0.0, 4.5, false, true)
            .subList(14, 26).maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }
        (chargeRadius(8) > chargeRadius(0) * 4) shouldBe true
        chargeRadius(38) shouldBe chargeRadius(8)
        val singularity = staffDisplayParts(StaffSpell.MARK, 8, 20, 0.0, 3.5, true, true)
        (singularity[0].scale.x in 0.7f..0.9f) shouldBe true
        singularity[0].center.y shouldBe 1.6f
        singularity.subList(2, 14).all { kotlin.math.abs(it.center.y - 1.525f) < 0.001f } shouldBe true
        val outerRing = singularity.subList(14, 26)
        (outerRing.maxOf { it.center.y } - outerRing.minOf { it.center.y } > 2.4f) shouldBe true
        val normal = org.joml.Quaternionf(outerRing.first().rotation).transform(org.joml.Vector3f(0f, 1f, 0f))
        (kotlin.math.abs(normal.y - kotlin.math.cos(Math.toRadians(35.0)).toFloat()) < 0.01f) shouldBe true
        (kotlin.math.abs(normal.z - kotlin.math.sin(Math.toRadians(35.0)).toFloat()) < 0.01f) shouldBe true
    }

    "secondary frost nova and directed emerald crest travel outward from an empty center" {
        val frostStart = staffDisplayParts(StaffSpell.FROST, 2, 30, 0.0, 8.0, true, true)
        val frostEnd = staffDisplayParts(StaffSpell.FROST, 18, 30, 0.0, 8.0, true, true)
        val frostStartRadius = kotlin.math.hypot(frostStart[0].center.x.toDouble(), frostStart[0].center.z.toDouble())
        val frostEndRadius = kotlin.math.hypot(frostEnd[0].center.x.toDouble(), frostEnd[0].center.z.toDouble())
        (frostStartRadius > 1.6 && frostStartRadius < 1.9) shouldBe true
        (frostEndRadius > frostStartRadius * 3.5f) shouldBe true

        val tidalStart = staffDisplayParts(StaffSpell.NOVA, 2, 30, 12.0, 5.5, true, true)
        val tidalEnd = staffDisplayParts(StaffSpell.NOVA, 18, 30, 12.0, 5.5, true, true)
        tidalStart.forEach { part ->
            if (kotlin.math.hypot(part.center.x.toDouble(), part.center.z.toDouble()) <= 3.0)
                (part.center.y + part.scale.y / 2f <= 0.7f) shouldBe true
        }
        (tidalEnd.take(16).maxOf { it.center.z } > tidalStart.take(16).maxOf { it.center.z } + 8f) shouldBe true
        tidalEnd.take(16).all { kotlin.math.abs(it.center.x) <= 5.6f } shouldBe true
        (tidalEnd.take(16).maxOf { kotlin.math.abs(it.center.x) } >
            tidalStart.take(16).maxOf { kotlin.math.abs(it.center.x) } + 4.5f) shouldBe true
        tidalEnd.take(32).forEachIndexed { index, part ->
            val segment = index % 16
            val offset = if (index < 16) 0.58 else 0.90
            val startAngle = -kotlin.math.PI / 2.0 + segment * kotlin.math.PI / 16.0
            val endAngle = startAngle + kotlin.math.PI / 16.0
            val dx = (kotlin.math.sin(endAngle) - kotlin.math.sin(startAngle)) * 5.5
            val dz = offset * (kotlin.math.cos(endAngle) - kotlin.math.cos(startAngle))
            (part.scale.z < (kotlin.math.hypot(dx, dz) - 0.001).toFloat()) shouldBe true
        }
        val peak = staffDisplayParts(StaffSpell.NOVA, 10, 30, 12.0, 5.5, true, true)
        (kotlin.math.abs(peak[24].center.y - 1.1f) < 0.02f) shouldBe true
        (kotlin.math.abs(peak[40].center.y - 2.0f) < 0.03f) shouldBe true
    }

    "primary emerald nova keeps a low empty center as its two-tier crest passes" {
        (0..6 step StaffSpellDisplayEffects.FRAME_TICKS).forEach { age ->
            val parts = staffDisplayParts(StaffSpell.NOVA, age, 30, 4.0, 8.0, true)
            parts.forEach { part ->
                val radius = kotlin.math.hypot(part.center.x.toDouble(), part.center.z.toDouble())
                if (radius <= 3.0) (part.center.y + part.scale.y / 2f <= 0.7f) shouldBe true
                (radius >= 1.3) shouldBe true
            }
        }
        val peak = staffDisplayParts(StaffSpell.NOVA, 10, 30, 4.0, 8.0, true)
        (kotlin.math.abs(peak[16].center.y - 0.89f) < 0.02f) shouldBe true
        (kotlin.math.abs(peak[32].center.y - 1.1f) < 0.03f) shouldBe true
        (kotlin.math.abs(peak[32].scale.y - 0.7f) < 0.02f) shouldBe true
    }

    "comet flight is steady and its impact shards travel outward without pulsing" {
        val flight = staffDisplayParts(StaffSpell.EMBER, 6, 24, 1.0, 0.85, false)
        val later = staffDisplayParts(StaffSpell.EMBER, 16, 24, 1.0, 0.85, false)
        flight.zip(later).forEach { (a, b) ->
            a.center shouldBe b.center
            a.scale shouldBe b.scale
        }
        var previous = emptyList<Float>()
        (0 until 20 step StaffSpellDisplayEffects.FRAME_TICKS).forEach { age ->
            val shards = staffDisplayParts(StaffSpell.EMBER, age, 20, 0.0, 2.8, true).drop(2)
            val radial = shards.map { it.center.x * it.center.x + it.center.z * it.center.z }
            (previous.isEmpty() || radial.zip(previous).all { (now, before) -> now >= before }) shouldBe true
            previous = radial
        }
        val primary = staffDisplayParts(StaffSpell.EMBER, 8, 20, 0.0, 2.8, true)
        val secondary = staffDisplayParts(StaffSpell.EMBER, 8, 20, 0.0, 3.5, true, true)
        (secondary.drop(2).maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) } >
            primary.drop(2).maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }) shouldBe true
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
            val observerView = Location(h.world, 0.5, 64.0, 7.0)
            observer.teleport(observerView)
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
            observer.teleport(observerView)
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
                if (record.visibleViewers.add(viewer)) shownTo += viewer
                Unit
            }
            every { handle.hideFrom(any()) } answers {
                val viewer = firstArg<Player>().uniqueId
                if (record.visibleViewers.remove(viewer)) hiddenFrom += viewer
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
