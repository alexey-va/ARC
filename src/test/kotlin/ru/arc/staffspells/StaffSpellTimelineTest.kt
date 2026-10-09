package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.hypot

class StaffSpellTimelineTest : FreeSpec({
    "chain keeps 47 fixed slots and only draws the route already traveled" {
        fun route(count: Int) = (0 until count).map { index -> Vector3f(0f, 0f, index * 2f) }
        fun visibleIndexes(parts: List<StaffDisplayPart>) = parts.indices.filter { parts[it].visible }

        val muzzle = staffLightningTrailParts(route(1))
        val firstStep = staffLightningTrailParts(route(2))
        val firstBend = staffLightningTrailParts(route(3))
        val secondBend = staffLightningTrailParts(route(5))
        val full = staffLightningTrailParts(route(41))

        muzzle.size shouldBe 47
        visibleIndexes(muzzle) shouldBe listOf(44, 45, 46)
        firstStep.size shouldBe 47
        visibleIndexes(firstStep) shouldBe listOf(0, 44, 45, 46)
        visibleIndexes(firstBend).size shouldBe 5
        visibleIndexes(secondBend) shouldBe listOf(0, 1, 2, 3, 40, 44, 45, 46)
        full.size shouldBe 47
        visibleIndexes(full).size shouldBe 47
        full.map { it.material } shouldBe muzzle.map { it.material }

        val trimmedStart = Vector3f(0f, 0f, -firstStep[0].scale.z / 2f)
        firstStep[0].rotation.transform(trimmedStart).add(firstStep[0].center)
        (abs(trimmedStart.z - 1.45f) < 0.0001f) shouldBe true
        (firstStep[0].center.length() - firstStep[0].scale.length() / 2f >= 1.30f) shouldBe true
        firstStep[44].center.z shouldBe 2f
        firstStep.take(40).filter { it.visible }.all { part ->
            part.center.z + part.scale.z / 2f <= 2.001f
        } shouldBe true

        firstBend.take(2).zip(secondBend.take(2)).forEach { (old, grown) ->
            old.center shouldBe grown.center
            old.scale shouldBe grown.scale
            old.rotation shouldBe grown.rotation
        }
        secondBend[44].center.z shouldBe 8f
        staffLightningTrailParts(route(43)).map { it.center } shouldBe full.map { it.center }
        staffLightningTrailParts(route(4), fade = 0.0).isEmpty() shouldBe true

        (muzzle + firstStep + firstBend + secondBend + full).all { part ->
            listOf(part.center.x, part.center.y, part.center.z,
                part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
        } shouldBe true
    }

    "mark impact starts at the charged shape and keeps its spin clock" {
        fun mark(age: Int, impact: Boolean, animationAge: Int) = staffDisplayParts(
            StaffSpell.MARK, age, if (impact) 20 else 40, 0.0, 4.5, impact,
            animationAgeTicks = animationAge,
        )

        val charged = mark(age = 8, impact = false, animationAge = 30)
        val firstImpact = mark(age = 0, impact = true, animationAge = 30)
        charged.size shouldBe 17
        firstImpact.size shouldBe charged.size
        charged.zip(firstImpact).forEach { (chargePart, impactPart) ->
            chargePart.center shouldBe impactPart.center
            chargePart.scale shouldBe impactPart.scale
            chargePart.rotation shouldBe impactPart.rotation
        }

        val rotated = mark(age = 0, impact = true, animationAge = 32)
        (abs(rotated[0].rotation.y - firstImpact[0].rotation.y) > 0.001f) shouldBe true
        val radii = (0..12 step 2).map { age ->
            mark(age = age, impact = true, animationAge = 30).drop(5).maxOf {
                it.center.length().toDouble()
            }
        }
        radii.zipWithNext().all { (before, after) -> after >= before } shouldBe true
        (mark(age = 18, impact = true, animationAge = 48).maxOf { it.scale.x } < 0.01f) shouldBe true
    }

    "secondary singularity starts fully charged and collapses without reopening" {
        fun mark(age: Int, animationAge: Int) = staffDisplayParts(
            StaffSpell.MARK, age, 20, 0.0, 4.5, impact = true, secondary = true,
            animationAgeTicks = animationAge,
        )
        fun cloudRadius(parts: List<StaffDisplayPart>) = parts.drop(7).maxOf {
            hypot(it.center.x.toDouble(), it.center.z.toDouble())
        }

        val full = mark(age = 0, animationAge = 24)
        val sameCharge = staffDisplayParts(StaffSpell.MARK, 8, 40, 0.0, 4.5,
            impact = false, secondary = true, animationAgeTicks = 24)
        full.size shouldBe 29
        full.zip(sameCharge).forEach { (impactPart, chargePart) ->
            impactPart.center shouldBe chargePart.center
            impactPart.scale shouldBe chargePart.scale
            impactPart.rotation shouldBe chargePart.rotation
        }

        val radii = (0..18 step 2).map { age -> cloudRadius(mark(age, 24)) }
        radii.zipWithNext().all { (before, after) -> after <= before } shouldBe true
        (radii.last() < radii.first() * 0.30) shouldBe true
    }

    "nova keeps moving through a shared ground-anchored dissolve" {
        fun primary(age: Int) = staffDisplayParts(StaffSpell.NOVA, age, 20, 4.0, 8.0, impact = true)
        fun tidal(age: Int) = staffDisplayParts(StaffSpell.NOVA, age, 20, 12.0, 5.5,
            impact = true, secondary = true)
        val radialStart = primary(2)
        val radialMid = primary(12)
        val radialNearEnd = primary(18)
        radialStart.size shouldBe 42
        radialMid.size shouldBe 42
        radialNearEnd.size shouldBe 42
        (radialMid.maxOf { hypot(it.center.x.toDouble(), it.center.z.toDouble()) } >
            radialStart.maxOf { hypot(it.center.x.toDouble(), it.center.z.toDouble()) }) shouldBe true
        (radialNearEnd.maxOf { hypot(it.center.x.toDouble(), it.center.z.toDouble()) } >
            radialMid.maxOf { hypot(it.center.x.toDouble(), it.center.z.toDouble()) }) shouldBe true

        val waveStart = tidal(2)
        val waveMid = tidal(12)
        val waveNearEnd = tidal(18)
        waveStart.size shouldBe 39
        waveMid.size shouldBe 39
        waveNearEnd.size shouldBe 39
        (waveNearEnd.maxOf { it.center.z } > waveMid.maxOf { it.center.z }) shouldBe true
        (waveNearEnd.maxOf { abs(it.center.x) } > waveMid.maxOf { abs(it.center.x) }) shouldBe true

        listOf(radialStart, radialMid, radialNearEnd, waveStart, waveMid, waveNearEnd).flatten().all { part ->
            part.center.y - part.scale.y / 2f >= -0.001f
        } shouldBe true
        (radialNearEnd.maxOf { it.scale.y } < radialMid.maxOf { it.scale.y } * 0.10f) shouldBe true
        (waveNearEnd.maxOf { it.scale.y } < waveMid.maxOf { it.scale.y } * 0.10f) shouldBe true
        (primary(20).maxOf { it.scale.x } < 0.01f) shouldBe true
        (tidal(20).maxOf { it.scale.x } < 0.01f) shouldBe true
        (radialNearEnd + waveNearEnd).all { abs(it.center.y) < 0.20f } shouldBe true
    }
})
