package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.abs

private data class MeleeTestBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
)

private fun meleeTestBounds(parts: List<StaffDisplayPart>): MeleeTestBounds {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (part in parts) {
        for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
            val corner = Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z)
            part.rotation.transform(corner).add(part.center)
            minX = minOf(minX, corner.x)
            maxX = maxOf(maxX, corner.x)
            minY = minOf(minY, corner.y)
            maxY = maxOf(maxY, corner.y)
            minZ = minOf(minZ, corner.z)
            maxZ = maxOf(maxZ, corner.z)
        }
    }
    return MeleeTestBounds(minX, maxX, minY, maxY, minZ, maxZ)
}

private fun meleePartsAreFinite(parts: List<StaffDisplayPart>) = parts.all { part ->
    listOf(part.center.x, part.center.y, part.center.z,
        part.scale.x, part.scale.y, part.scale.z,
        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
}

private fun samePose(left: List<StaffDisplayPart>, right: List<StaffDisplayPart>): Boolean =
    left.size == right.size && left.zip(right).all { (a, b) ->
        a.identity == b.identity && a.center.distance(b.center) < 0.0001f &&
            a.scale.distance(b.scale) < 0.0001f &&
            abs(a.rotation.dot(b.rotation)) > 0.99999f
    }

class StaffMeleeGeometryTest : FreeSpec({
    val meleeSpells = listOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR, StaffSpell.TIDE)
    val keyframeAges = (0 until STAFF_MELEE_EFFECT_TICKS step StaffSpellDisplayEffects.FRAME_TICKS).toList()
    val forbiddenParticles = setOf(
        Particle.SMOKE, Particle.LARGE_SMOKE, Particle.CLOUD,
        Particle.EXPLOSION, Particle.EXPLOSION_EMITTER,
        Particle.GUST, Particle.GUST_EMITTER_LARGE, Particle.GUST_EMITTER_SMALL,
    )

    "selected frontal attacks reach their full footprint by keyframe 4 and fade before removal" {
        meleeSpells.forEach { spell ->
            val frames = keyframeAges.map { age ->
                staffMeleeDisplayParts(spell, age, STAFF_MELEE_EFFECT_TICKS, 7.0)
            }
            val identities = frames.first().map { it.identity }

            frames.forEach { parts ->
                (parts.isNotEmpty() && parts.size <= 48) shouldBe true
                meleePartsAreFinite(parts) shouldBe true
                parts.all { it.identity != null } shouldBe true
                parts.map { it.identity }.distinct().size shouldBe parts.size
                parts.map { it.identity } shouldBe identities
                val bounds = meleeTestBounds(parts)
                (bounds.minX >= -5.1f && bounds.maxX <= 5.1f) shouldBe true
                (bounds.minY >= -1.6f && bounds.maxY <= 1.35f) shouldBe true
                (bounds.minZ >= 1.39f && bounds.maxZ <= 7.002f) shouldBe true
            }

            val contact = frames[STAFF_MELEE_PEAK_TICK / StaffSpellDisplayEffects.FRAME_TICKS]
            val landed = frames[STAFF_MELEE_CONTACT_TICK / StaffSpellDisplayEffects.FRAME_TICKS]
            (abs(meleeTestBounds(contact).maxZ - 7.0f) < 0.002f) shouldBe true
            val contactMinZ = meleeTestBounds(contact).minZ
            if (spell == StaffSpell.TIDE || spell == StaffSpell.LUNAR) {
                (contactMinZ >= 1.4f && contactMinZ < 6.0f) shouldBe true
            } else {
                (contactMinZ in 1.7f..2.6f) shouldBe true
            }
            samePose(contact, landed) shouldBe true
            (frames.last().maxOf { it.scale.length() } < 0.01f) shouldBe true
            staffMeleeDisplayParts(spell, STAFF_MELEE_EFFECT_TICKS, STAFF_MELEE_EFFECT_TICKS, 7.0) shouldBe emptyList()
        }

        STAFF_MELEE_PEAK_TICK shouldBe 4
        STAFF_MELEE_CONTACT_TICK shouldBe 6
        STAFF_MELEE_FADE_START_TICK shouldBe 8
        STAFF_MELEE_EFFECT_TICKS shouldBe 16
        staffMeleeDisplayParts(StaffSpell.FROST, 0, 16, Double.NaN) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.FROST, 0, 16, 1.4) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.FROST, -1, 16, 7.0) shouldBe emptyList()
        staffMeleeDisplayParts(StaffSpell.FROST, 0, 0, 7.0) shouldBe emptyList()
    }

    "four primary attacks keep clearly different large topologies" {
        val frost = staffMeleeDisplayParts(StaffSpell.FROST, 4, 16, 7.0)
        val shelves = frost.filter { it.identity?.startsWith("frost:shelf:") == true }
        val shards = frost.filter { it.identity?.startsWith("frost:shard:") == true }
        shelves.size shouldBe 5
        shards.size shouldBe 12
        shards.all { it.material == Material.BLUE_ICE || it.material == Material.PACKED_ICE } shouldBe true
        frost.none { it.material == Material.CYAN_STAINED_GLASS } shouldBe true
        (meleeTestBounds(frost).maxX - meleeTestBounds(frost).minX > 7.0f) shouldBe true

        val fault = staffMeleeDisplayParts(StaffSpell.FAULT, 4, 16, 7.0)
        val faultRows = fault.mapNotNull { part -> part.identity?.substringAfter("fault:row:")?.substringBefore(':') }.toSet()
        faultRows shouldBe setOf("0", "1", "2", "3")
        fault.all { it.material in setOf(Material.BASALT, Material.POLISHED_BASALT, Material.BLACKSTONE, Material.OBSIDIAN) } shouldBe true
        val risingRows = staffMeleeDisplayParts(StaffSpell.FAULT, 2, 16, 7.0).associateBy { it.identity }
        val frontRow = risingRows.getValue("fault:row:0:rock:0")
        val rearRow = risingRows.getValue("fault:row:3:rock:0")
        (frontRow.center.y > rearRow.center.y + 0.10f) shouldBe true

        val lunar = staffMeleeDisplayParts(StaffSpell.LUNAR, 4, 16, 7.0)
            .filter { it.identity?.startsWith("lunar:arc:") == true }
        (lunar.size >= 10) shouldBe true
        lunar.all { it.material in setOf(Material.CALCITE, Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ) } shouldBe true
        val orderedArc = lunar.sortedBy { it.identity!!.substringAfterLast(':').toInt() }
        val left = orderedArc.first().center
        val crown = orderedArc[orderedArc.size / 2].center
        val right = orderedArc.last().center
        (crown.z > maxOf(left.z, right.z) + 0.7f) shouldBe true
        (right.y > left.y + 0.15f) shouldBe true

        val tide = staffMeleeDisplayParts(StaffSpell.TIDE, 4, 16, 7.0)
        val water = tide.filter { it.identity?.startsWith("tide:water:") == true }
        val foam = tide.filter { it.identity?.startsWith("tide:foam:") == true }
        val curl = tide.filter { it.identity?.startsWith("tide:curl:") == true }
        (water.size >= 8 && foam.size >= 6 && curl.size >= 2) shouldBe true
        (water.maxOf { it.scale.y } > foam.maxOf { it.scale.y } * 2.0f) shouldBe true
        (meleeTestBounds(tide).maxX - meleeTestBounds(tide).minX > 7.0f) shouldBe true
    }

    "the central reticle corridor stays below eye height while outer shapes carry the silhouette" {
        meleeSpells.forEach { spell ->
            val parts = staffMeleeDisplayParts(spell, 4, 16, 7.0)
            val centerCorridor = parts.filter { abs(it.center.x) < 0.9f }
            centerCorridor.isNotEmpty() shouldBe true
            val centerTop = centerCorridor.maxOf { meleeTestBounds(listOf(it)).maxY }
            (centerTop < 0.30f) shouldBe true
        }

        StaffSpell.values().filterNot(meleeSpells::contains).forEach { spell ->
            staffMeleeDisplayParts(spell, 4, 16, 7.0) shouldBe emptyList()
            staffMeleeParticles(spell, 4, 16, 7.0) shouldBe emptyList()
        }
    }

    "native accents form a bounded contact burst and never use generic smoke clutter" {
        MockBukkitTestRuntime.open().use {
            meleeSpells.forEach { spell ->
                keyframeAges.forEach { age ->
                    val samples = staffMeleeParticles(spell, age, STAFF_MELEE_EFFECT_TICKS, 7.0)
                    if (age in setOf(2, 4, 6)) {
                        (samples.size in 5..10) shouldBe true
                    } else {
                        samples shouldBe emptyList()
                    }
                    samples.all { sample ->
                        sample.particle !in forbiddenParticles && sample.count in 1..2 &&
                            listOf(sample.offset.x, sample.offset.y, sample.offset.z,
                                sample.spread.x, sample.spread.y, sample.spread.z, sample.speed).all(Double::isFinite) &&
                            sample.spread.length() <= 0.2 &&
                            sample.offset.x in -5.2..5.2 && sample.offset.y in -1.7..1.5 && sample.offset.z in 1.3..7.1 &&
                            (sample.particle != Particle.BLOCK || sample.data is BlockData)
                    } shouldBe true
                }
            }
            staffMeleeParticles(StaffSpell.FROST, 4, 6, 7.0).size shouldBe 7
            staffMeleeParticles(StaffSpell.FROST, 6, 6, 7.0) shouldBe emptyList()
        }
    }
})
