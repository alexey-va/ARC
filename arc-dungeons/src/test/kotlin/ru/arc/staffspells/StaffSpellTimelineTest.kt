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

    "grove root cage grows before the brambles and pollen rim close" {
        fun cage(age: Int) = staffArsenalParts(StaffSpell.NOVA, age, 48, 0.0, 3.0,
            false, true, StaffCastInput.SECONDARY, 1.0)
        fun volume(age: Int) = cage(age).sumOf { (it.scale.x * it.scale.y * it.scale.z).toDouble() }
        fun outerRadius(age: Int) = cage(age).maxOf { hypot(it.center.x.toDouble(), it.center.z.toDouble()) }
        fun transformedRadius(age: Int) = cage(age).maxOf { part ->
            listOf(-0.5f, 0.5f).maxOf { x -> listOf(-0.5f, 0.5f).maxOf { y ->
                listOf(-0.5f, 0.5f).maxOf { z ->
                    val corner = part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                        .add(part.center)
                    hypot(corner.x.toDouble(), corner.z.toDouble())
                }
            } }
        }
        val start = cage(0)
        val rise = cage(8)
        val closed = cage(20)
        start.map { it.identity } shouldBe rise.map { it.identity }
        rise.map { it.identity } shouldBe closed.map { it.identity }
        rise.size shouldBe 36
        rise.mapNotNull { it.identity }.distinct().size shouldBe 36
        rise.count { it.identity?.startsWith("garden:root:") == true } shouldBe 20
        rise.count { it.identity?.startsWith("garden:hook:") == true } shouldBe 5
        rise.count { it.identity?.startsWith("garden:tie:") == true } shouldBe 5
        (volume(0) < volume(8)) shouldBe true
        (outerRadius(20) < outerRadius(8)) shouldBe true
        (transformedRadius(8) <= 3.0) shouldBe true
        (volume(44) < volume(20)) shouldBe true
        fun pollenRadius(age: Int) = staffArsenalParticles(StaffSpell.NOVA, age, 48, 3.0, true, 1.0)
            .maxOf { hypot(it.offset.x, it.offset.z) }
        (pollenRadius(20) < pollenRadius(8)) shouldBe true
        (staffArsenalParticles(StaffSpell.NOVA, 20, 48, 3.0, true, 1.0).size in 32..48) shouldBe true
    }

    "lunar crescents leave a stable afterimage and shatter inside the tick sixteen damage footprint" {
        fun frame(age: Int, secondary: Boolean, charge: Double): List<StaffDisplayPart> {
            val radius = 2.2 + charge
            return staffArsenalParts(StaffSpell.LUNAR, age, 40, 0.0, radius,
                false, secondary, StaffCastInput.of(secondary, false), charge)
        }
        fun radialBounds(parts: List<StaffDisplayPart>) = parts.maxOf { part ->
            listOf(-0.5f, 0.5f).maxOf { x -> listOf(-0.5f, 0.5f).maxOf { y ->
                listOf(-0.5f, 0.5f).maxOf { z ->
                    val corner = part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                        .add(part.center)
                    hypot(corner.x.toDouble(), corner.z.toDouble())
                }
            } }
        }

        listOf(0.0, 1.0).forEach { charge ->
            listOf(false, true).forEach { secondary ->
                val start = frame(0, secondary, charge)
                val sweep = frame(8, secondary, charge)
                val contact = frame(16, secondary, charge)
                val settle = frame(24, secondary, charge)
                val ids = contact.map { it.identity }
                contact.size shouldBe 48
                start.map { it.identity } shouldBe ids
                sweep.map { it.identity } shouldBe ids
                settle.map { it.identity } shouldBe ids
                contact.mapNotNull { it.identity }.distinct().size shouldBe 48
                contact.count { it.identity?.startsWith("moon:shard:") == true } shouldBe 14
                (radialBounds(contact) <= 2.2 + charge + .7 + .04) shouldBe true

                if (secondary) {
                    val oldSweepDepth = maxOf(
                        kotlin.math.abs(sweep.single { it.identity == "moon:0:8" }.center.z),
                        kotlin.math.abs(sweep.single { it.identity == "moon:1:8" }.center.z),
                    )
                    val contactDepth = maxOf(
                        kotlin.math.abs(contact.single { it.identity == "moon:0:8" }.center.z),
                        kotlin.math.abs(contact.single { it.identity == "moon:1:8" }.center.z),
                    )
                    (oldSweepDepth > 0.5f && contactDepth < .01f) shouldBe true
                } else {
                    val echoesAtContact = contact.filter { it.identity?.startsWith("moon:echo:") == true }
                    val echoesAfterSettle = settle.filter { it.identity?.startsWith("moon:echo:") == true }
                    (echoesAtContact.maxOf { it.scale.x } > echoesAfterSettle.maxOf { it.scale.x }) shouldBe true
                }
                val shards = staffArsenalParticles(StaffSpell.LUNAR, 16, 40,
                    2.2 + charge, secondary, charge)
                (shards.size in 30..48) shouldBe true
                (shards.maxOf { hypot(it.offset.x, it.offset.z) } <= 2.9 + charge + .04) shouldBe true
            }
        }
    }

    "physical arsenal sequences retain identities and shrink before cleanup" {
        listOf(StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.PRISM).forEach { spell ->
            fun frame(age: Int) = staffArsenalParts(spell, age, 40, 8.0, 3.0,
                false, false, StaffCastInput.PRIMARY)
            val steady = frame(20)
            val dissolve = frame(36)
            val expired = frame(38)
            (steady.size in 1..48) shouldBe true
            dissolve.map { it.identity } shouldBe steady.map { it.identity }
            expired.map { it.identity } shouldBe steady.map { it.identity }
            (dissolve.maxOf { it.scale.x } < steady.maxOf { it.scale.x }) shouldBe true
            (expired.maxOf { it.scale.x } <= 0.001f) shouldBe true
        }
    }
})
