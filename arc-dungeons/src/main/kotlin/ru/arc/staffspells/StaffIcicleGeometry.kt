package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal fun staffIcicleStep(age: Int): Double = minOf(2.4, 0.55 + age.coerceAtLeast(0) * 0.18)

private data class IcicleSlot(val anchor: Vector3f, val rotation: Quaternionf, val size: Float)

// Hand-spaced layers, not a ring: the first seven already surround an open aiming corridor.
// Stable poses keep charging, release and the offline preview on the same physical bodies.
private val icicleSlots = listOf(
    IcicleSlot(Vector3f(-2.30f, 0.15f, 3.50f), Quaternionf().rotateXYZ(-0.06f, 0.10f, 0.18f), 0.93f),
    IcicleSlot(Vector3f(2.00f, 1.03f, 4.05f), Quaternionf().rotateXYZ(0.12f, -0.09f, -0.32f), 0.84f),
    IcicleSlot(Vector3f(-1.12f, 1.70f, 4.30f), Quaternionf().rotateXYZ(0.04f, 0.13f, 0.12f), 1.02f),
    IcicleSlot(Vector3f(3.00f, -0.55f, 4.15f), Quaternionf().rotateXYZ(-0.10f, -0.12f, 0.35f), 0.88f),
    IcicleSlot(Vector3f(0.43f, 1.15f, 3.65f), Quaternionf().rotateXYZ(0.09f, 0.05f, -0.18f), 0.90f),
    IcicleSlot(Vector3f(-1.60f, -0.65f, 3.70f), Quaternionf().rotateXYZ(-0.13f, 0.07f, -0.26f), 0.80f),
    IcicleSlot(Vector3f(1.35f, -0.08f, 3.45f), Quaternionf().rotateXYZ(0.02f, -0.10f, 0.08f), 0.97f),
    IcicleSlot(Vector3f(-2.90f, 1.25f, 4.65f), Quaternionf().rotateXYZ(0.14f, 0.09f, 0.28f), 0.88f),
    IcicleSlot(Vector3f(1.52f, 2.00f, 4.90f), Quaternionf().rotateXYZ(-0.04f, -0.14f, -0.13f), 0.82f),
    IcicleSlot(Vector3f(-1.45f, 0.50f, 4.25f), Quaternionf().rotateXYZ(0.08f, 0.12f, -0.38f), 1.00f),
    IcicleSlot(Vector3f(-3.20f, -0.85f, 4.25f), Quaternionf().rotateXYZ(-0.12f, -0.04f, 0.22f), 0.86f),
)

internal fun staffIcicleAnchorOffset(index: Int, count: Int = 7): Vector3f {
    require(count in 1..icicleSlots.size && index in 0 until count)
    return Vector3f(icicleSlots[index].anchor)
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

/** Additional charge grows four separate satellites without moving the starter cloud. */
internal fun staffIcicleChargeAnchorOffset(index: Int): Vector3f = staffIcicleAnchorOffset(index, 11)

/** One local eight-piece Icicle body, progressively scaled into its charge slot. */
internal fun staffIcicleChargeParts(progress: Double, index: Int): List<StaffDisplayPart> {
    require(index in 0 until 11)
    val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val growth = if (index < 7) 1.0 else
        smoothCharge((charge - (index - 7) / 4.0) / 0.25)
    val slot = icicleSlots[index]
    val scaleFactor = (growth * slot.size).toFloat()
    return staffIcicleDisplayParts(ageTicks = 8, impact = false, charge = charge).mapIndexed { piece, part ->
        part.copy(
            center = slot.rotation.transform(Vector3f(part.center).mul(scaleFactor)),
            scale = Vector3f(
                maxOf(part.scale.x * scaleFactor, 0.001f),
                maxOf(part.scale.y * scaleFactor, 0.001f),
                maxOf(part.scale.z * scaleFactor, 0.001f),
            ),
            rotation = Quaternionf(slot.rotation).mul(part.rotation),
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
