package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun staffIcicleStep(age: Int): Double = minOf(2.4, 0.55 + age.coerceAtLeast(0) * 0.18)
internal fun staffIcicleAnchorOffset(index: Int, count: Int = 7): Vector3f {
    val angle = if (count <= 1) PI / 2 else PI * (0.12 + 0.76 * index.coerceIn(0, count - 1) / (count - 1))
    return Vector3f((cos(angle) * 2.65).toFloat(), (0.35 + sin(angle) * 2.3).toFloat(), -1.65f)
}
internal fun staffIcicleLaunchDelay(index: Int): Int = 4 + index * 2

/** One shoulder crystal: its eight stable pieces assemble, fly together, then fracture. */
internal fun staffIcicleDisplayParts(ageTicks: Int, impact: Boolean, charge: Double = 0.0): List<StaffDisplayPart> {
    val assembly = (ageTicks / 8.0).coerceIn(0.0, 1.0)
    val chargeAmount = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val chargeBloom = smoothCharge((chargeAmount - 0.08) / 0.84)
    val release = (ageTicks / 8.0).coerceIn(0.0, 1.0)
    return List(8) { index ->
        val angle = index * PI / 4.0
        val material = when (index) {
            0 -> Material.PACKED_ICE
            1 -> Material.SEA_LANTERN
            2, 4, 6 -> Material.BLUE_ICE
            else -> Material.LIGHT_BLUE_STAINED_GLASS
        }
        if (impact) {
            val spread = 0.2 + release * 1.1
            val scale = Vector3f(0.18f, 0.14f, 0.55f).mul((1.0 - release * 0.6).toFloat())
            if (index >= 2) scale.mul((0.42 + 0.58 * chargeBloom).toFloat())
            else scale.y *= (1.0 + 0.28 * chargeBloom).toFloat()
            StaffDisplayPart(material,
                Vector3f((cos(angle) * spread).toFloat(), (sin(angle) * spread).toFloat(),
                    ((index % 3 - 1) * spread * 0.65).toFloat()),
                scale,
                Quaternionf().rotateY(angle.toFloat()).rotateX((release * 1.2).toFloat()))
        } else {
            val spread = if (index < 2) 0.0 else 0.16 + (1.0 - assembly) * 0.26
            val size = (0.3 + assembly * 0.7).toFloat()
            val scale = when (index) {
                0 -> Vector3f(0.32f, 0.32f, 1.35f)
                1 -> Vector3f(0.20f, 0.20f, 0.70f)
                else -> Vector3f(0.18f, 0.22f, 0.76f)
            }.mul(size)
            if (index >= 2) scale.mul((0.42 + 0.58 * chargeBloom).toFloat())
            else scale.y *= (1.0 + 0.28 * chargeBloom).toFloat()
            StaffDisplayPart(material,
                Vector3f((cos(angle) * spread).toFloat(), (sin(angle) * spread).toFloat(),
                    if (index == 1) 0.42f else if (index < 2) -0.18f else -0.48f),
                scale, Quaternionf().rotateZ((angle + PI / 4.0).toFloat())
                    .rotateY((cos(angle) * 0.20).toFloat()).rotateX((sin(angle) * 0.20).toFloat()))
        }
    }
}

/** Shoulder halo grows outside the crosshair; stable slots prevent spawn/despawn flicker. */
internal fun staffChargeParts(spell: StaffSpell, progress: Double, age: Int): List<StaffDisplayPart> {
    if (spell == StaffSpell.INFERNO || spell == StaffSpell.GRAVITY) return emptyList()
    val count = if (spell == StaffSpell.ICICLE) 11 else 7
    val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val bloom = smoothCharge((charge - 0.08) / 0.84)
    val material = when (spell) {
        StaffSpell.CHAIN -> Material.SEA_LANTERN
        StaffSpell.MARK, StaffSpell.REQUIEM -> Material.CRYING_OBSIDIAN
        StaffSpell.FROST, StaffSpell.ICICLE -> Material.BLUE_ICE
        StaffSpell.LANCE -> Material.GOLD_BLOCK
        StaffSpell.EMBER, StaffSpell.INFERNO -> Material.MAGMA_BLOCK
        StaffSpell.NOVA -> Material.EMERALD_BLOCK
        StaffSpell.FAULT, StaffSpell.GRAVITY -> Material.OBSIDIAN
        StaffSpell.LUNAR -> Material.CALCITE
        StaffSpell.GALE -> Material.WHITE_STAINED_GLASS
        StaffSpell.TIDE -> Material.PRISMARINE
        StaffSpell.PRISM -> Material.AMETHYST_BLOCK
        StaffSpell.NECROMANCER, StaffSpell.OSSUARY -> Material.BONE_BLOCK
    }
    return (0 until count).flatMap { index ->
        val growth = smoothCharge((charge * (count - 2) + 2.0 - index).coerceIn(0.0, 1.0))
        val anchor = staffChargeFormationOffset(spell, index, count).mul((1.0 + bloom * 0.12).toFloat())
        anchor.y += sin(age.coerceAtLeast(0) * 0.07 + index).toFloat() * (0.04f + bloom.toFloat() * 0.03f)
        List(3) { piece ->
            val base = if (piece == 1) Vector3f(0.11f, 0.20f,
                if (spell == StaffSpell.ICICLE) 0.70f else 0.30f)
            else Vector3f(0.24f, 0.23f, if (spell == StaffSpell.ICICLE) 0.95f else 0.40f)
            val intensity = if (piece == 1) 0.66 + bloom * 0.42 else 0.56 + bloom * 0.44
            val slotScale = 0.008 + (intensity - 0.008) * growth
            StaffDisplayPart(if (piece == 1) Material.SEA_LANTERN else material,
                Vector3f(anchor).add(0f, 0f, (piece - 1) * 0.15f),
                base.mul(slotScale.toFloat()),
                Quaternionf().rotateZ((piece * PI / 3 + age.coerceAtLeast(0) * 0.015).toFloat()))
        }
    }
}

