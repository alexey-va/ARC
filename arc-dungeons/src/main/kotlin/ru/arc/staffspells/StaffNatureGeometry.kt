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
    val reach = natureExtent(length, 48.0, 28.0)
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

/** Interlocking thorn cage: roots climb, bend inward, and grip around the target. */
internal fun staffNovaDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    radius: Double,
    charge: Double = 0.0,
): List<StaffDisplayPart> {
    val extent = natureExtent(radius, 16.0, 3.0).coerceAtLeast(1.0)
    val age = ageTicks.coerceAtLeast(0)
    val grow = natureSmooth(age / 8.0).coerceAtLeast(0.025)
    val grip = natureSmooth((age - 12.0) / 8.0)
    val fade = natureSmooth((durationTicks - 2.0 - age) / 8.0).coerceAtLeast(0.015)
    val size = (0.8 + charge.coerceIn(0.0, 1.0) * 0.45) * grow * fade
    val outer = (extent - 0.45).coerceAtLeast(0.35) * (1.0 - grip * 0.34)
    val angles = doubleArrayOf(-2.88, -1.45, -0.20, 1.14, 2.50)
    val parts = ArrayList<StaffDisplayPart>(36)

    fun add(id: String, material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) {
        parts += naturePart(material, x, y, z, sx * size, sy * size, sz * size,
            yaw = yaw, pitch = pitch, roll = roll).copy(identity = id)
    }

    angles.forEachIndexed { root, angle ->
        val sway = sin(root * 1.71 + 0.4)
        repeat(4) { segment ->
            val f = (segment + 0.5) / 4.0
            val radial = outer * (1.0 - f * 0.22 * grip) + sin(f * PI + root) * 0.07
            val y = 0.12 + f * (1.42 + abs(sway) * 0.18) + grip * f * 0.12
            add("garden:root:$root:$segment",
                if (segment == 3) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                cos(angle) * radial, y, sin(angle) * radial,
                0.34, 0.54, 0.40,
                yaw = -angle + sway * 0.08,
                pitch = 0.12 * sway + grip * 0.08,
                roll = 0.22 * cos(angle + segment * 0.3))
        }

        // Each root throws an inward hook. Neighboring hooks cross instead of forming petals.
        val hookRadius = outer * (0.70 - grip * 0.12)
        val hookY = 0.92 + (root % 2) * 0.18 + grip * 0.12
        add("garden:hook:$root", Material.DARK_OAK_WOOD,
            cos(angle) * hookRadius, hookY, sin(angle) * hookRadius,
            0.28, 0.42, 0.92,
            yaw = -angle + 0.42 + sway * 0.13,
            pitch = -0.30 + sway * 0.08,
            roll = cos(angle) * 0.22)
        add("garden:thorn:$root", Material.FLOWERING_AZALEA_LEAVES,
            cos(angle + 0.08) * (hookRadius - 0.16), hookY + 0.12,
            sin(angle + 0.08) * (hookRadius - 0.16),
            0.20, 0.46, 0.28,
            yaw = -angle + 0.82, pitch = -0.42, roll = sway * 0.18)

        // Low cross-ties make the five unequal stems read as a gripping cage, not a flower.
        val next = angles[(root + 1) % angles.size]
        val delta = if (root == angles.lastIndex) next + 2.0 * PI - angle else next - angle
        val mid = angle + delta * 0.5
        val tieRadius = outer * (0.70 - grip * 0.18)
        add("garden:tie:$root", Material.MOSS_BLOCK,
            cos(mid) * tieRadius, 0.66 + (root % 3) * 0.09 + grip * 0.16,
            sin(mid) * tieRadius,
            0.24, 0.34, outer * delta * 0.78,
            yaw = -mid,
            pitch = 0.18 * sway,
            roll = sin(mid) * 0.16)
    }
    add("garden:heart", Material.EMERALD_BLOCK, 0.0, 0.58 + grip * 0.16, 0.0,
        0.36, 0.52, 0.36, yaw = age * 0.018, roll = 0.16)
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

