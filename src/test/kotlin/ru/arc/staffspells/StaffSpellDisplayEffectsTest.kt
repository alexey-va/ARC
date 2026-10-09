package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.joml.Vector3f
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

    "charged cores launch through the reticle and the solar mass grows after leaving the muzzle" {
        val chain = staffDisplayParts(StaffSpell.CHAIN, 0, 20, 48.0, 0.8, false)
        chain.size shouldBe 47
        (chain[44].scale.x >= 0.18f) shouldBe true
        (chain[44].center.z >= 2.4f) shouldBe true
        (chain.minOf { it.center.z } >= 0f) shouldBe true

        val lance = staffDisplayParts(StaffSpell.LANCE, 0, 20, 24.0, 0.75, false)
        lance.size shouldBe StaffSpellDisplayEffects.MAX_PARTS
        (lance[18].scale.x in 0.50f..0.60f) shouldBe true
        (lance[18].center.z >= 1.9f) shouldBe true
        lance[18].center.y shouldBe -0.75f
        (lance[0].center.z > 1.0f) shouldBe true
        (lance[41].scale.x <= 0.08f && lance[42].scale.x <= 0.08f) shouldBe true
        lance[41].center.x shouldBe 0f
        lance[41].center.y shouldBe -0.75f
        val arrivedLance = staffDisplayParts(StaffSpell.LANCE, staffLanceFlightTicks(24.0),
            staffLanceDuration(24.0), 24.0, 0.75, true)
        arrivedLance[18].center.z shouldBe 24f
        arrivedLance[18].center.y shouldBe 0f
        (arrivedLance[0].center.y < -0.55f) shouldBe true
        (arrivedLance[18].scale.x > 0.8f) shouldBe true
        (arrivedLance[0].scale.x < 0.14f) shouldBe true
        (arrivedLance[18].center.z - arrivedLance[0].center.z > 20f) shouldBe true
        arrivedLance.size shouldBe StaffSpellDisplayEffects.MAX_PARTS
        lance.forEach { part ->
            for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                val corner = org.joml.Vector3f(part.scale).mul(org.joml.Vector3f(x, y, z)).mul(0.5f)
                part.rotation.transform(corner).add(part.center)
                (corner.z > 0.5f) shouldBe true
            }
        }
    }

    "solar flight remains visible across its actual range and shares its particle clock" {
        listOf(8.0, 16.0, 24.0, 48.0).forEach { distance ->
            val flight = staffLanceFlightTicks(distance)
            val duration = staffLanceDuration(distance)
            duration shouldBe flight + 8
            val positions = (0..flight).map { staffLanceFront(it, distance) }
            positions.first() shouldBe 2.0
            positions.last() shouldBe distance
            positions.zipWithNext().all { (before, after) -> after > before && after - before <= 2.800001 } shouldBe true
            (0 until duration - 2 step 2).forEach { age ->
                val parts = staffDisplayParts(StaffSpell.LANCE, age, duration, distance, 0.75, true)
                val head = parts[18]
                head.center.z shouldBe staffLanceFront(age, distance).toFloat()
                (head.scale.x > 0.02f) shouldBe true
            }
            (staffDisplayParts(StaffSpell.LANCE, duration - 1, duration, distance, 0.75, true)
                .maxOf { it.scale.x } < 0.01f) shouldBe true
        }
        staffLanceFlightTicks(48.0) shouldBe 17
    }

    "LANCE shares one yaw-pitch frame for diagonal and vertical rays" {
        listOf(
            Vector3f(0f, 0f, 1f),
            Vector3f(3f, 4f, 12f),
            Vector3f(-6f, 2f, -5f),
            Vector3f(0f, 1f, 0f),
            Vector3f(0f, -1f, 0f),
        ).forEach { direction ->
            val orientation = staffLanceOrientation(direction)
            val forward = orientation.transform(Vector3f(0f, 0f, 1f))
            val right = orientation.transform(Vector3f(-1f, 0f, 0f))
            val up = orientation.transform(Vector3f(0f, 1f, 0f))
            val expectedForward = Vector3f(direction).normalize()

            (forward.distance(expectedForward) < 1.0e-5f) shouldBe true
            (kotlin.math.abs(right.length() - 1f) < 1.0e-5f) shouldBe true
            (kotlin.math.abs(up.length() - 1f) < 1.0e-5f) shouldBe true
            (kotlin.math.abs(right.dot(up)) < 1.0e-5f) shouldBe true
            (kotlin.math.abs(right.dot(forward)) < 1.0e-5f) shouldBe true
            (kotlin.math.abs(up.dot(forward)) < 1.0e-5f) shouldBe true
        }

        val verticalUp = staffLanceOrientation(Vector3f(0f, 1f, 0f))
        (kotlin.math.abs(verticalUp.transform(Vector3f(-1f, 0f, 0f)).x + 1f) < 1.0e-5f) shouldBe true
        (kotlin.math.abs(verticalUp.transform(Vector3f(0f, 1f, 0f)).z + 1f) < 1.0e-5f) shouldBe true
        val verticalDown = staffLanceOrientation(Vector3f(0f, -1f, 0f))
        (kotlin.math.abs(verticalDown.transform(Vector3f(0f, 1f, 0f)).z - 1f) < 1.0e-5f) shouldBe true
        (kotlin.math.abs(staffLanceOrientation(Vector3f()).transform(Vector3f(0f, 0f, 1f)).z - 1f) < 1.0e-5f) shouldBe true
    }

    "LANCE grows a stable off-axis channel from its safe muzzle to the moving head" {
        val distance = 48.0
        val duration = staffLanceDuration(distance)
        val launch = staffDisplayParts(StaffSpell.LANCE, 0, duration, distance, 0.75, true)
        val middle = staffDisplayParts(StaffSpell.LANCE, staffLanceFlightTicks(distance) / 2,
            duration, distance, 0.75, true)
        val arrival = staffDisplayParts(StaffSpell.LANCE, staffLanceFlightTicks(distance),
            duration, distance, 0.75, true)
        fun trail(parts: List<StaffDisplayPart>) = parts.take(18) + parts.subList(19, 41)

        launch.size shouldBe 48
        launch.count { it.visible } shouldBe 8 // compact core, two muzzle facets and five head facets
        middle.count { it.visible } shouldBe 27
        arrival.count { it.visible } shouldBe 48
        launch.map { it.material } shouldBe arrival.map { it.material }
        launch[18].center.z shouldBe 2f
        launch[18].center.x shouldBe 0f
        launch[18].center.y shouldBe -0.75f
        middle[18].center.z shouldBe staffLanceFront(staffLanceFlightTicks(distance) / 2, distance).toFloat()
        arrival[18].center.z shouldBe distance.toFloat()
        arrival[18].center.y shouldBe 0f
        arrival[41].center.y shouldBe -0.75f
        (arrival[18].scale.x <= 0.93f) shouldBe true
        (trail(arrival).maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.y.toDouble()) } > 0.8) shouldBe true
        (staffLanceOrbitOffset(2.0, distance).length() < 0.001f) shouldBe true
        (staffLanceOrbitOffset(15.0, distance).length() > 0.7f) shouldBe true
        (staffLanceOrbitOffset(distance, distance).length() < 0.001f) shouldBe true
        staffLanceCenterOffset(2.0, distance) shouldBe -0.75
        staffLanceCenterOffset(distance, distance) shouldBe 0.0
        staffLanceCenterOffset(1.0, 1.0) shouldBe 0.0

        listOf(launch, middle, arrival).forEach { state ->
            state.filter { it.visible }.all { part ->
                org.joml.Vector3f(part.center).length() >= 1.15f + part.scale.length() / 2f
            } shouldBe true
        }
    }

    "lightning extends stable handles and hides unreached route slots" {
        withDisplayHarness { h ->
            val caster = h.player("lightning-owner")
            val eye = caster.eyeLocation
            val id = h.effects.play(caster.uniqueId, StaffSpell.CHAIN, eye,
                eye.clone().add(0.0, 0.0, 48.0), durationTicks = 60)!!
            val handles = h.recorder.records.toList()
            handles.size shouldBe 47
            handles.all { caster.uniqueId !in it.visibleViewers } shouldBe true
            val points = (0..4).map { eye.clone().add(0.0, 0.0, it * 2.0) }
            h.effects.moveTrail(id, points.take(2))
            h.scheduler.tick(2)
            (caster.uniqueId in handles[0].visibleViewers) shouldBe true
            handles.subList(1, 44).all { caster.uniqueId !in it.visibleViewers } shouldBe true
            h.effects.moveTrail(id, points)
            h.scheduler.tick(2)
            h.recorder.records.size shouldBe 47
            handles.all { it.removals == 0 } shouldBe true
            handles.take(4).all { caster.uniqueId in it.visibleViewers } shouldBe true
            h.effects.finishTrail(id)
            h.scheduler.tick(8)
            handles.all { it.removals == 1 } shouldBe true
        }
    }

    "remaining ice and comet ground links leave small gaps at their joins" {
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

        val frost = staffDisplayParts(StaffSpell.FROST, 4, 30, 0.0, 8.0, true, true)
        ringLinksAvoidCornerOverlap(frost.take(16), kotlin.math.PI / 8.0) shouldBe true

        val ember = staffDisplayParts(StaffSpell.EMBER, 4, 20, 0.0, 2.8, true)
        ringLinksAvoidCornerOverlap(ember.subList(34, 48), 2.0 * kotlin.math.PI / 14.0) shouldBe true

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
                if (spell in setOf(StaffSpell.CHAIN, StaffSpell.LANCE, StaffSpell.MARK)) {
                    (volume(first) > volume(body) * 0.01) shouldBe true
                } else {
                    (volume(first) < volume(body) * 0.01) shouldBe true
                }
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

    "mark charges as a dense violet core before its radial crystal tear opens" {
        fun mark(age: Int) = staffDisplayParts(StaffSpell.MARK, age, 40, 0.0, 4.5, false)
        val closed = mark(0)
        val open = mark(8)
        closed.size shouldBe 17
        open.size shouldBe 17
        (open.take(5).minOf { it.scale.x } > 0.8f) shouldBe true
        (open.take(5).maxOf { kotlin.math.sqrt(it.center.lengthSquared().toDouble()) } < 0.3) shouldBe true
        fun tearRadius(parts: List<StaffDisplayPart>) = parts.drop(5).maxOf {
            kotlin.math.sqrt(it.center.lengthSquared().toDouble())
        }
        (tearRadius(open) > tearRadius(closed) + 0.45) shouldBe true
        val fragments = open.drop(5)
        (fragments.minOf { it.scale.x } >= 0.42f) shouldBe true
        (fragments.maxOf { it.scale.x } >= 0.60f) shouldBe true
    }

    "secondary mark gathers a chunky three-dimensional singularity then collapses" {
        fun parts(age: Int) = staffDisplayParts(StaffSpell.MARK, age, 20, 0.0, 3.5, true, true)
        fun cloudRadius(shape: List<StaffDisplayPart>) = shape.drop(7).maxOf {
            kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble())
        }
        val closed = parts(0)
        val open = parts(8)
        val collapsed = parts(18)
        closed.size shouldBe 29
        (open[0].scale.x > 0.8f) shouldBe true
        open.take(7).all { it.center.y in 1.45f..1.75f } shouldBe true
        (open.drop(7).maxOf { it.center.y } - open.drop(7).minOf { it.center.y } > 0.8f) shouldBe true
        (cloudRadius(open) <= cloudRadius(closed) + 0.001) shouldBe true
        (cloudRadius(collapsed) < cloudRadius(open) * 0.30) shouldBe true
    }

    "secondary frost nova and chunky emerald tidal front preserve their different paths" {
        val frostStart = staffDisplayParts(StaffSpell.FROST, 2, 30, 0.0, 8.0, true, true)
        val frostEnd = staffDisplayParts(StaffSpell.FROST, 18, 30, 0.0, 8.0, true, true)
        val frostStartRadius = kotlin.math.hypot(frostStart[0].center.x.toDouble(), frostStart[0].center.z.toDouble())
        val frostEndRadius = kotlin.math.hypot(frostEnd[0].center.x.toDouble(), frostEnd[0].center.z.toDouble())
        (frostStartRadius > 1.6 && frostStartRadius < 1.9) shouldBe true
        (frostEndRadius > frostStartRadius * 3.5f) shouldBe true

        val tidalStart = staffDisplayParts(StaffSpell.NOVA, 2, 20, 12.0, 5.5, true, true)
        val tidalEnd = staffDisplayParts(StaffSpell.NOVA, 18, 20, 12.0, 5.5, true, true)
        tidalStart.size shouldBe 39
        tidalEnd.size shouldBe 39
        (tidalEnd.maxOf { it.center.z } > tidalStart.maxOf { it.center.z } + 8f) shouldBe true
        (tidalEnd.maxOf { kotlin.math.abs(it.center.x) } <= 5.7f) shouldBe true
        (tidalEnd.maxOf { kotlin.math.abs(it.center.x) } >
            tidalStart.maxOf { kotlin.math.abs(it.center.x) } + 4.5f) shouldBe true
        (tidalEnd.minOf { it.center.y - it.scale.y / 2f } >= -0.02f) shouldBe true
        (tidalEnd.maxOf { it.center.y + it.scale.y / 2f } < 0.25f) shouldBe true
        (tidalEnd.maxOf { it.scale.x } < 0.1f) shouldBe true
        val peak = staffDisplayParts(StaffSpell.NOVA, 10, 30, 12.0, 5.5, true, true)
        (peak.maxOf { it.center.y + it.scale.y / 2f } > 2.0f) shouldBe true
        (peak[1].center.y > 1.4f) shouldBe true
        (peak[1].scale.y >= 1.1f) shouldBe true
    }

    "primary emerald nova is a thick outward-moving radial crystal storm" {
        (0..6 step StaffSpellDisplayEffects.FRAME_TICKS).forEach { age ->
            val parts = staffDisplayParts(StaffSpell.NOVA, age, 30, 4.0, 8.0, true)
            parts.size shouldBe 42
            parts.forEach { part ->
                val radius = kotlin.math.hypot(part.center.x.toDouble(), part.center.z.toDouble())
                (radius >= 1.3) shouldBe true
            }
        }
        val early = staffDisplayParts(StaffSpell.NOVA, 2, 30, 4.0, 8.0, true)
        val peak = staffDisplayParts(StaffSpell.NOVA, 10, 30, 4.0, 8.0, true)
        val late = staffDisplayParts(StaffSpell.NOVA, 18, 30, 4.0, 8.0, true)
        (late.minOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) } >
            early.minOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) } + 5.5) shouldBe true
        (peak.minOf { it.scale.x } >= 0.48f) shouldBe true
        (peak.maxOf { it.center.y + it.scale.y / 2f } > 1.4f) shouldBe true
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
