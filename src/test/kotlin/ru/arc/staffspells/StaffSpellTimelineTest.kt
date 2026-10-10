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
        (abs(trimmedStart.x) < 0.0001f && abs(trimmedStart.y) < 0.0001f) shouldBe true
        (firstStep[0].scale.x / firstStep[0].center.z < 0.065f) shouldBe true
        (abs(firstStep[44].center.x) > 0.15f) shouldBe true
        (firstStep[0].center.length() - firstStep[0].scale.length() / 2f >= 1.30f) shouldBe true
        firstStep[44].center.z shouldBe 2f
        firstStep.take(40).filter { it.visible }.all { part ->
            val end = Vector3f(0f, 0f, part.scale.z / 2f)
            part.rotation.transform(end).add(part.center)
            end.z <= 2.001f
        } shouldBe true

        firstBend.take(2).zip(secondBend.take(2)).forEach { (old, grown) ->
            old.center shouldBe grown.center
            (grown.scale.x <= old.scale.x) shouldBe true
            grown.scale.z shouldBe old.scale.z
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

        val charged = mark(age = 12, impact = false, animationAge = 30)
        val firstImpact = mark(age = 0, impact = true, animationAge = 30)
        charged.size shouldBe 17
        firstImpact.size shouldBe charged.size
        charged.zip(firstImpact).forEach { (chargePart, impactPart) ->
            chargePart.center shouldBe impactPart.center
            chargePart.scale shouldBe impactPart.scale
            chargePart.rotation shouldBe impactPart.rotation
        }

        val rotated = mark(age = 0, impact = true, animationAge = 32)
        (abs(rotated[0].rotation.z - firstImpact[0].rotation.z) > 0.001f) shouldBe true
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

    "nova bloom front advances before impact-only dissolve" {
        fun bloom(age: Int) = staffDisplayParts(
            StaffSpell.NOVA, age, 12, 0.0, 5.5, impact = true, secondary = true,
            input = StaffCastInput.SECONDARY,
        )
        fun frontRadius(parts: List<StaffDisplayPart>) = parts.take(8).maxOf {
            hypot(it.center.x.toDouble(), it.center.z.toDouble())
        }

        val start = bloom(0)
        val early = bloom(4)
        val preDissolve = bloom(6)
        val dissolveStart = bloom(8)
        val dissolveLater = bloom(10)
        val expired = bloom(12)
        listOf(start, early, preDissolve, dissolveStart, dissolveLater, expired).forEach { it.size shouldBe 32 }

        (frontRadius(start) < frontRadius(early)) shouldBe true
        (frontRadius(early) < frontRadius(preDissolve)) shouldBe true
        (start.maxOf { it.scale.x } == preDissolve.maxOf { it.scale.x }) shouldBe true
        (dissolveStart.maxOf { it.scale.x } < preDissolve.maxOf { it.scale.x }) shouldBe true
        (dissolveLater.maxOf { it.scale.x } < dissolveStart.maxOf { it.scale.x }) shouldBe true
        expired.all { it.scale.x > 0f && it.scale.y > 0f && it.scale.z > 0f } shouldBe true
        (expired.maxOf { it.scale.x } < 0.001f) shouldBe true
    }

    "all arsenal impact geometries keep 32 slots and dissolve only in their final five ticks" {
        val spells = listOf(StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.GALE,
            StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM)
        spells.forEach { spell ->
            fun impact(age: Int) = staffDisplayParts(spell, age, 12, 8.0, 3.0,
                impact = true, input = StaffCastInput.PRIMARY)
            val steady = impact(6)
            val dissolve = impact(9)
            val expired = impact(12)
            steady.size shouldBe 32
            dissolve.size shouldBe 32
            expired.size shouldBe 32
            (impact(0).maxOf { it.scale.x } == steady.maxOf { it.scale.x }) shouldBe true
            (dissolve.maxOf { it.scale.x } < steady.maxOf { it.scale.x }) shouldBe true
            expired.all { it.scale.x > 0f && it.scale.y > 0f && it.scale.z > 0f } shouldBe true
            (expired.maxOf { it.scale.x } < 0.001f) shouldBe true
        }
    }
})
