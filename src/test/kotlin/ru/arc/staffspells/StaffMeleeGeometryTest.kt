package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Vector3f
import kotlin.math.abs

private data class MeleeTestBounds(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
)

private fun meleeTestBounds(parts: List<StaffDisplayPart>): MeleeTestBounds {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (part in parts) {
        for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
            val corner = Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z)
            part.rotation.transform(corner).add(part.center)
            minX = minOf(minX, corner.x)
            maxX = maxOf(maxX, corner.x)
            minZ = minOf(minZ, corner.z)
            maxZ = maxOf(maxZ, corner.z)
        }
    }
    return MeleeTestBounds(minX, maxX, minZ, maxZ)
}

private fun meleePartsAreFinite(parts: List<StaffDisplayPart>) = parts.all { part ->
    listOf(part.center.x, part.center.y, part.center.z,
        part.scale.x, part.scale.y, part.scale.z,
        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
}

private fun meleeSegmentEndpoint(part: StaffDisplayPart, positiveZ: Boolean): Vector3f {
    val local = Vector3f(0f, 0f, part.scale.z * if (positiveZ) 0.5f else -0.5f)
    return part.rotation.transform(local).add(part.center)
}

class StaffMeleeGeometryTest : FreeSpec({
    val meleeSpells = listOf(
        StaffSpell.MARK, StaffSpell.FROST, StaffSpell.NOVA, StaffSpell.LUNAR,
        StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.PRISM, StaffSpell.OSSUARY,
    )

    "primary close attacks move into the supplied forward reach, keep camera clearance, and fade on one timeline" {
        meleeSpells.forEach { spell ->
            val reach = if (spell == StaffSpell.PRISM || spell == StaffSpell.OSSUARY) 5.0 else 4.5
            val frames = (0 until STAFF_MELEE_FADE_TICK).map { age ->
                staffMeleeDisplayParts(spell, age, STAFF_MELEE_FADE_TICK, reach)
            }
            val originalIds = frames.first().map { it.identity }

            frames.forEach { parts ->
                (parts.isNotEmpty() && parts.size <= 32) shouldBe true
                meleePartsAreFinite(parts) shouldBe true
                parts.all { it.identity != null } shouldBe true
                parts.map { it.identity }.distinct().size shouldBe parts.size
                parts.map { it.identity } shouldBe originalIds
                val bounds = meleeTestBounds(parts)
                (bounds.minZ >= 1.299f) shouldBe true
                (bounds.maxZ <= reach + 0.001f) shouldBe true
            }

            val startingBounds = meleeTestBounds(frames.first())
            (startingBounds.minZ in 1.299f..1.301f) shouldBe true
            val contactBounds = meleeTestBounds(frames[STAFF_MELEE_DISPLAY_ARRIVAL_TICK])
            (abs(contactBounds.maxZ - reach.toFloat()) < 0.001f) shouldBe true
            (contactBounds.maxZ > startingBounds.maxZ + 0.5f) shouldBe true
            (frames.last().maxOf { it.scale.length() } < frames[5].maxOf { it.scale.length() }) shouldBe true
            (frames[8].maxOf { it.scale.length() } < 0.003f) shouldBe true
            staffMeleeDisplayParts(spell, STAFF_MELEE_FADE_TICK, STAFF_MELEE_FADE_TICK, reach) shouldBe emptyList()
        }

        staffMeleeDisplayParts(StaffSpell.LANCE, 0, 10, 4.5) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.MARK, -1, 10, 4.5) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.PRISM, 0, 10, Double.NaN) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.OSSUARY, 0, 10, 1.3) shouldBe emptyList()
    }

    "each theme keeps the requested silhouette and the straight crystal and bone thrusts stay narrow" {
        val mark = staffMeleeDisplayParts(StaffSpell.MARK, 2, 10, 4.5)
        mark.count { it.identity?.startsWith("mark:claw:") == true } shouldBe 6
        mark.count { it.material == Material.CRYING_OBSIDIAN } shouldBe 3

        val frost = staffMeleeDisplayParts(StaffSpell.FROST, 2, 10, 4.5)
        frost.size shouldBe 7
        (meleeTestBounds(frost).maxX - meleeTestBounds(frost).minX > 1.4f) shouldBe true
        frost.all { it.material == Material.BLUE_ICE || it.material == Material.PACKED_ICE } shouldBe true

        val nova = staffMeleeDisplayParts(StaffSpell.NOVA, 2, 10, 4.5)
        nova.size shouldBe 12
        nova.any { it.material == Material.FLOWERING_AZALEA_LEAVES } shouldBe true
        val leftRoot = meleeSegmentEndpoint(nova.single { it.identity == "nova:whip:0:0" }, positiveZ = false)
        val rightRoot = meleeSegmentEndpoint(nova.single { it.identity == "nova:whip:1:0" }, positiveZ = false)
        val leftTip = meleeSegmentEndpoint(nova.single { it.identity == "nova:whip:0:3" }, positiveZ = true)
        val rightTip = meleeSegmentEndpoint(nova.single { it.identity == "nova:whip:1:3" }, positiveZ = true)
        (leftRoot.x < rightRoot.x && leftTip.x > rightTip.x) shouldBe true

        val lunar = staffMeleeDisplayParts(StaffSpell.LUNAR, 2, 10, 4.5)
        val bladeStart = lunar.single { it.identity == "lunar:blade:0" }.center
        val bladeEnd = lunar.single { it.identity == "lunar:blade:6" }.center
        (bladeEnd.x > bladeStart.x && bladeEnd.y > bladeStart.y) shouldBe true
        lunar.single { it.identity == "lunar:hilt" }.material shouldBe Material.IRON_BLOCK

        val gale = staffMeleeDisplayParts(StaffSpell.GALE, 2, 10, 4.5)
        gale.size shouldBe 10
        gale.all { it.material in setOf(Material.WHITE_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS) } shouldBe true

        val tide = staffMeleeDisplayParts(StaffSpell.TIDE, 2, 10, 4.5)
        val water = tide.filter { it.identity?.startsWith("tide:water:") == true }
        val foam = tide.filter { it.identity?.startsWith("tide:foam:") == true }
        water.size shouldBe 7
        foam.size shouldBe 7
        water.all { it.scale.x > 0.6f } shouldBe true
        foam.all { it.scale.x < 0.2f } shouldBe true

        val prism = staffMeleeDisplayParts(StaffSpell.PRISM, 2, 10, 5.0)
        (meleeTestBounds(prism).maxX - meleeTestBounds(prism).minX < 0.85f) shouldBe true
        prism.any { it.material == Material.AMETHYST_BLOCK } shouldBe true

        val ossuary = staffMeleeDisplayParts(StaffSpell.OSSUARY, 2, 10, 5.0)
        ossuary.count { it.identity?.startsWith("ossuary:shaft:") == true } shouldBe 3
        ossuary.count { it.identity?.startsWith("ossuary:tip:") == true } shouldBe 3
        (meleeTestBounds(ossuary).maxX - meleeTestBounds(ossuary).minX < 0.85f) shouldBe true
    }

    "native accents follow the same advancing parts and stop before the display expires" {
        val forbidden = setOf(Particle.SMOKE, Particle.LARGE_SMOKE, Particle.CLOUD,
            Particle.EXPLOSION, Particle.EXPLOSION_EMITTER,
            Particle.GUST, Particle.GUST_EMITTER_LARGE, Particle.GUST_EMITTER_SMALL)

        meleeSpells.forEach { spell ->
            val reach = if (spell == StaffSpell.PRISM || spell == StaffSpell.OSSUARY) 5.0 else 4.5
            listOf(0, STAFF_MELEE_DISPLAY_ARRIVAL_TICK, 4, 6).forEach { age ->
                val parts = staffMeleeDisplayParts(spell, age, STAFF_MELEE_FADE_TICK, reach)
                val samples = staffMeleeParticles(spell, age, STAFF_MELEE_FADE_TICK, reach)
                (samples.size in 1..2) shouldBe true
                samples.all { sample ->
                    val nearestPart = parts.minOf { part ->
                        sample.offset.distance(Vector(part.center.x.toDouble(), part.center.y.toDouble(), part.center.z.toDouble()))
                    }
                    sample.particle !in forbidden && sample.count == 1 &&
                        listOf(sample.offset.x, sample.offset.y, sample.offset.z,
                            sample.spread.x, sample.spread.y, sample.spread.z, sample.speed).all(Double::isFinite) &&
                        sample.spread.length() <= 0.03 && nearestPart < 0.001
                } shouldBe true
            }
            staffMeleeParticles(spell, 8, STAFF_MELEE_FADE_TICK, reach) shouldBe emptyList()
        }
        staffMeleeParticles(StaffSpell.CHAIN, 2, STAFF_MELEE_FADE_TICK, 4.5) shouldBe emptyList()
    }
})
