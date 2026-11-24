package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A single socket in the swarm. Its native billboard keeps the slit facing each viewer. */
internal object AuroraWatchingEyes : AuroraStoryParts {
    private val cuts = doubleArrayOf(-0.90, -0.56, -0.19, 0.19, 0.56, 0.90)

    override fun partCount(shape: AuroraShape) = 20

    private fun arch(x: Double): Double {
        val u = abs(x) / 0.90
        return 1.0 - u * (0.16 + 0.84 * u)
    }

    private fun seam(x: Double) = 0.025 * x - 0.055 * arch(x)

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val phase = 2.0 * PI * (t % 1.0)
        val breath = (1.0 - cos(phase)) * 0.5
        val cant = 0.14 * sin(phase)
        val cs = cos(cant)
        val sn = sin(cant)
        fun piece(material: Material, x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double,
            roll: Double = 0.0) = storyPart(material, x * cs - y * sn, x * sn + y * cs, z, sx, sy, sz, roll + cant)
        val closure = storyBeat(t, 0.310, 0.353) - storyBeat(t, 0.453, 0.545)
        if (index >= 15) {
            // The tissue withdraws behind the closing lids, leaving a raised seam in the sky.
            val height = 1.0 - 0.80 * storyBeat(closure, 0.18, 1.0)
            val y = -0.050 * closure
            return when (index) {
                15 -> piece(Material.SNOWBALL, 0.0, y - 0.009 * height, 0.015, 1.87, 0.68 * height, 0.22)
                16 -> piece(Material.ENDER_EYE, 0.019, y + 0.016 * height, 0.070, 0.74, 0.73 * height, 0.20)
                17 -> piece(Material.BLACK_CONCRETE, 0.0, y - 0.008 * height, 0.101,
                    0.071 + 0.017 * breath, 0.359 * height, 0.021, -0.034)
                18 -> piece(Material.OCHRE_FROGLIGHT, -0.040, y + 0.088 * height, 0.120,
                    0.054, 0.039 * height, 0.012, -0.22)
                else -> piece(Material.ENDER_PEARL, 0.052, y + 0.024 * height, -0.040, 2.04, 0.92 * height, 0.20)
            }
        }
        val column = if (index < 10) index / 2 else index - 10
        val upper = index >= 10 || index % 2 == 0
        val sign = if (upper) 1.0 else -1.0
        val reach = if (upper) 0.350 - 0.025 * breath else 0.212
        fun edge(x: Double) = seam(x) + sign * (reach * arch(x) * (1.0 - closure) - 0.013 * closure)

        val left = cuts[column]
        val right = cuts[column + 1]
        val x = (left + right) * 0.5
        val y = (edge(left) + edge(right)) * 0.5
        val dx = right - left
        val dy = edge(right) - edge(left)
        val length = hypot(dx, dy)
        val a = arch(x)
        val rim = index >= 10
        val openOuter = (if (upper) 0.414 else -0.392) * (0.06 + 0.94 * a) + 0.025 * x
        val closedOuter = seam(x) + sign * (0.030 + 0.094 * a)
        val outer = openOuter + (closedOuter - openOuter) * closure
        val width = if (rim) 0.024 + 0.016 * a else abs(outer - y) * dx / length + 0.008
        val shift = sign * (width * 0.5 - 0.004)
        val skin = if (rim) Material.POLISHED_BLACKSTONE else if (upper) Material.CYAN_TERRACOTTA else Material.STRIPPED_WARPED_HYPHAE
        return piece(skin, x - dy / length * shift, y + dx / length * shift, if (rim) 0.232 else 0.180,
            length + (if (rim) 0.012 else 0.036 + 0.18 * width), width, if (rim) 0.024 else 0.085,
            roll = atan2(dy, dx))
    }
}
