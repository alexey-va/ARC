package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal fun staffIcicleStep(age: Int): Double = minOf(2.4, 0.55 + age.coerceAtLeast(0) * 0.18)
/** A visible upper arc in camera space; its open middle leaves the target unobstructed. */
internal fun staffIcicleAnchorOffset(index: Int, count: Int = 7): Vector3f {
    val angle = if (count <= 1) PI / 2 else PI * (0.12 + 0.76 * index.coerceIn(0, count - 1) / (count - 1))
    return Vector3f((cos(angle) * 2.15).toFloat(), (0.20 + sin(angle)).toFloat(), 3.25f)
}
internal fun staffIcicleLaunchDelay(index: Int): Int = 4 + index * 2

/** One crown crystal: its eight stable pieces assemble, fly together, then fracture. */
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
                Quaternionf().rotateY(angle.toFloat()).rotateX((release * 1.2).toFloat()),
                identity = "icicle:$index")
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
                    .rotateY((cos(angle) * 0.20).toFloat()).rotateX((sin(angle) * 0.20).toFloat()),
                identity = "icicle:$index")
        }
    }
}

/** Charge places the shared Ember body on the aim line; flight uses the same body at the origin. */
internal fun staffEmberChargeOffset(): Vector3f = Vector3f(0f, -0.88f, 2.9f)

/** The count stays seven until each of the four progressive slots is earned. */
internal fun staffIcicleCount(charge: Double): Int {
    val amount = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return 7 + floor(4.0 * amount).toInt()
}

private val staffIcicleChargeAnchorOrder = intArrayOf(0, 2, 4, 5, 6, 8, 10, 1, 3, 7, 9)

/** Seven symmetric starter positions, then the four positions that fill their gaps. */
internal fun staffIcicleChargeAnchorOffset(index: Int): Vector3f {
    require(index in 0 until 11)
    return staffIcicleAnchorOffset(staffIcicleChargeAnchorOrder[index], 11)
}

/** One local eight-piece Icicle body, progressively scaled into its charge slot. */
internal fun staffIcicleChargeParts(progress: Double, index: Int): List<StaffDisplayPart> {
    require(index in 0 until 11)
    val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val growth = if (index < 7) 1.0 else
        smoothCharge((charge - (index - 7) / 4.0) / 0.25)
    val scaleFactor = growth.toFloat()
    return staffIcicleDisplayParts(ageTicks = 8, impact = false, charge = charge).mapIndexed { piece, part ->
        part.copy(
            center = Vector3f(part.center).mul(scaleFactor),
            scale = Vector3f(
                maxOf(part.scale.x * scaleFactor, 0.001f),
                maxOf(part.scale.y * scaleFactor, 0.001f),
                maxOf(part.scale.z * scaleFactor, 0.001f),
            ),
            identity = "icicle:$piece",
        )
    }
}

/** Only primary ICICLE and EMBER have charged geometry; other spells cast immediately. */
@Suppress("UNUSED_PARAMETER")
internal fun staffChargeParts(spell: StaffSpell, progress: Double, age: Int): List<StaffDisplayPart> {
    val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return when (spell) {
        StaffSpell.EMBER -> staffEmberBodyParts(charge, staffEmberChargeOffset())
        StaffSpell.ICICLE -> (0 until 11).flatMap { index ->
            val anchor = staffIcicleChargeAnchorOffset(index)
            staffIcicleChargeParts(charge, index).mapIndexed { piece, part ->
                part.copy(
                    center = Vector3f(part.center).add(anchor),
                    identity = "icicle-charge:$index:$piece",
                )
            }
        }
        else -> emptyList()
    }
}

private fun smoothCharge(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
