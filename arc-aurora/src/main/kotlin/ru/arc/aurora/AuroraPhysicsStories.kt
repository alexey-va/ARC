package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraPhysicsStories : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private const val FISHING_DEPTH = 0.12
    private const val STEP_START = 0.77
    private const val STEP_DURATION = 0.96
    private const val IMPACT = 1.60
    private const val RELEASE = 2.885

    override fun partCount(shape: AuroraShape) = when (shape) {
        AuroraShape.SKY_FISHING, AuroraShape.FROST_WALKER -> 14
        AuroraShape.SLIME_ANVIL -> 5
        else -> error("Not a physics story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        require(index in 0 until partCount(shape) && t.isFinite())
        val progress = t.coerceIn(0.0, 1.0)
        return when (shape) {
            AuroraShape.SKY_FISHING -> fishing(index, progress * 8.4)
            AuroraShape.FROST_WALKER -> frost(index, progress * 7.8)
            AuroraShape.SLIME_ANVIL -> slime(index, progress * 6.3)
            else -> error("Not a physics story: $shape")
        }
    }

    private fun pulse(t: Double, from: Double, peak: Double, to: Double) =
        storyBeat(t, from, peak) * (1.0 - storyBeat(t, peak, to))

    /** The rotation applies to the mesh and its offset about the physical attachment point. */
    private fun attached(material: Material, pivot: AuroraOffset, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, rotation: Quaternionf, size: Double = 1.0): AuroraMiniaturePart {
        val offset = rotation.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat())).mul(size.toFloat())
        return AuroraMiniaturePart(material, AuroraOffset(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z),
            rotation, Vector3f((sx * size).toFloat(), (sy * size).toFloat(), (sz * size).toFloat()))
    }

    private fun fishing(index: Int, s: Double): AuroraMiniaturePart {
        val hauled = storyBeat(s, 2.65, 3.28) * (1.0 - storyBeat(s, 6.62, 7.94))
        val bite = 0.11 * pulse(s, 1.11, 1.26, 1.46) + 0.16 * pulse(s, 1.65, 1.81, 2.03) +
            0.32 * pulse(s, 2.13, 2.65, 3.23)
        val age = (s - 2.76).coerceAtLeast(0.0)
        val sway = sin(age * 3.06) * exp(-0.64 * age) * hauled
        val bobX = -0.55 + 0.20 * hauled + 0.20 * sway
        val bobY = -0.08 - bite + 0.16 * hauled - 0.020 * sin(TURN * s / 2.1) * (1.0 - hauled) - 0.036 * sway * sway
        if (index < 5) {
            val roll = 0.17 - 0.33 * hauled + 0.235 * bite - 0.035 * sway
            val c = cos(roll)
            val sn = sin(roll)
            val gripX = 1.10
            val gripY = 0.018 + 0.045 * hauled
            // The unheld fishing-rod sprite includes a second hook. This bare vanilla shaft carries the animated line.
            val shaft = 1.02
            val rodX = gripX - shaft * (c * 0.3125 + sn * 0.375)
            val rodY = gripY - shaft * (sn * 0.3125 - c * 0.375)
            if (index == 0) return storyPart(Material.STICK, rodX, rodY, FISHING_DEPTH, shaft, shaft, 1.36, roll)
            if (index == 1) return storyPart(Material.IRON_BLOCK, gripX - c * 0.11, gripY + sn * 0.11 + 0.022,
                FISHING_DEPTH + 0.022, 0.112, 0.080, 0.092, roll - 0.35)
            // Opaque tip of the 16px stick, after the native ItemDisplay Y rotation.
            val tipX = rodX + shaft * (-0.40625 * c - 0.34375 * sn)
            val tipY = rodY + shaft * (-0.40625 * sn + 0.34375 * c)
            val slack = 0.16 * (1.0 - hauled) * (1.0 - (bite / 0.27).coerceIn(0.0, 1.0))
            fun lineX(u: Double) = tipX + (bobX - tipX) * u + 2.0 * u * (1.0 - u) * slack
            fun lineY(u: Double) = tipY + (bobY + 0.13 - tipY) * u - 2.0 * u * (1.0 - u) * slack
            val a = (index - 2) / 3.0
            val b = (index - 1) / 3.0
            val ax = lineX(a); val ay = lineY(a)
            val bx = lineX(b); val by = lineY(b)
            return storyPart(Material.LIGHT_GRAY_CONCRETE, (ax + bx) / 2, (ay + by) / 2, FISHING_DEPTH,
                0.023, hypot(bx - ax, by - ay) + 0.006, 0.023, atan2(ax - bx, by - ay))
        }
        when (index) {
            5 -> return storyPart(Material.WHITE_CONCRETE, bobX, bobY + 0.038, FISHING_DEPTH, 0.156, 0.190, 0.156)
            6 -> return storyPart(Material.RED_CONCRETE, bobX, bobY + 0.038, FISHING_DEPTH, 0.162, 0.067, 0.162)
            7 -> return storyPart(Material.GRAY_CONCRETE, bobX, bobY - 0.109, FISHING_DEPTH, 0.028, 0.116, 0.028)
            8 -> return storyPart(Material.GRAY_CONCRETE, bobX - 0.030, bobY - 0.158, FISHING_DEPTH, 0.083, 0.030, 0.034)
        }
        val caught = storyBeat(s, 2.53, 3.15) * (1.0 - storyBeat(s, 6.80, 7.65))
        val hook = AuroraOffset(bobX - 0.03, bobY - 0.158, FISHING_DEPTH)
        val island = Quaternionf().rotateZ((-0.12 * hauled + 0.28 * sway).toFloat()).rotateY(-0.35f)
        return when (index) {
            9 -> attached(Material.GRASS_BLOCK, hook, -0.235, -0.215, -0.17, 0.63, 0.43, 0.59, island, caught)
            10 -> attached(Material.OAK_LOG, hook, -0.28, 0.15, -0.18, 0.115, 0.30, 0.115, island, caught)
            11 -> attached(Material.OAK_LEAVES, hook, -0.27, 0.36, -0.19, 0.41, 0.20, 0.35, island, caught)
            12 -> attached(Material.OAK_LEAVES, hook, -0.39, 0.35, -0.10, 0.28, 0.18, 0.27, island, caught)
            else -> attached(Material.OAK_LEAVES, hook, -0.28, 0.51, -0.18, 0.32, 0.20, 0.29, island, caught)
        }
    }

    private fun stepX(index: Int) = -1.00 + index * 0.285
    private fun stepY(index: Int) = -0.32 + index * 0.016 + (index % 2) * 0.072
    private fun stepZ(index: Int) = if (index % 2 == 0) 0.14 else -0.14
    private fun leaving(index: Int) = STEP_START + index * STEP_DURATION

    private fun frost(index: Int, s: Double): AuroraMiniaturePart {
        if (index < 8) {
            val leg = index / 4
            val stride = floor((s - leaving(leg)) / (2 * STEP_DURATION)).toInt().coerceIn(0, 2)
            val from = leg + 2 * stride
            val u = ((s - leaving(from)) / STEP_DURATION).coerceIn(0.0, 1.0)
            val reach = storyBeat(u, 0.0, 1.0)
            val sole = AuroraOffset(stepX(from) + (stepX(from + 2) - stepX(from)) * reach,
                stepY(from) + (stepY(from + 2) - stepY(from)) * reach + sin(PI * u) * 0.37, stepZ(leg))
            val boot = Quaternionf().rotateZ((-0.30 * sin(TURN * u)).toFloat()).rotateY(-0.23f)
            val size = if (leg == 0) 1.0 else 0.94
            // The native DIAMOND_BOOTS sprite contains *both* boots. Independent cuboid boots can actually stride.
            return when (index % 4) {
                0 -> attached(Material.DARK_PRISMARINE, sole, 0.0, 0.023, 0.0, 0.24, 0.046, 0.165, boot, size)
                1 -> attached(Material.DIAMOND_BLOCK, sole, 0.0, 0.090, 0.0, 0.236, 0.108, 0.170, boot, size)
                2 -> attached(Material.DIAMOND_BLOCK, sole, -0.056, 0.269, 0.0, 0.116, 0.284, 0.145, boot, size)
                else -> attached(Material.DIAMOND_BLOCK, sole, -0.056, 0.415, 0.0, 0.138, 0.062, 0.161, boot, size)
            }
        }
        val pair = (index - 8) / 2
        val side = if (index % 2 == 0) -1.0 else 1.0
        var tile = pair
        // A slot advances only in its fully hidden interval, after both halves of the old footprint have fallen.
        while (tile + 3 < 8 && s >= leaving(tile + 2) - 0.53) tile += 3
        val landing = leaving(tile - 1)
        val grow = if (tile < 2) 1.0 else storyBeat(s, landing - 0.31, landing - 0.035)
        val age = if (tile < 6) s - leaving(tile) else -1.0
        val fall = ((age - 0.43) / 0.57).coerceIn(0.0, 1.0)
        val visible = grow * (1.0 - storyBeat(fall, 0.50, 1.0))
        val opening = 0.013 * storyBeat(age, 0.11, 0.43) + fall * (0.045 + 0.135 * fall)
        val thickness = 0.09 * visible
        return storyPart(Material.PACKED_ICE, stepX(tile) + side * (0.094 * grow + opening),
            stepY(tile) - thickness / 2 - fall * (0.055 + (if (side < 0) 0.195 else 0.280) * fall),
            stepZ(tile) + side * fall * 0.070, 0.188 * visible, thickness, 0.36 * visible, side * fall * 0.47)
    }

    private fun slimeHeight(s: Double): Double {
        val rest = 0.77 + 0.020 * sin(TURN * s / 6.3)
        return when {
            s < 2.66 -> rest + (0.10 - rest) * storyBeat(s, IMPACT, 1.98)
            s < 3.0 -> 0.10 + 0.84 * storyBeat(s, 2.66, 3.0)
            else -> rest + (0.94 - rest) * exp(-2.8 * (s - 3.0)) * cos(11 * (s - 3.0))
        }
    }

    private fun slimeBottom(s: Double) = -0.60 - 0.056 * storyBeat(s, IMPACT, 1.98) * (1.0 - storyBeat(s, 2.66, 3.0)) -
        0.018 * sin(TURN * s / 6.3)

    private fun slime(index: Int, s: Double): AuroraMiniaturePart {
        val x = -0.20
        val h = slimeHeight(s)
        val top = slimeBottom(s) + h
        if (index == 4) {
            val sy = 0.50
            if (s <= RELEASE) {
                val u = ((s - 0.55) / (IMPACT - 0.55)).coerceIn(0.0, 1.0)
                val impactTop = slimeBottom(IMPACT) + slimeHeight(IMPACT)
                val sole = if (s < IMPACT) 0.72 + (impactTop - 0.72) * u * u else top
                val visible = storyBeat(s, 0.0, 0.38)
                return storyPart(Material.ANVIL, x + 0.16 * (1.0 - u), sole + sy * visible / 2, 0.04,
                    0.57 * visible, sy * visible, 0.81 * visible, 0.13 * (1.0 - u), 1.32)
            }
            val age = (s - RELEASE).coerceAtMost(1.94)
            val visible = 1.0 - storyBeat(age, 1.51, 1.94)
            val launchY = slimeBottom(RELEASE) + slimeHeight(RELEASE) + sy / 2
            return storyPart(Material.ANVIL, x + 0.70 * age, launchY + 1.40 * age - 1.03 * age * age, 0.04 + 0.12 * age,
                0.57 * visible, sy * visible, 0.81 * visible, -TURN * age / 1.72, 1.32 + 0.21 * sin(age * 2.4))
        }
        val w = 0.88 + (0.77 - h) * 0.70
        val depth = 0.65 + (0.77 - h) * 0.35
        val center = AuroraOffset(x, top - h / 2, 0.0)
        val yaw = -0.23 + 0.075 * sin(TURN * s / 6.3)
        if (index == 0) return storyPart(Material.SLIME_BLOCK, center.x, center.y, center.z, w, h, depth, yaw = yaw)
        // The slime block already contains the translucent skin and the 10/16 inner cube.
        val mouth = index == 3
        val fx = when (index) { 1 -> -0.21; 2 -> 0.21; else -> 0.085 }
        return attached(Material.GREEN_TERRACOTTA, center, fx * w, (if (mouth) -0.19 else 0.13) * h, depth / 2 + 0.012,
            (if (mouth) 0.115 else 0.205) * w, (if (mouth) 0.13 else 0.22) * h, 0.048,
            Quaternionf().rotationY(yaw.toFloat()))
    }
}
