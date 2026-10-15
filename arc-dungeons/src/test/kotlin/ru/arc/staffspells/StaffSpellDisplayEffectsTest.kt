package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.mockbukkit.mockbukkit.world.WorldMock
import io.mockk.verify
import org.joml.Vector3f
import org.bukkit.Location
import org.bukkit.Material
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
        StaffSpell.entries.forEach spellLoop@{ spell ->
            if (spell in setOf(StaffSpell.NECROMANCER, StaffSpell.INFERNO, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM)) return@spellLoop // Native actors or particles own these silhouettes.
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

    "charged crown keeps eleven stable shards and grows them without entering the player eye" {
        val early = staffChargeParts(StaffSpell.ICICLE, 0.0, 0)
        val half = staffChargeParts(StaffSpell.ICICLE, 0.5, 18)
        val full = staffChargeParts(StaffSpell.ICICLE, 1.0, 36)
        full.size shouldBe 88
        early.size shouldBe full.size
        early.map { it.material } shouldBe full.map { it.material }
        early.map { it.visible } shouldBe full.map { it.visible }
        early.all { it.visible } shouldBe true
        (early.sumOf { it.scale.length().toDouble() } < half.sumOf { it.scale.length().toDouble() }) shouldBe true
        (half.sumOf { it.scale.length().toDouble() } < full.sumOf { it.scale.length().toDouble() }) shouldBe true
        full.all { it.center.length() - it.scale.length() / 2 > 1.15f } shouldBe true
    }

    "lightning launches through the reticle without a near-camera shield" {
        val chain = staffDisplayParts(StaffSpell.CHAIN, 0, 20, 48.0, 0.8, false)
        chain.size shouldBe 47
        (chain[44].scale.x >= 0.18f) shouldBe true
        (chain[44].center.z >= 2.4f) shouldBe true
        (chain.minOf { it.center.z } >= 0f) shouldBe true

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

    "GALE rotation keeps elevated vectors on a horizontal yaw plane" {
        val forward = staffYawOnlyOrientation(Vector3f(3f, 4f, 4f)).transform(Vector3f(0f, 0f, 1f))
        (kotlin.math.abs(forward.x - 0.6f) < 1.0e-5f) shouldBe true
        (kotlin.math.abs(forward.y) < 1.0e-5f) shouldBe true
        (kotlin.math.abs(forward.z - 0.8f) < 1.0e-5f) shouldBe true
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

    "comet ground links leave small gaps at their joins" {
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

        val ember = staffDisplayParts(StaffSpell.EMBER, 4, 20, 0.0, 2.8, true)
        ringLinksAvoidCornerOverlap(ember.subList(34, 48), 2.0 * kotlin.math.PI / 14.0) shouldBe true

    }

    "transient spells grow in and dissolve before removal with stable part identities" {
        StaffSpell.entries.filterNot { it in setOf(StaffSpell.NECROMANCER, StaffSpell.INFERNO, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM) }.forEach { spell ->
            listOf(false, true).forEach { secondary ->
                fun parts(age: Int) = staffDisplayParts(spell, age, 30, 8.0, 3.0, true, secondary)
                val first = parts(0)
                val body = parts(8)
                val last = parts(if (spell in ARSENAL_SPELLS) 29 else 28)
                first.size shouldBe body.size
                last.size shouldBe body.size
                first.map { it.material } shouldBe body.map { it.material }
                fun volume(parts: List<StaffDisplayPart>) = parts.sumOf {
                    (it.scale.x * it.scale.y * it.scale.z).toDouble()
                }
                if (spell == StaffSpell.NOVA && secondary) {
                    fun outerRadius(parts: List<StaffDisplayPart>) = parts.maxOf {
                        kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble())
                    }
                    (outerRadius(parts(20)) < outerRadius(body)) shouldBe true
                } else if (spell in ARSENAL_SPELLS) {
                    (volume(first) < volume(body)) shouldBe true
                } else if (spell in setOf(StaffSpell.CHAIN, StaffSpell.LANCE, StaffSpell.MARK, StaffSpell.EMBER, StaffSpell.ICICLE)) {
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

    "mark holds a vertical fracture then releases its fragments outward without rebound" {
        val held = staffDisplayParts(StaffSpell.MARK, 18, 18, 0.0, 1.3, false)
        (held.take(5).maxOf { it.scale.y } > 1.5f) shouldBe true
        var previous = 0.0
        (0..12 step 2).forEach { age ->
            val parts = staffDisplayParts(StaffSpell.MARK, age, 20, 0.0, 3.5, true,
                animationAgeTicks = 18 + age)
            val radius = parts.drop(5).maxOf { it.center.length().toDouble() }
            (radius >= previous) shouldBe true
            previous = radius
        }
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
        (closed[0].scale.x > 0.8f) shouldBe true
        open.take(7).all { it.center.y in 1.45f..1.75f } shouldBe true
        (closed.drop(7).maxOf { it.center.y } - closed.drop(7).minOf { it.center.y } > 0.8f) shouldBe true
        (cloudRadius(open) <= cloudRadius(closed) + 0.001) shouldBe true
        (cloudRadius(collapsed) < cloudRadius(closed) * 0.15) shouldBe true
    }

    "shoulder icicle assembles as one body then fractures" {
        val crystal = staffDisplayParts(StaffSpell.ICICLE, 8, 80, 1.0, 0.8, false)
        val flying = staffDisplayParts(StaffSpell.ICICLE, 16, 80, 1.0, 0.8, false)
        crystal.size shouldBe 8
        crystal.zip(flying).forEach { (a, b) ->
            a.center shouldBe b.center
            a.scale shouldBe b.scale
            a.material shouldBe b.material
        }
        val shatter = staffDisplayParts(StaffSpell.ICICLE, 4, 10, 0.0, 0.9, true)
        (shatter.maxOf { it.center.length() } > crystal.maxOf { it.center.length() }) shouldBe true
        val faded = staffDisplayParts(StaffSpell.ICICLE, 8, 10, 0.0, 0.9, true)
        (faded.maxOf { it.scale.length() } < 0.002f) shouldBe true

    }

    "restored frost nova keeps its 48-slot ring and travels outward as one radial front" {
        val early = staffDisplayParts(StaffSpell.FROST, 2, 30, 0.0, 8.0, true, secondary = true)
        val peak = staffDisplayParts(StaffSpell.FROST, 10, 30, 0.0, 8.0, true, secondary = true)
        val late = staffDisplayParts(StaffSpell.FROST, 18, 30, 0.0, 8.0, true, secondary = true)
        early.size shouldBe 48
        late.size shouldBe 48
        early.map { it.material } shouldBe late.map { it.material }
        val earlyReach = early.maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }
        val lateReach = late.maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }
        (lateReach > earlyReach + 5.0) shouldBe true
        (peak.maxOf { it.center.y + it.scale.y / 2f } > 1.0f) shouldBe true
    }

    "fault forms a grounded chunky rupture and carries its fragments on smooth outward arcs" {
        fun parts(age: Int, duration: Int = 22, radius: Double = 2.0, secondary: Boolean = false) =
            staffDisplayParts(StaffSpell.FAULT, age, duration, 0.0, radius, true, secondary)
        val early = parts(4)
        val middle = parts(10)
        val late = parts(16)
        val wide = parts(10, duration = 24, radius = 4.5, secondary = true)
        middle.size shouldBe 36
        early.size shouldBe 36
        late.size shouldBe 36
        middle.map { it.material } shouldBe early.map { it.material }
        middle.map { it.material } shouldBe late.map { it.material }
        middle.all { it.scale.x >= 0.30f && it.scale.y >= 0.30f && it.scale.z >= 0.30f } shouldBe true
        (middle.minOf { it.center.y - it.scale.y / 2f } >= -0.001f) shouldBe true
        fun firstFragmentRadius(age: Int) = parts(age).drop(24).first().center.let {
            kotlin.math.hypot(it.x.toDouble(), it.z.toDouble())
        }
        (firstFragmentRadius(4) < firstFragmentRadius(10)) shouldBe true
        (firstFragmentRadius(10) < firstFragmentRadius(12)) shouldBe true
        (wide.maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) } >
            middle.maxOf { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) }) shouldBe true
        (wide.maxOf { it.center.y } > middle.maxOf { it.center.y }) shouldBe true
        val dissolved = parts(20)
        (dissolved.maxOf { it.scale.length() } < late.maxOf { it.scale.length() }) shouldBe true
    }

    "frost geometry reaches the extended 28-block range" {
        val parts = staffDisplayParts(StaffSpell.FROST, 14, 24, 28.0, 12.0, false)
        (parts.maxOf { it.center.z } > 24f) shouldBe true
        parts.size shouldBe 40
    }

    "comet flight settles at full size and its impact shards travel outward without pulsing" {
        val flight = staffDisplayParts(StaffSpell.EMBER, 16, 24, 1.0, 0.85, false)
        val later = staffDisplayParts(StaffSpell.EMBER, 20, 24, 1.0, 0.85, false)
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
        val meteor = staffDisplayParts(StaffSpell.EMBER, 8, 20, 0.0, 2.8, true, true)
        // Same radius: the meteor has a vertical plume and heavy ejecta, not a larger recolored comet.
        (meteor.maxOf { it.center.y } > primary.maxOf { it.center.y } * 1.5f) shouldBe true
        meteor.takeLast(14).all { it.material in setOf(Material.MAGMA_BLOCK, Material.BLACKSTONE) } shouldBe true
    }

    "comet is visible at its release muzzle and accelerates without resizing its charged core" {
        listOf(0.0, 1.0).forEach { charge ->
            val eye = org.bukkit.util.Vector()
            var originZ = 2.4
            var previousAge = 0
            var previousParts = staffDisplayParts(StaffSpell.EMBER, previousAge, 80, 48.0, 0.85,
                impact = false, charge = charge)
            val visibleCoreScales = mutableListOf<Float>()
            val releaseCore = previousParts.single { it.identity == "core:0" }
            val releasePadding = staffEyeClearance(StaffSpell.EMBER, impact = false) + .15 + releaseCore.scale.length() * .5
            (originZ + releaseCore.center.z > releasePadding) shouldBe true
            var firstVisibleAge: Int? = 0
            visibleCoreScales += releaseCore.scale.length()
            var previousFrameStep = 0.0

            for (age in StaffSpellDisplayEffects.FRAME_TICKS..20 step StaffSpellDisplayEffects.FRAME_TICKS) {
                val previousZ = originZ
                val nextZ = previousZ + (previousAge + 1..age).sumOf { staffEmberStep(it, 1.2) }
                val frameStep = nextZ - previousZ
                (frameStep + 1.0e-8 >= previousFrameStep) shouldBe true
                (nextZ > previousZ) shouldBe true
                val currentParts = staffDisplayParts(StaffSpell.EMBER, age, 80, 48.0, 0.85,
                    impact = false, charge = charge)
                val oldCore = previousParts.single { it.identity == "core:0" }
                val core = currentParts.single { it.identity == "core:0" }
                val from = org.bukkit.util.Vector(oldCore.center.x.toDouble(), oldCore.center.y.toDouble(),
                    previousZ + oldCore.center.z)
                val to = org.bukkit.util.Vector(core.center.x.toDouble(), core.center.y.toDouble(), nextZ + core.center.z)
                val cameraPadding = staffEyeClearance(StaffSpell.EMBER, impact = false) + 0.15 +
                    maxOf(oldCore.scale.length(), core.scale.length()) * 0.5
                val visible = staffSweptDistance(eye, from, to) >= cameraPadding

                if (visible && firstVisibleAge == null) firstVisibleAge = age
                if (firstVisibleAge != null) visibleCoreScales += core.scale.length()
                originZ = nextZ
                previousAge = age
                previousParts = currentParts
                previousFrameStep = frameStep
            }

            firstVisibleAge shouldBe 0
            val firstSizeRatio = visibleCoreScales.first() / visibleCoreScales.last()
            firstSizeRatio shouldBe 1f
            visibleCoreScales.zipWithNext().all { (before, after) -> after >= before } shouldBe true
        }
    }

    "defenses retain passenger handles through turns and impact then clean up on expiry and cancel" {
        withDisplayHarness { h ->
            val caster = h.player("shield-owner")
            val observer = h.player("shield-observer").apply {
                teleport(Location(h.world, .5, 64.0, 6.0))
            }
            val entitiesBefore = h.world.entities.size
            // EMBER and CHAIN have special flight clocks/interpolation; shields must not inherit them.
            listOf(StaffSpell.EMBER, StaffSpell.CHAIN).forEach { spell ->
                val before = h.recorder.records.size
                val id = h.effects.defend(caster, spell, 24, allAround = false)!!
                val records = h.recorder.records.drop(before)
                records.size shouldBe 12
                h.scheduler.tick(4)
                records.all { caster.uniqueId in it.visibleViewers && observer.uniqueId in it.visibleViewers } shouldBe true
                caster.teleport(caster.location.clone().add(2.0, 0.0, 0.0).apply { yaw = 45f })
                h.scheduler.tick(2)
                records.forEach {
                    verify(atLeast = 2) { it.handle.attachTo(caster) }
                    it.teleports.isEmpty() shouldBe true
                    it.interpolationDurationTicks shouldBe 2
                    it.poses.all(DisplayPose::isFinite) shouldBe true
                }
                h.effects.defenseImpact(id, .5, depleted = false)
                h.scheduler.tick(2)
                h.recorder.records.size shouldBe before + 12
                records.all { it.removals == 0 } shouldBe true
                h.effects.defenseImpact(id, 0.0, depleted = true)
                h.scheduler.tick(6)
                records.all { it.removals == 1 } shouldBe true
            }
            val beforeShell = h.recorder.records.size
            h.effects.defend(caster, StaffSpell.FROST, 400, allAround = true)
            val shell = h.recorder.records.drop(beforeShell)
            shell.size shouldBe 40
            h.scheduler.tick(398)
            shell.all { it.removals == 0 } shouldBe true
            h.scheduler.tick(2)
            shell.all { it.removals == 1 } shouldBe true
            val beforeCancel = h.recorder.records.size
            h.effects.defend(caster, StaffSpell.FAULT, 32, allAround = false)
            h.effects.cancel(caster.uniqueId)
            h.recorder.records.drop(beforeCancel).all { it.removals == 1 } shouldBe true
            h.world.entities.size shouldBe entitiesBefore
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

    "comet keyframes halve position updates without stale timer teleports or future impact origins" {
        withDisplayHarness { h ->
            val caster = h.player("comet-frames")
            val start = Location(h.world, 0.5, 66.0, 4.0)
            val id = h.effects.play(caster.uniqueId, StaffSpell.EMBER, start,
                start.clone().add(0.0, 0.0, 1.0), durationTicks = 80, controllerDriven = true)!!
            val body = h.recorder.records.take(4)
            var distance = 0.0
            repeat(20) { index ->
                distance += staffEmberStep(index + 1, 1.2)
                if ((index + 1) % StaffSpellDisplayEffects.FRAME_TICKS == 0) {
                    h.effects.move(id, start.clone().add(0.0, 0.0, distance))
                    h.effects.synchronize(id, index + 1)
                }
                val counts = body.map { it.teleports.size }
                h.scheduler.tick(1)
                body.map { it.teleports.size } shouldBe counts
            }
            body.forEach { part ->
                part.teleports.size shouldBe 11 // Initial frame plus ten keyframes, not twenty tick updates.
                part.teleports.zipWithNext().all { (a, b) -> b.z > a.z } shouldBe true
                part.interpolationDurationTicks shouldBe StaffSpellDisplayEffects.FRAME_TICKS
                part.teleportDurationTicks shouldBe StaffSpellDisplayEffects.FRAME_TICKS
                verify(atLeast = 1) { part.handle.teleportDuration = 2 }
            }
            h.effects.impact(id, start.clone().add(0.0, 0.0, distance), 2.8, 20)
            body.take(2).forEach { part ->
                // Only semantic core pieces survive; the wake retires at collision.
                (part.teleports.last().z <= start.z + distance + 1.0) shouldBe true
            }
        }
    }

    "Q and F custom scenes reuse handles and coalesce tick poses with interpolation" {
        for (spell in listOf(StaffSpell.CHAIN, StaffSpell.EMBER)) withDisplayHarness { h ->
            val caster = h.player("hotkey-frame-owner")
            val start = Location(h.world, 0.5, 66.0, 4.0)
            val parts = listOf(StaffDisplayPart(Material.QUARTZ_BLOCK, Vector3f(), Vector3f(.4f),
                org.joml.Quaternionf(), identity = "hotkey-body"))
            val id = h.effects.custom(caster.uniqueId, spell, start, 24) { parts }!!
            val record = h.recorder.records.single()
            repeat(20) { index ->
                h.effects.updateCustom(id, start.clone().add(0.0, 0.0, (index + 1) * .2), parts)
                h.scheduler.tick(1)
                record.teleports.size shouldBe 1 + (index + 1) / 2
            }

            h.recorder.records.size shouldBe 1
            record.removals shouldBe 0
            record.interpolationDurationTicks shouldBe 2
            record.teleportDurationTicks shouldBe 2
            record.teleports.zipWithNext().all { (a, b) -> b.z > a.z } shouldBe true
            h.scheduler.tick(4)
            record.removals shouldBe 1
            h.effects.isActive(id) shouldBe false
        }
    }

    "EMBER charge tweens each input update across four ticks and retains its packet handles" {
        withDisplayHarness { h ->
            val caster = h.player("ember-charge-interpolation")
            val eye = caster.eyeLocation
            val id = h.effects.play(caster.uniqueId, StaffSpell.EMBER, eye,
                durationTicks = 220, charging = true)!!
            val body = h.recorder.records.toList()
            body.size shouldBe 21
            body.forEach {
                it.interpolationDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
                it.teleportDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
            }
            val handles = body.map { it.handle }
            val initialCoreScale = body.first().poses.last().values[3]

            h.effects.charge(id, eye, 0.5)
            body.forEach { it.poses.size shouldBe 2 }
            val halfCoreScale = body.first().poses.last().values[3]
            (halfCoreScale > initialCoreScale) shouldBe true
            h.scheduler.tick(4)
            body.forEach {
                it.poses.size shouldBe 2
                it.interpolationDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
                it.teleportDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
            }

            h.effects.charge(id, eye, 1.0)
            body.forEach { it.poses.size shouldBe 3 }
            (body.first().poses.last().values[3] > halfCoreScale) shouldBe true
            body.forEachIndexed { index, part ->
                (part.handle === handles[index]) shouldBe true
                part.interpolationDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
                part.teleportDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
                verify(atLeast = 1) { part.handle.teleportDuration = StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS }
            }
            body.all { it.removals == 0 } shouldBe true
            h.recorder.records.size shouldBe body.size

            val icicle = h.player("icicle-charge-interpolation")
            h.effects.play(icicle.uniqueId, StaffSpell.ICICLE, icicle.eyeLocation,
                durationTicks = 220, charging = true)!!
            h.recorder.records.drop(body.size).forEach {
                it.interpolationDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
                it.teleportDurationTicks shouldBe StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS
            }
        }
    }

    "charged projectiles keep their exact handles world pose and body on release" {
        withDisplayHarness { h ->
            val caster = h.player("charge-release")
            for (spell in listOf(StaffSpell.EMBER, StaffSpell.ICICLE)) for (pitch in listOf(-22f, -90f, 90f)) {
                val before = h.recorder.records.size
                val at = caster.eyeLocation.clone().apply { yaw = 37f; this.pitch = pitch }
                if (spell == StaffSpell.ICICLE) at.add(3.0, 1.0, -2.0)
                val id = h.effects.play(caster.uniqueId, spell, at, durationTicks = 220,
                    charging = true, chargeSlot = if (spell == StaffSpell.ICICLE) 5 else null)!!
                h.effects.charge(id, at, .63)
                val body = h.recorder.records.drop(before).toList()
                val positions = body.map { it.teleports.last().clone() }
                val poses = body.map { it.poses.last() }
                val origin = h.effects.chargeOrigin(id)!!
                h.effects.releaseCharge(id, 80, controllerDriven = true) shouldBe id
                h.effects.chargeOrigin(id) shouldBe null
                h.recorder.records.size shouldBe before + body.size
                body.forEachIndexed { index, record ->
                    record.removals shouldBe 0
                    record.blockUpdates shouldBe 0
                    (record.teleports.last().distance(positions[index]) < 1e-5) shouldBe true
                    record.poses.last() shouldBe poses[index]
                }
                val displacement = at.direction.multiply(1.2)
                h.effects.moveProjectile(id, origin.clone().add(displacement), at.direction)
                h.effects.synchronize(id, 1)
                body.forEachIndexed { index, record ->
                    record.removals shouldBe 0
                    record.poses.last().values.zip(poses[index].values).forEach { (moved, held) ->
                        (kotlin.math.abs(moved - held) < 1e-5f) shouldBe true
                    }
                    (record.teleports.last().distance(positions[index].clone().add(displacement)) < 1e-5) shouldBe true
                }
                h.effects.remove(id)
                body.all { it.removals == 1 } shouldBe true
            }
        }
    }

    "preparation scenes honor their own clock and finite lifetime for Ember and Mark" {
        withDisplayHarness { h ->
            val caster = h.player("preparation-owner")
            for (spell in listOf(StaffSpell.EMBER, StaffSpell.MARK)) {
                val before = h.recorder.records.size
                h.effects.play(caster.uniqueId, spell, caster.eyeLocation,
                    durationTicks = 10, secondary = true, preparation = true)
                val parts = h.recorder.records.drop(before)
                parts.isNotEmpty() shouldBe true
                h.scheduler.tick(4)
                parts.all { it.poses.size == 3 && it.removals == 0 } shouldBe true
                h.scheduler.tick(6)
                parts.all { it.removals == 1 } shouldBe true
            }
        }
    }

    "Ember impact retains shared core slots and births new debris across the first four ticks" {
        withDisplayHarness { h ->
            val caster = h.player("ember-continuity")
            val start = Location(h.world, 0.5, 66.0, 4.0)
            val id = h.effects.play(caster.uniqueId, StaffSpell.EMBER, start,
                start.clone().add(0.0, 0.0, 1.0), durationTicks = 80, controllerDriven = true)!!
            val flight = h.recorder.records.toList()
            flight.size shouldBe 21
            h.effects.synchronize(id, 2)

            val collision = start.clone().add(0.0, 0.0, 1.5)
            h.effects.impact(id, collision, radius = 2.8, durationTicks = 12)

            val newParts = h.recorder.records.drop(flight.size).toList()
            newParts.size shouldBe 46
            flight.take(2).all { it.removals == 0 && it.blockUpdates == 1 } shouldBe true
            flight.drop(2).all { it.removals == 1 } shouldBe true
            val impactParts = staffDisplayParts(StaffSpell.EMBER, 0, 12, 1.5, 2.8, true).drop(2)
            newParts.zip(impactParts).all { (record, part) ->
                val scale = record.poses.last().values.drop(3).take(3).sumOf { it.toDouble() * it.toDouble() }
                val size = part.scale.length().toDouble()
                kotlin.math.abs(kotlin.math.sqrt(scale) / size - 0.04) < 1.0e-4
            } shouldBe true

            h.scheduler.tick(2)
            val ageTwoParts = staffDisplayParts(StaffSpell.EMBER, 2, 12, 1.5, 2.8, true).drop(2)
            newParts.zip(ageTwoParts).all { (record, part) ->
                val scale = record.poses.last().values.drop(3).take(3).sumOf { it.toDouble() * it.toDouble() }
                val size = part.scale.length().toDouble()
                kotlin.math.abs(kotlin.math.sqrt(scale) / size - 0.52) < 1.0e-4
            } shouldBe true

            h.scheduler.tick(2)
            val ageFourParts = staffDisplayParts(StaffSpell.EMBER, 4, 12, 1.5, 2.8, true).drop(2)
            newParts.zip(ageFourParts).all { (record, part) ->
                val scale = record.poses.last().values.drop(3).take(3).sumOf { it.toDouble() * it.toDouble() }
                val size = part.scale.length().toDouble()
                kotlin.math.abs(kotlin.math.sqrt(scale) / size - 1.0) < 1.0e-4
            } shouldBe true
        }
    }

    "controller-driven scenes render only at synchronized ages" {
        withDisplayHarness { h ->
            val caster = h.player("controller-clock")
            val start = Location(h.world, 0.5, 66.0, 4.0)
            val id = h.effects.play(caster.uniqueId, StaffSpell.ICICLE, start, durationTicks = 80,
                controllerDriven = true)!!
            val records = h.recorder.records.toList()

            h.scheduler.tick(4)
            records.all { it.poses.size == 1 } shouldBe true

            val moved = start.clone().add(2.0, 0.0, 0.0)
            h.effects.move(id, moved)
            h.effects.synchronize(id, 0)
            records.all { it.poses.size == 2 } shouldBe true
            val part = staffDisplayParts(StaffSpell.ICICLE, 0, 80, 0.1, 1.0, false).first()
            records.first().teleports.last().x shouldBe moved.x + part.center.x.toDouble()

            h.effects.synchronize(id, 4)
            records.all { it.poses.size == 3 } shouldBe true
            h.scheduler.tick(4)
            h.effects.synchronize(id, 4)
            records.all { it.poses.size == 3 } shouldBe true
        }
    }

    "part budgets admit eleven small icicles without increasing the global display limit" {
        withDisplayHarness { h ->
            val caster = h.player("shard-budget-caster")
            val origin = Location(h.world, 0.5, 68.0, 7.5)
            repeat(11) { h.effects.play(caster.uniqueId, StaffSpell.ICICLE, origin, durationTicks = 80) }
            h.recorder.records.count { it.removals == 0 } shouldBe 88
            val others = (1..4).map { h.player("budget-$it") }
            others.forEach { player -> repeat(5) { h.effects.play(player.uniqueId, StaffSpell.FROST, origin, durationTicks = 80) } }
            h.recorder.records.any { it.removals > 0 } shouldBe true
            (h.recorder.records.count { it.removals == 0 } <= StaffSpellDisplayEffects.MAX_TOTAL_PARTS) shouldBe true
            h.scheduler.tick(4)
            others.forEach { viewer ->
                (h.recorder.records.count { it.removals == 0 && viewer.uniqueId in it.visibleViewers } <=
                    StaffSpellDisplayEffects.MAX_PARTS_PER_VIEWER) shouldBe true
            }
        }
    }

})

private val ARSENAL_SPELLS = setOf(StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.GALE,
    StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.OSSUARY)

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
    var interpolationDurationTicks: Int = -1,
    var teleportDurationTicks: Int = -1,
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
            every { handle.isValid } returns true
            every { handle.blockData } answers { record.block }
            every { handle.blockData = any() } answers {
                record.block = firstArg<BlockData>()
                record.blockUpdates++
                Unit
            }
            every { handle.interpolationDuration = any() } answers {
                record.interpolationDurationTicks = firstArg<Int>()
                Unit
            }
            every { handle.teleportDuration = any() } answers {
                record.teleportDurationTicks = firstArg<Int>()
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
    mockkConstructor(WorldMock::class)
    try {
        // Empty, loaded air fixture: cover geometry is verified by the combat/ray specs.
        every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { anyConstructed<WorldMock>().rayTraceBlocks(any<Location>(), any<org.bukkit.util.Vector>(),
            any<Double>(), any<org.bukkit.FluidCollisionMode>(), any<Boolean>()) } returns null
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
    } finally { unmockkConstructor(WorldMock::class) }
}
