package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A hinged, double-sided gold relief. Each feather extends once and locks into the heraldic fan. */
internal object AuroraImperialAquila : AuroraStoryParts {
    const val PART_COUNT = 63

    private val gold = Material.GOLD_BLOCK
    private val brass = Material.YELLOW_TERRACOTTA
    private val amber = Material.YELLOW_CONCRETE
    private val hammered = Material.RAW_GOLD_BLOCK

    private data class Feather(val rootX: Double, val rootY: Double, val tipX: Double, val tipY: Double, val width: Double) {
        val length = hypot(tipX - rootX, tipY - rootY)
        val angle = atan2(tipY - rootY, tipX - rootX)
    }

    private val flight = listOf(
        Feather(0.540, 0.360, 1.525, 0.515, 0.187),
        Feather(0.541, 0.312, 1.398, 0.278, 0.187),
        Feather(0.529, 0.262, 1.240, 0.043, 0.177),
        Feather(0.505, 0.218, 1.039, -0.158, 0.170),
        Feather(0.468, 0.181, 0.821, -0.307, 0.156),
        Feather(0.419, 0.145, 0.573, -0.385, 0.143),
    )

    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val size = storyBeat(t, 0.0, 0.076) * (1 - storyBeat(t, 0.920, 1.0))
        return when (index) {
            in 0..5 -> chest(index, size)
            in 6..17 -> head(index - 6, t, size)
            in 18..45 -> wing(index - 18, t, size)
            in 46..52 -> tail(index - 46, t, size)
            else -> talon(index - 53, t, size)
        }
    }

    private fun box(material: Material, x: Double, y: Double, width: Double, height: Double,
        depth: Double, size: Double, roll: Double = 0.0) =
        storyPart(material, x * size, y * size, 0.0, width * size, height * size, depth * size, roll)

    private fun bar(material: Material, side: Int, ax: Double, ay: Double, bx: Double, by: Double,
        width: Double, depth: Double, size: Double): AuroraMiniaturePart {
        val dx = (bx - ax) * side
        val dy = by - ay
        return box(material, (ax + bx) * 0.5 * side, (ay + by) * 0.5, hypot(dx, dy), width,
            depth, size, atan2(dy, dx))
    }

    private fun chest(i: Int, size: Double): AuroraMiniaturePart = when (i) {
        0 -> box(hammered, 0.0, -0.114, 0.287, 0.237, 0.159, size)
        1 -> box(brass, 0.0, -0.204, 0.203, 0.203, 0.147, size, PI / 4)
        2, 3 -> bar(gold, if (i == 2) -1 else 1, 0.042, 0.235, 0.136, -0.144, 0.131, 0.172, size)
        4 -> box(amber, 0.0, 0.001, 0.129, 0.478, 0.189, size)
        else -> box(gold, 0.0, -0.102, 0.123, 0.123, 0.212, size, PI / 4)
    }

    private fun head(i: Int, t: Double, size: Double): AuroraMiniaturePart {
        val side = if (i < 6) -1 else 1
        val open = storyBeat(t, 0.045, 0.248) * (1 - storyBeat(t, 0.875, 0.975))
        val turn = 0.29 * (1 - open)
        val cs = cos(turn)
        val sn = sin(turn)
        fun x(px: Double, py: Double) = 0.061 + (px - 0.061) * cs - (py - 0.104) * sn
        fun y(px: Double, py: Double) = 0.104 + (px - 0.061) * sn + (py - 0.104) * cs
        fun feature(material: Material, ax: Double, ay: Double, bx: Double, by: Double,
            width: Double, depth: Double) = bar(material, side, x(ax, ay), y(ax, ay), x(bx, by), y(bx, by), width, depth, size)
        return when (i % 6) {
            0 -> feature(hammered, 0.061, 0.104, 0.174, 0.464, 0.139, 0.134)
            1 -> feature(gold, 0.131, 0.483, 0.270, 0.516, 0.148, 0.156)
            2 -> feature(amber, 0.132, 0.550, 0.279, 0.568, 0.047, 0.172)
            3 -> feature(gold, 0.242, 0.535, 0.408, 0.503, 0.064, 0.142)
            4 -> feature(amber, 0.410, 0.520, 0.378, 0.420, 0.046, 0.149)
            else -> if (side < 0) feature(Material.POLISHED_BLACKSTONE, 0.203, 0.535, 0.237, 0.539, 0.034, 0.194)
                else feature(brass, 0.202, 0.534, 0.249, 0.541, 0.014, 0.193)
        }
    }

    private fun wing(i: Int, t: Double, size: Double): AuroraMiniaturePart {
        val side = if (i < 14) -1 else 1
        val slot = i % 14
        val hinge = storyBeat(t, 0.067, 0.29) * (1 - storyBeat(t, 0.857, 0.975))
        fun mountX(x: Double) = 0.14 + (x - 0.14) * (0.30 + 0.70 * hinge)
        fun mountY(y: Double) = 0.059 + (y - 0.059) * (0.38 + 0.62 * hinge)
        if (slot == 0) return bar(hammered, side, 0.105, 0.040, mountX(0.525), mountY(0.242),
            0.169, 0.158, size)
        if (slot == 1) return bar(gold, side, mountX(0.451), mountY(0.137), mountX(0.545), mountY(0.373),
            0.173, 0.182, size)

        val row = (slot - 2) / 2
        val ridge = slot % 2 == 1
        val feather = flight[row]
        val delay = 0.019 * row
        val fan = storyBeat(t, 0.100 + delay, 0.375 + delay) * (1 - storyBeat(t, 0.835 - delay * 0.46, 0.969))
        val folded = -1.21 + 0.045 * row
        val angle = folded + (feather.angle - folded) * fan
        val dx = cos(angle)
        val dy = sin(angle)
        val length = feather.length * (0.34 + 0.66 * fan)
        val rootX = mountX(feather.rootX)
        val rootY = mountY(feather.rootY)
        // A long raised vane projects past the shorter bronze plate, giving each feather a stepped tip.
        val start = if (ridge) 0.065 else 0.0
        val end = if (ridge) 1.0 else 0.863
        val bevel = if (ridge) feather.width * 0.18 else 0.0
        return bar(if (ridge) gold else brass, side,
            rootX + dx * length * start - dy * bevel, rootY + dy * length * start + dx * bevel,
            rootX + dx * length * end - dy * bevel, rootY + dy * length * end + dx * bevel,
            feather.width * (if (ridge) 0.69 else 1.0), (if (ridge) 0.120 else 0.087) + 0.004 * row, size)
    }

    private fun tail(i: Int, t: Double, size: Double): AuroraMiniaturePart {
        val n = abs(i - 3)
        val side = if (i < 3) -1 else 1
        val fan = storyBeat(t, 0.225 + 0.018 * n, 0.470 + 0.018 * n) * (1 - storyBeat(t, 0.810, 0.955))
        val rootY = -0.207
        val length = (0.512 - 0.046 * n) * (0.53 + 0.47 * fan)
        return bar(if (n % 2 == 0) gold else amber, side,
            n * 0.026 * (0.58 + 0.42 * fan), rootY, n * 0.082 * (0.16 + 0.84 * fan), rootY - length,
            0.065 - 0.003 * n, 0.075 + 0.009 * n, size)
    }

    private fun talon(i: Int, t: Double, size: Double): AuroraMiniaturePart {
        val side = if (i < 5) -1 else 1
        val grip = storyBeat(t, 0.180, 0.388) * (1 - storyBeat(t, 0.842, 0.961))
        val footX = 0.214 + 0.062 * grip
        val footY = -0.323 - 0.045 * grip
        val reach = 0.60 + 0.40 * grip
        return when (i % 5) {
            0 -> bar(brass, side, 0.110, -0.143, 0.224, -0.283, 0.095, 0.116, size)
            1 -> bar(gold, side, 0.224, -0.282, footX, footY, 0.076, 0.131, size)
            2 -> bar(gold, side, footX - 0.018, footY, footX + 0.122 * reach, footY - 0.030, 0.061, 0.127, size)
            3 -> bar(amber, side, footX + 0.121 * reach, footY - 0.016, footX + 0.096 * reach, footY - 0.124 * reach,
                0.047, 0.135, size)
            else -> bar(gold, side, footX - 0.005, footY, footX - 0.083 * reach, footY - 0.098 * reach,
                0.054, 0.139, size)
        }
    }
}