private fun staffChargeFormationOffset(spell: StaffSpell, index: Int, count: Int): Vector3f {
    val t = if (count <= 1) 0.5 else index.toDouble() / (count - 1)
    val lane = t * 2.0 - 1.0
    val angle = index * 2.0 * PI / count
    return when (spell) {
        StaffSpell.CHAIN -> Vector3f((lane * 2.4).toFloat(), (0.42 + (index % 2) * 0.38).toFloat(),
            (-2.28 + (index % 3) * -0.12).toFloat())
        StaffSpell.MARK -> Vector3f((1.20 * cos(angle * 0.58)).toFloat(), (0.28 + 2.15 * t).toFloat(),
            (-2.30 + 0.20 * sin(angle * 2.0)).toFloat())
        StaffSpell.FROST -> Vector3f((2.35 * cos(angle)).toFloat(), (0.86 + 0.42 * sin(angle)).toFloat(),
            (-2.02 + 0.12 * cos(angle * 2.0)).toFloat())
        StaffSpell.ICICLE -> staffIcicleAnchorOffset(index, count)
        StaffSpell.FAULT -> Vector3f((lane * 2.45).toFloat(), (0.22 + (index % 2) * 0.22).toFloat(),
            (-2.18 - (index % 3) * 0.13).toFloat())
        StaffSpell.LANCE -> Vector3f((lane * 0.70).toFloat(), (0.50 + (1.0 - kotlin.math.abs(lane)) * 0.22).toFloat(),
            (-2.18 - (1.0 - kotlin.math.abs(lane)) * 0.60).toFloat())
        StaffSpell.EMBER -> Vector3f((2.25 * cos(angle)).toFloat(), (1.68 + 0.58 * sin(angle)).toFloat(),
            (-2.08 + 0.16 * sin(angle * 2.0)).toFloat())
        StaffSpell.NOVA -> {
            val ray = if (index % 2 == 0) 2.35 else 2.75
            Vector3f((ray * cos(angle)).toFloat(), (1.22 + ray * 0.42 * sin(angle)).toFloat(),
                (-2.05 + ray * 0.24 * cos(angle * 2.0)).toFloat())
        }
        StaffSpell.LUNAR -> {
            val arc = PI * (0.10 + 0.80 * t)
            Vector3f((2.50 * cos(arc)).toFloat(), (0.78 + 1.08 * sin(arc)).toFloat(), -2.12f)
        }
        StaffSpell.GALE -> Vector3f((2.30 * cos(angle)).toFloat(), (0.18 + 2.10 * t).toFloat(),
            (-1.94 + 0.24 * sin(angle)).toFloat())
        StaffSpell.TIDE -> Vector3f((lane * 2.45).toFloat(), (0.82 + 0.40 * sin(angle)).toFloat(),
            (-2.16 - 0.34 * cos(angle)).toFloat())
        StaffSpell.REQUIEM -> Vector3f((if (index % 2 == 0) -0.62 else 0.62).toFloat(),
            (0.22 + 2.30 * t).toFloat(), (-2.26 - 0.18 * cos(angle)).toFloat())
        StaffSpell.INFERNO, StaffSpell.GRAVITY -> Vector3f() // Held channels own their live geometry, never charge formations.
        StaffSpell.OSSUARY -> Vector3f(((if (index % 2 == 0) -1 else 1) * (1.35 + .7 * sin(t * PI))).toFloat(),
            (.2 + 1.6 * t).toFloat(), -2.45f)
        StaffSpell.NECROMANCER -> Vector3f((2.25 * cos(angle)).toFloat(),
            (-.5 + .38 * sin(angle * 2)).toFloat(), (-2.25 + .35 * sin(angle)).toFloat())
        StaffSpell.PRISM -> {
            val vertex = index % 3
            val layer = index / 3
            when (vertex) {
                0 -> Vector3f(-1.62f, (0.38 + layer * 0.43).toFloat(), -2.10f)
                1 -> Vector3f(1.62f, (0.38 + layer * 0.43).toFloat(), -2.10f)
                else -> Vector3f(0f, (0.78 + layer * 0.43).toFloat(), -2.58f)
            }
        }
    }
}

private fun smoothCharge(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
