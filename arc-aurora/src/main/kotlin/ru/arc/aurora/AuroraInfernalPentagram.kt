package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraInfernalPentagram : AuroraStoryParts {
    const val PART_COUNT = 75

    private const val RIM_COUNT = 20
    private const val RIM_RADIUS = 1.18
    private const val TURN = 2.0 * PI
    private val intersections = doubleArrayOf(0.0, 0.381966, 0.618034, 1.0)
    private val weaveDepth = doubleArrayOf(0.0, 0.093, -0.093, 0.0)
    private val emblems = listOf(Material.NETHER_STAR, Material.FIRE_CHARGE, Material.BLAZE_POWDER,
        Material.GHAST_TEAR, Material.NETHER_WART)

    private fun angle(i: Int, total: Int) = -PI / 2 + TURN * i / total
    private fun polar(radius: Double, angle: Double) = AuroraOffset(radius * cos(angle), radius * sin(angle), 0.0)
    private val tips = List(5) { polar(1.01, angle(it, 5)) }
    private val seals = List(5) { polar(1.15, angle(it, 5)) }

    /** A cuboid drawn from one end, including the slope through the plane of the seal. */
    private class Bar(val a: AuroraOffset, b: AuroraOffset) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        private val planar = hypot(dx, dy)
        private val length = hypot(planar, dz)
        private val roll = atan2(dy, dx)
        private val yaw = -atan2(dz, planar)

        fun part(material: Material, from: Double, to: Double, width: Double, depth: Double): AuroraMiniaturePart {
            val span = (to - from).coerceAtLeast(0.0)
            val middle = from + span * 0.5
            val shown = if (span > 0.0) 1.0 else 0.0
            return storyPart(material, a.x + dx * middle, a.y + dy * middle, a.z + dz * middle,
                length * span, width * shown, depth * shown, roll, yaw)
        }
    }

    // At the two exact intersections, one chord crests above the other and then dives underneath.
    private val blades = List(15) { slot ->
        val chord = slot / 3
        val start = tips[chord * 2 % 5]
        val end = tips[(chord * 2 + 2) % 5]
        fun joint(j: Int) = AuroraOffset(start.x + (end.x - start.x) * intersections[j],
            start.y + (end.y - start.y) * intersections[j], weaveDepth[j])
        val j = slot % 3
        Bar(joint(j), joint(j + 1))
    }
    private val rim = List(RIM_COUNT) { i ->
        val middle = angle(i, RIM_COUNT)
        val half = 0.465 * TURN / RIM_COUNT
        Bar(polar(RIM_RADIUS, middle - half), polar(RIM_RADIUS, middle + half))
    }

    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (index) {
        in 0..29 -> blade(index, t)
        in 30..49 -> rimPlate(index - 30, t)
        in 50..54 -> vent(index - 50, t)
        in 55..69 -> seal(index - 55, t)
        else -> eye(index - 70, t)
    }

    private fun blade(index: Int, t: Double): AuroraMiniaturePart {
        val barIndex = index / 2
        val chord = barIndex / 3
        val j = barIndex % 3
        val hot = index % 2 == 1
        val lag = if (hot) 0.012 else 0.0
        val trace = minOf(storyBeat(t, 0.032 + lag, 0.402 + lag),
            1 - storyBeat(t, 0.871 - lag, 0.978 - lag)) * 5
        val drawn = ((trace - chord - intersections[j]) / (intersections[j + 1] - intersections[j])).coerceIn(0.0, 1.0)
        val strike = 0.423 + 0.050 * ((chord * 2 + 2) % 5)
        val heat = storyBeat(t, strike, strike + 0.022) - storyBeat(t, strike + 0.033, strike + 0.087)
        // The bright metal is narrower and thicker than its casing so it shows through both faces.
        return if (hot) blades[barIndex].part(Material.REDSTONE_BLOCK, 0.0, drawn, 0.082 + 0.021 * heat, 0.115)
        else blades[barIndex].part(Material.POLISHED_BLACKSTONE, 0.0, drawn, 0.143, 0.079)
    }

    private fun rimPlate(i: Int, t: Double): AuroraMiniaturePart {
        val distance = minOf(i, RIM_COUNT - i)
        val arrival = 0.004 + 0.015 * distance
        val end = 0.982 - 0.0037 * distance
        val span = storyBeat(t, arrival, arrival + 0.067) * (1 - storyBeat(t, end - 0.055, end))
        val material = if (i % 2 == 0) Material.POLISHED_BLACKSTONE_BRICKS else Material.RED_NETHER_BRICKS
        return if (i <= RIM_COUNT / 2) rim[i].part(material, 0.0, span, 0.098, 0.078)
        else rim[i].part(material, 1.0 - span, 1.0, 0.098, 0.078)
    }

    private fun vent(i: Int, t: Double): AuroraMiniaturePart {
        val at = 0.392 + 0.050 * i
        val lit = storyBeat(t, at, at + 0.032) * (1 - storyBeat(t, 0.886 - 0.011 * i, 0.943 - 0.011 * i))
        val half = 0.40 * lit
        return rim[4 * i + 2].part(Material.MAGMA_BLOCK, 0.5 - half, 0.5 + half, 0.047, 0.102)
    }

    private fun seal(index: Int, t: Double): AuroraMiniaturePart {
        val i = index / 3
        val p = seals[i]
        val at = 0.423 + 0.050 * i
        val arrive = 0.045 + 0.044 * minOf(i, 5 - i)
        val end = 0.968 - 0.005 * i
        val mount = storyBeat(t, arrive, arrive + 0.077) * (1 - storyBeat(t, end - 0.055, end))
        val kindle = storyBeat(t, at, at + 0.040)
        val flare = storyBeat(t, at, at + 0.025) - storyBeat(t, at + 0.034, at + 0.089)
        val size = mount * (1 + 0.12 * flare)
        val turn = angle(i, 5) + PI / 4
        return when (index % 3) {
            0 -> storyPart(Material.CHISELED_POLISHED_BLACKSTONE, p.x, p.y, 0.0,
                0.296 * size, 0.296 * size, 0.088 * size, turn)
            1 -> {
                val inset = size * (0.78 + 0.22 * kindle)
                storyPart(Material.CUT_COPPER, p.x, p.y, 0.0, 0.220 * inset, 0.220 * inset, 0.112 * size, turn)
            }
            else -> {
                val glow = kindle * (1 - storyBeat(t, 0.883 - 0.013 * i, 0.932 - 0.013 * i))
                val face = 0.306 * size * glow
                // Generated items have caps at ±1/32. Both caps protrude past the copper inset.
                val relief = if (face > 0.0) 2.12 * size else 0.0
                storyPart(emblems[i], p.x, p.y, 0.0, face, face, relief, (1 - kindle) * 0.54, PI)
            }
        }
    }

    private val eyelidWidths = doubleArrayOf(0.346, 0.278, 0.202, 0.119)
    private val eyelidHeights = doubleArrayOf(0.030, 0.071, 0.109, 0.151)

    private fun eye(i: Int, t: Double): AuroraMiniaturePart {
        val lid = storyBeat(t, 0.666, 0.695) * (1 - storyBeat(t, 0.810, 0.827))
        val opening = storyBeat(t, 0.673, 0.710) * (1 - storyBeat(t, 0.793, 0.820))
        if (i < 4) {
            // Successively taller, narrower steps make a solid almond with the same relief on both faces.
            return storyPart(Material.POLISHED_BLACKSTONE, 0.0, 0.008, 0.0, eyelidWidths[i] * lid,
                (0.024 + (eyelidHeights[i] - 0.024) * opening) * lid, (0.055 + 0.009 * i) * lid)
        }
        val light = storyBeat(t, 0.684, 0.707) * (1 - storyBeat(t, 0.790, 0.811))
        // Both native caps of this single amber slit stand proud of the dark setting.
        return storyPart(Material.HONEYCOMB, 0.0, 0.004, 0.0, 0.084 * light,
            0.135 * opening * light, if (light > 0.0) 1.91 * lid else 0.0, yaw = PI)
    }
}
