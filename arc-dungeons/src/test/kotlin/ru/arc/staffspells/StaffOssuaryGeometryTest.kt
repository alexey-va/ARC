package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.hypot

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
    "primary ossuary nodes rise as five irregular forked bone clusters inside the strike radius" {
        val radiusByCharge = mapOf(0.0 to 2.75, 1.0 to 3.0)
        radiusByCharge.forEach { (charge, radius) ->
            val frames = listOf(0, 2, OSSUARY_PRIMARY_RISE_TICKS, 6, 8).map { age ->
                staffArsenalParts(StaffSpell.OSSUARY, age, 22, 24.0, radius,
                    false, false, StaffCastInput.PRIMARY, charge)
            }
            val expectedIds = frames.first().map { it.identity }
            frames.forEach { parts ->
                parts.size shouldBe 30
                parts.map { it.identity } shouldBe expectedIds
                parts.mapNotNull { it.identity }.distinct().size shouldBe 30
                ossuaryPartsFinite(parts) shouldBe true
                (ossuaryMaxRadius(parts) <= radius + 0.04) shouldBe true
                parts.count { it.material == Material.BONE_BLOCK } shouldBe 15
                parts.count { it.material == Material.CALCITE } shouldBe 15
            }

            val risen = frames[2]
            risen.count { it.identity!!.endsWith(":spine") } shouldBe 5
            risen.count { it.identity!!.contains(":branch:") } shouldBe 10
            risen.count { it.identity!!.contains(":tine:") } shouldBe 10
            (ossuaryMaxRadius(risen) > radius * .72) shouldBe true
            (frames[0].sumOf { (it.scale.x * it.scale.y * it.scale.z).toDouble() } <
                risen.sumOf { (it.scale.x * it.scale.y * it.scale.z).toDouble() }) shouldBe true
            val beforeContact = frames[2].associateBy { it.identity }
            val afterContact = frames[3].associateBy { it.identity }
            (ossuaryMaxRadius(afterContact.values.toList()) >= ossuaryMaxRadius(beforeContact.values.toList()) - .02) shouldBe true
        }
    }

    "secondary ossuary eruption builds an irregular radial fork field before its tick twelve strike" {
        val radiusByCharge = mapOf(0.0 to 5.5, 1.0 to 6.0)
        radiusByCharge.forEach { (charge, radius) ->
            val frames = listOf(0, 6, 8, OSSUARY_SECONDARY_STRIKE_TICK, 16).map { age ->
                staffArsenalParts(StaffSpell.OSSUARY, age, 36, 0.0, radius,
                    false, true, StaffCastInput.SECONDARY, charge)
            }
            val expectedIds = frames.first().map { it.identity }
            frames.forEach { parts ->
                parts.size shouldBe 30
                parts.map { it.identity } shouldBe expectedIds
                parts.mapNotNull { it.identity }.distinct().size shouldBe 30
                ossuaryPartsFinite(parts) shouldBe true
                (ossuaryMaxRadius(parts) <= radius + 0.04) shouldBe true
            }
            val strike = frames[3]
            (ossuaryMaxRadius(strike) > radius * .72) shouldBe true
            strike.count { it.identity!!.endsWith(":spine") } shouldBe 5
            strike.count { it.identity!!.contains(":branch:") } shouldBe 10
            strike.count { it.identity!!.contains(":tine:") } shouldBe 10
            (strike.filter { it.identity!!.endsWith(":cap") }.maxOf { it.center.y + it.scale.y / 2 } > 2.0f) shouldBe true
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
