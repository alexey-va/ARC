package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.hypot

private val ossuaryCastId = UUID.fromString("c63cb6d2-8030-4e93-9ac7-71cfdc57a38c")
private val ossuaryRecastId = UUID.fromString("334e032b-92e2-4751-9301-1caf2a631eda")
private val ossuaryShapes = (0 until 11).map { staffOssuaryShape(ossuaryCastId, it) } +
    staffOssuaryShape(ossuaryRecastId)

private fun ossuaryPartsFinite(parts: List<StaffDisplayPart>) = parts.all { part ->
    listOf(part.center.x, part.center.y, part.center.z,
        part.scale.x, part.scale.y, part.scale.z,
        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f && part.identity != null
}

private fun ossuaryMaxRadius(parts: List<StaffDisplayPart>): Double = parts.maxOfOrNull { part ->
    listOf(-0.5f, 0.5f).maxOf { x ->
        listOf(-0.5f, 0.5f).maxOf { y ->
            listOf(-0.5f, 0.5f).maxOf { z ->
                val corner = part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                    .add(part.center)
                hypot(corner.x.toDouble(), corner.z.toDouble())
            }
        }
    }
} ?: 0.0

class StaffOssuaryGeometryTest : FreeSpec({
    "every node and recast has its own reproducible skeleton rather than a rotated template" {
        val shapes = (0 until 11).map { staffOssuaryShape(ossuaryCastId, it) }
        shapes shouldBe (0 until 11).map { staffOssuaryShape(ossuaryCastId, it) }
        shapes.distinct().size shouldBe 11
        shapes.map { it.ribs.size }.toSet() shouldBe setOf(4, 5)
        (shapes.map { shape -> shape.ribs.count { it.fork.isNotEmpty() } }.toSet().size > 1) shouldBe true
        // Link lengths, widths and topology are invariant under a template's rotation.
        val proportions = shapes.map { shape ->
            staffOssuaryRibParts(8, 22, 3.4, false, shape = shape).map { it.identity to it.scale }
        }
        proportions.distinct().size shouldBe 11
        shapes.forEachIndexed { index, shape ->
            (shape != staffOssuaryShape(ossuaryRecastId, index)) shouldBe true
            val settled = staffOssuaryRibParts(6, 22, 3.4, false, shape = shape)
            for (age in listOf(8, 10, 12, 14)) {
                staffOssuaryRibParts(age, 22, 3.4, false, shape = shape) shouldBe settled
            }
            shape shouldBe staffOssuaryShape(ossuaryCastId, index)
        }
    }

    "primary ossuary nodes rise as distinct curved skeletons inside the strike radius" {
        val radiusByCharge = mapOf(0.0 to 3.4, 1.0 to 3.75)
        radiusByCharge.forEach { (charge, radius) -> ossuaryShapes.forEach { shape ->
            val frames = listOf(0, 2, OSSUARY_PRIMARY_RISE_TICKS, 6, 8).map { age ->
                staffOssuaryRibParts(age, 22, radius, false, charge, shape)
            }
            val ribs = shape.ribs.size
            val forks = shape.ribs.count { it.fork.isNotEmpty() }
            val count = ribs * 4 + forks * 2
            val expectedIds = frames.first().map { it.identity }
            frames.forEach { parts ->
                parts.size shouldBe count
                (parts.size in 20..30) shouldBe true
                parts.map { it.identity } shouldBe expectedIds
                parts.mapNotNull { it.identity }.distinct().size shouldBe count
                ossuaryPartsFinite(parts) shouldBe true
                (ossuaryMaxRadius(parts) <= radius + 0.04) shouldBe true
                parts.count { it.material == Material.BONE_BLOCK } shouldBe ribs * 3 + forks
                parts.count { it.material == Material.CALCITE } shouldBe ribs + forks
            }

            val risen = frames[2]
            risen.count { it.identity!!.contains(":curve:") } shouldBe ribs * 4
            risen.count { it.identity!!.endsWith(":fork") } shouldBe forks
            risen.count { it.identity!!.endsWith(":tine") } shouldBe forks
            (ossuaryMaxRadius(risen) > radius * .72) shouldBe true
            (frames[0].sumOf { (it.scale.x * it.scale.y * it.scale.z).toDouble() } <
                risen.sumOf { (it.scale.x * it.scale.y * it.scale.z).toDouble() }) shouldBe true
            val beforeContact = frames[2].associateBy { it.identity }
            val afterContact = frames[3].associateBy { it.identity }
            (ossuaryMaxRadius(afterContact.values.toList()) >= ossuaryMaxRadius(beforeContact.values.toList()) - .02) shouldBe true
        } }
    }

    "secondary ossuary eruption builds an irregular radial fork field before its tick twelve strike" {
        val radiusByCharge = mapOf(0.0 to 5.5, 1.0 to 6.0)
        radiusByCharge.forEach { (charge, radius) -> ossuaryShapes.forEach { shape ->
            val frames = listOf(0, 6, 8, OSSUARY_SECONDARY_STRIKE_TICK, 16).map { age ->
                staffOssuaryRibParts(age, 36, radius, true, charge, shape)
            }
            val expectedIds = frames.first().map { it.identity }
            frames.forEach { parts ->
                (parts.size in 20..30) shouldBe true
                parts.map { it.identity } shouldBe expectedIds
                parts.mapNotNull { it.identity }.distinct().size shouldBe parts.size
                ossuaryPartsFinite(parts) shouldBe true
                (ossuaryMaxRadius(parts) <= radius + 0.04) shouldBe true
            }
            val strike = frames[3]
            (ossuaryMaxRadius(strike) > radius * .72) shouldBe true
            strike.count { it.identity!!.contains(":curve:") } shouldBe shape.ribs.size * 4
            strike.count { it.identity!!.endsWith(":fork") } shouldBe shape.ribs.count { it.fork.isNotEmpty() }
            strike.count { it.identity!!.endsWith(":tine") } shouldBe shape.ribs.count { it.fork.isNotEmpty() }
            (strike.filter { it.identity!!.endsWith(":curve:3") }.maxOf { it.center.y + it.scale.y / 2 } > 2.0f) shouldBe true
        } }
    }

    "the nearest primary cluster leaves the standing and crouching aim window open through its rise" {
        // Full-length runtime path: launch 2.5 blocks ahead, then eleven evenly spaced nodes to 24.
        val firstNodeZ = (2.5 + (24.0 - 2.5) / 11).toFloat()
        for ((variant, shape) in ossuaryShapes.withIndex())
            for (charge in listOf(0.0, 0.5, 1.0)) for (age in 0..20 step 2) {
            val from = staffOssuaryRibParts(age, 22, 3.4 + charge * .35, false, charge, shape)
            val to = staffOssuaryRibParts(age + 2, 22, 3.4 + charge * .35, false, charge, shape)
                .ifEmpty { from }
            for (blend in listOf(0f, .25f, .5f, .75f)) from.zip(to).forEach { (part, next) ->
                val center = Vector3f(part.center).lerp(next.center, blend).add(0f, 0f, firstNodeZ)
                val scale = Vector3f(part.scale).lerp(next.scale, blend)
                val inverse = Quaternionf(part.rotation).slerp(next.rotation, blend).invert()
                val bounds = BoundingBox(-scale.x / 2.0, -scale.y / 2.0, -scale.z / 2.0,
                    scale.x / 2.0, scale.y / 2.0, scale.z / 2.0)
                for (eyeHeight in listOf(1.27f, 1.62f)) {
                    val origin = inverse.transform(Vector3f(0f, eyeHeight, 0f).sub(center))
                    for (x in listOf(-.04f, 0f, .04f)) for (y in listOf(-.04f, 0f, .04f)) {
                        val ray = inverse.transform(Vector3f(x, y, 1f).normalize())
                        withClue("variant=$variant q=$charge age=$age blend=$blend eye=$eyeHeight ray=($x,$y) part=${part.identity}") {
                            bounds.rayTrace(Vector(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()),
                                Vector(ray.x.toDouble(), ray.y.toDouble(), ray.z.toDouble()), 32.0) shouldBe null
                        }
                    }
                }
            }
        }
    }

    "ossuary particle accents follow the clustered silhouette within each strike footprint" {
        val primary = staffArsenalParticles(StaffSpell.OSSUARY, 6, 22, 2.75, false, 0.0)
        primary.size shouldBe 20
        (primary.maxOf { hypot(it.offset.x, it.offset.z) } < 2.75) shouldBe true
        primary.all { it.particle in setOf(org.bukkit.Particle.DUST_COLOR_TRANSITION, org.bukkit.Particle.SOUL) } shouldBe true

        val secondary = staffArsenalParticles(StaffSpell.OSSUARY, OSSUARY_SECONDARY_STRIKE_TICK,
            36, 5.5, true, 0.0)
        secondary.size shouldBe 20
        (secondary.maxOf { hypot(it.offset.x, it.offset.z) } < 5.5) shouldBe true
        secondary.all { it.particle in setOf(org.bukkit.Particle.DUST_COLOR_TRANSITION, org.bukkit.Particle.SOUL) } shouldBe true
    }
})
