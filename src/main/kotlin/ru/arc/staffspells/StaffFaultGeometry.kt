package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A compact ground rupture: eight heavy rim chunks, sixteen rising basalt wedges,
 * then twelve deterministic fragments that travel outward on short arcs.
 * Slot order and materials stay fixed for the whole scene so packet displays can
 * interpolate instead of replacing entities as the eruption moves.
 */
internal fun staffFaultDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    radius: Double,
    secondary: Boolean,
): List<StaffDisplayPart> {
    val reach = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.8, 8.0) ?: 2.0
    val duration = durationTicks.coerceAtLeast(1).toDouble()
    val age = ageTicks.coerceAtLeast(0).toDouble()
    val emergence = faultSmooth(age / 7.0)
    val settle = faultSmooth((age - duration * 0.60) / (duration * 0.30))
    val expansion = faultSmooth(age / 11.0)
    val mass = 0.88 + reach * 0.075
    val secondaryLift = if (secondary) 0.28 else 0.0
    val parts = ArrayList<StaffDisplayPart>(36)

    repeat(8) { index ->
        val angle = index * PI / 4.0 + sin(index * 1.7) * 0.09
        val ring = reach * (0.16 + (index % 3) * 0.035)
        val width = (0.50 + (index % 3) * 0.055) * mass
        val height = (0.66 + (index % 4) * 0.075 + emergence * 0.16) * mass
        val depth = (0.52 + (index % 3) * 0.045) * mass
        val material = when (index) {
            0, 5 -> Material.BASALT
            1, 7 -> Material.OBSIDIAN
            2 -> Material.BLACKSTONE
            3 -> Material.CRYING_OBSIDIAN
            4 -> Material.POLISHED_BASALT
            else -> Material.AMETHYST_BLOCK
        }
        parts += faultPart(material, cos(angle) * ring, height * 0.5, sin(angle) * ring,
            width, height, depth, yaw = angle, pitch = 0.0, roll = sin(index * 1.3) * 0.08)
    }

    repeat(16) { index ->
        val angle = index * PI / 8.0 + sin(index * 2.11) * 0.12
        val startRadius = reach * (0.10 + (index % 3) * 0.025)
        val endRadius = reach * (0.34 + (index % 4) * 0.045)
        val radial = startRadius + (endRadius - startRadius) * expansion
        val height = (0.22 + (1.22 + (index % 4) * 0.12 + secondaryLift) *
            emergence * (1.0 - 0.22 * settle))
        val width = (0.42 + (index % 4) * 0.055) * mass
        val tall = (1.02 + (index % 5) * 0.105 + if (secondary) 0.12 else 0.0) * mass
        val depth = (0.42 + (index % 3) * 0.05) * mass
        val tilt = 0.12 + emergence * 0.18 + settle * 0.22 + sin(index * 1.37) * 0.045
        val sign = if (index % 2 == 0) 1.0 else -1.0
        val material = when (index % 8) {
            0, 5 -> Material.BASALT
            1, 4, 7 -> Material.BLACKSTONE
            2 -> Material.OBSIDIAN
            3 -> Material.POLISHED_BASALT
            6 -> Material.AMETHYST_BLOCK
            else -> Material.CRYING_OBSIDIAN
        }
        parts += faultPart(material, cos(angle) * radial, tall * 0.5 + height * 0.62,
            sin(angle) * radial, width, tall, depth,
            yaw = angle, pitch = sign * tilt, roll = -sign * (0.08 + settle * 0.12))
    }

    repeat(12) { index ->
        val flightTicks = duration * (0.62 + (index % 4) * 0.055)
        val flight = (age / flightTicks).coerceIn(0.0, 1.0)
        val arc = 4.0 * flight * (1.0 - flight)
        val sign = if (index % 2 == 0) 1.0 else -1.0
        val angle = index * PI / 6.0 + sign * 0.14 * sin(PI * flight)
        val startRadius = reach * 0.14
        val endRadius = reach * (0.52 + (index % 3) * 0.04)
        val radial = startRadius + (endRadius - startRadius) * faultSmooth(flight)
        val height = 0.48 + (1.15 + secondaryLift * 1.4 + (index % 3) * 0.10) * arc
        val width = (0.38 + (index % 3) * 0.055) * mass
        val tall = (0.46 + (index % 4) * 0.045) * mass
        val depth = (0.42 + (index % 3) * 0.06) * mass
        val material = when (index % 6) {
            0 -> Material.AMETHYST_BLOCK
            1, 5 -> Material.BASALT
            2 -> Material.POLISHED_BASALT
            3 -> Material.DEEPSLATE
            else -> Material.BLACKSTONE
        }
        parts += faultPart(material, cos(angle) * radial, height, sin(angle) * radial,
            width, tall, depth, yaw = angle + flight * sign * 0.28,
            pitch = sign * (0.22 + flight * 0.30), roll = -sign * (0.18 + flight * 0.12))
    }
    return parts
}

private fun faultPart(
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    depth: Double,
    yaw: Double,
    pitch: Double,
    roll: Double,
) = StaffDisplayPart(material,
    Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
    Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
    Quaternionf().rotateY(yaw.toFloat()).rotateX(pitch.toFloat()).rotateZ(roll.toFloat()))

private fun faultSmooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
