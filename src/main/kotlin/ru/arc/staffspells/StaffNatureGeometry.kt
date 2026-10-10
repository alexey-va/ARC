package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Grounded, staged crystal fans; each row grows in place as the shared front reaches it. */
internal fun staffFrostDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
): List<StaffDisplayPart> {
    val reach = natureExtent(length, 14.0, 7.0)
    val halfAngle = atan2(natureExtent(radius, 96.0, 8.3), reach)
    val front = staffWaveFront(ageTicks, reach, 12)
    val parts = ArrayList<StaffDisplayPart>(40)
    val heightProfile = doubleArrayOf(0.72, 1.04, 0.78, 1.28, 0.86, 1.12, 0.68, 0.94)

    repeat(5) { row ->
        val distance = reach * (row + 0.65) / 5.0
        val local = (front - distance) * 12.0 / reach
        val grow = natureSmooth((local + 1.5) / 3.0)
        val melt = 1.0 - natureSmooth((local - 10.0) / 6.0)
        val stage = grow * melt

        repeat(8) { slot ->
            val across = (slot / 3.5 - 1.0).coerceIn(-1.0, 1.0)
            val angle = across * halfAngle * 0.96 + if ((row + slot) % 2 == 0) 0.018 else -0.018
            val stagger = if (slot % 2 == 0) 0.08 else -0.04
            val shardDistance = distance + stagger
            val height = (0.66 + row * 0.075) * heightProfile[slot] * stage
            val width = (0.30 + (slot % 3) * 0.055 + row * 0.012) * (0.72 + 0.28 * grow)
            val depth = 0.38 + (slot % 4) * 0.055 + row * 0.025
            val material = when {
                slot == 3 || slot == 4 -> Material.PACKED_ICE
                (row * 3 + slot) % 4 == 0 -> Material.LIGHT_BLUE_STAINED_GLASS
                else -> Material.BLUE_ICE
            }
            parts += naturePart(
                material,
                sin(angle) * shardDistance,
                height * 0.5,
                cos(angle) * shardDistance,
                width,
                height.coerceAtLeast(0.001),
                depth,
                yaw = -angle,
            )
        }
    }
    return parts
}

/** Petal facets roll around an expanding radial edge, leaving a low jade spore wake. */
internal fun staffNovaDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double): List<StaffDisplayPart> {
    val reach = natureExtent(radius, 16.0, 8.0).coerceAtLeast(1.8)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val travel = ((front - 1.8) / (reach - 1.8).coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
    val parts = ArrayList<StaffDisplayPart>(42)

    repeat(14) { index ->
        val angle = index * 2.0 * PI / 14.0
        val petalAngle = angle + PI / 14.0
        val yaw = PI / 2.0 - petalAngle
        val curl = 0.18 + 0.11 * abs(sin(index * 1.7)) + travel * 0.10
        val petalRadius = front - 0.10 + sin(index * 1.7) * 0.06
        val petalSize = 0.90 + 0.13 * cos(index * 1.3)
        parts += naturePart(
            Material.LIME_STAINED_GLASS,
            cos(petalAngle) * petalRadius,
            0.65 + travel * 0.14,
            sin(petalAngle) * petalRadius,
            0.72 * petalSize,
            0.78,
            1.12 * petalSize,
            yaw = yaw,
            pitch = -curl,
            roll = sin(index * 1.7) * 0.10,
        )
        parts += naturePart(
            if (index % 4 == 0) Material.SEA_LANTERN else Material.EMERALD_BLOCK,
            cos(petalAngle) * (front + 0.04),
            0.78 + travel * 0.14,
            sin(petalAngle) * (front + 0.04),
            0.27,
            0.26,
            0.74,
            yaw = yaw,
            pitch = -curl * 0.7,
        )
        val wakeRadius = (front - 0.48 - (index % 3) * 0.06).coerceAtLeast(1.35)
        parts += naturePart(
            Material.MOSS_BLOCK,
            cos(petalAngle) * wakeRadius,
            0.17 + travel * 0.06,
            sin(petalAngle) * wakeRadius,
            0.25,
            0.16,
            0.40,
            yaw = yaw,
        )
    }
    return parts
}

/** A directional leaf crest has a curved leading edge, curled jade panels and a broken wake. */
internal fun staffTidalCrestDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
): List<StaffDisplayPart> {
    val reach = natureExtent(length, 48.0, 12.0).coerceAtLeast(1.8)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val halfWidth = natureExtent(radius, 10.0, 5.5) * front / reach
    val travel = ((front - 1.8) / (reach - 1.8).coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
    val parts = ArrayList<StaffDisplayPart>(39)

    repeat(13) { index ->
        val across = index / 6.0 - 1.0
        val x = across * halfWidth
        val z = front - across * across * 0.78
        val width = (halfWidth / 6.0 * 0.96 + 0.14).coerceAtLeast(0.20)
        val slope = -2.0 * 0.78 * across / halfWidth.coerceAtLeast(0.001)
        val yaw = -atan2(slope, 1.0)
        val curl = 0.16 + abs(across) * 0.08 + 0.08 * sin(index * 1.25)
        val leafHeight = 0.85 + (1.0 - abs(across)) * 0.55 + 0.12 * sin(index * 1.8)
        val y = leafHeight * 0.60 + travel * 0.08

        parts += naturePart(
            Material.LIME_STAINED_GLASS,
            x,
            y,
            z,
            width * 1.08,
            leafHeight,
            0.82,
            yaw = yaw,
            pitch = -curl,
            roll = across * 0.08,
        )
        parts += naturePart(
            if (index % 4 == 0) Material.SEA_LANTERN else Material.EMERALD_BLOCK,
            x,
            y + leafHeight * 0.30,
            z + 0.10,
            width * 0.35,
            0.20,
            0.58,
            yaw = yaw,
            pitch = -curl * 0.65,
        )
        parts += naturePart(
            Material.MOSS_BLOCK,
            x * 0.96,
            0.19 + travel * 0.04,
            z - 0.50 - abs(across) * 0.10,
            width * 0.65,
            0.15,
            0.38,
            yaw = yaw,
        )
    }
    return parts
}

private fun natureSmooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private fun natureExtent(value: Double, max: Double, fallback: Double) =
    value.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.1, max) ?: fallback

private fun naturePart(
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    sx: Double,
    sy: Double,
    sz: Double,
    yaw: Double = 0.0,
    pitch: Double = 0.0,
    roll: Double = 0.0,
) = StaffDisplayPart(
    material,
    Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
    Vector3f(sx.toFloat(), sy.toFloat(), sz.toFloat()),
    Quaternionf().rotateY(yaw.toFloat()).rotateX(pitch.toFloat()).rotateZ(roll.toFloat()),
)

