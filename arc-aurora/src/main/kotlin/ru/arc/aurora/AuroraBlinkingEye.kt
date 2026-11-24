package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The cornea and iris keep their size while the two lids travel in front of them. */
internal object AuroraBlinkingEye : AuroraStoryParts {
    private const val COLUMNS = 11
    private const val LAYERS = 7
    private const val HALF_WIDTH = 1.68
    private const val IRIS_ROWS = 7
    private const val FOLDS = 6
    private const val PUPIL_ROWS = 11
    private const val TURN = 2.0 * PI
    private const val IRIS = COLUMNS * LAYERS
    private const val LID_FOLD = IRIS + IRIS_ROWS * 2
    private const val PUPIL = LID_FOLD + FOLDS
    private const val GLINT = PUPIL + PUPIL_ROWS
    private const val VEINS = GLINT + 2

    override fun partCount(shape: AuroraShape) = VEINS + 10

    private fun blink(t: Double, start: Double, shut: Double, lift: Double, end: Double) =
        storyBeat(t, start, shut) - storyBeat(t, lift, end)

    private fun arch(x: Double): Double {
        val u = x / HALF_WIDTH
        return (1.0 - abs(u) * (0.36 + 0.64 * abs(u))).coerceAtLeast(0.0)
    }

    private fun seam(x: Double) = 0.033 * x - 0.165 * arch(x)
    private fun opening(x: Double, upper: Boolean, closure: Double): Double {
        val a = arch(x)
        return seam(x) + a * (if (upper) 0.714 else -0.326) * (1.0 - closure)
    }

    private fun cord(material: Material, x1: Double, y1: Double, x2: Double, y2: Double,
        z: Double, thickness: Double, depth: Double) = storyPart(material, (x1 + x2) * 0.5, (y1 + y2) * 0.5, z,
        hypot(x2 - x1, y2 - y1) + 0.012, thickness, depth, roll = atan2(y2 - y1, x2 - x1))

    private fun socket(index: Int, closure: Double): AuroraMiniaturePart {
        val col = index / LAYERS
        val layer = index % LAYERS
        val width = 2.0 * HALF_WIDTH / COLUMNS
        val left = -HALF_WIDTH + width * col
        val right = left + width
        val x = (left + right) * 0.5
        val a = arch(x)
        if (layer == 0) {
            // The eye white bulges through the aperture, with its corners recessed into the socket.
            val span = maxOf(arch(left), arch(right))
            val height = if (col == 0 || col == COLUMNS - 1) 0.161 else 1.16 * span
            val sclera = if (col == 0 || col == COLUMNS - 1) Material.WHITE_TERRACOTTA else Material.BONE_BLOCK
            return storyPart(sclera, x, 0.033 * x + 0.025 * a, 0.016 + 0.21 * a,
                width + 0.008, height, 0.14 + 0.07 * a)
        }
        val upper = layer % 2 == 1
        val outerL = 0.033 * left + (if (upper) 0.72 else -0.75) * arch(left)
        val outerR = 0.033 * right + (if (upper) 0.72 else -0.75) * arch(right)
        val overlap = (if (upper) -0.009 else 0.009) * closure
        val edgeL = opening(left, upper, closure) + overlap
        val edgeR = opening(right, upper, closure) + overlap
        val z = 0.535 + 0.050 * a
        val skin = if (upper) Material.PURPLE_TERRACOTTA else Material.STRIPPED_CRIMSON_HYPHAE
        if (layer <= 2) {
            // The rolled edges cover each end of the broad lid panel as its height changes.
            val outer = if (upper) minOf(outerL, outerR) else maxOf(outerL, outerR)
            val inner = (edgeL + edgeR) * 0.5
            val front = z - 0.016
            val back = 0.08 + 0.085 * a
            return storyPart(skin, x, (outer + inner) * 0.5, (front + back) * 0.5,
                width + 0.013, abs(outer - inner) + 0.025, front - back)
        }
        val outer = layer >= 5
        val y1 = if (outer) outerL else edgeL
        val y2 = if (outer) outerR else edgeR
        val dy = y2 - y1
        val length = hypot(width, dy)
        val sign = if (upper == outer) -1.0 else 1.0
        val base = if (outer) 0.038 else if (upper) 0.035 else 0.022
        val thickness = base * (0.55 + 0.45 * a) + abs(dy) * width / length * (if (outer) 0.88 else 0.47)
        val shift = sign * (thickness * 0.5 - 0.009)
        val material = if (outer) skin else if (upper) Material.BLACK_TERRACOTTA else Material.RED_TERRACOTTA
        val front = z + (if (outer) 0.010 else if (upper) 0.045 else 0.037)
        val back = if (outer) -0.014 + 0.080 * a else z - 0.046
        return storyPart(material, x - dy * shift / length, (y1 + y2) * 0.5 + width * shift / length,
            (front + back) * 0.5, length + 0.020, thickness, front - back,
            roll = atan2(dy, width))
    }

    private data class Vein(val x: Double, val y: Double, val tipX: Double, val tipY: Double)

    // Off-centre forks lead in from the canthi and stop before the iris.
    private val veins = arrayOf(
        Vein(-1.51, -0.110, -1.18, -0.080), Vein(-1.18, -0.080, -0.99, -0.174),
        Vein(-0.99, -0.174, -0.79, -0.144), Vein(-1.18, -0.080, -1.00, 0.043),
        Vein(-1.00, 0.043, -0.87, 0.064), Vein(1.50, 0.109, 1.26, 0.141),
        Vein(1.26, 0.141, 1.06, 0.061), Vein(1.06, 0.061, 0.87, 0.095),
        Vein(1.26, 0.141, 1.20, 0.228), Vein(1.20, 0.228, 1.04, 0.257),
    )

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val time = t % 1.0
        if (index < IRIS || index in LID_FOLD until PUPIL) {
            val closure = blink(time, 0.240, 0.255, 0.280, 0.330) +
                blink(time, 0.699, 0.714, 0.729, 0.754) + blink(time, 0.758, 0.774, 0.790, 0.842)
            if (index < IRIS) return socket(index, closure)
            val i = index - LID_FOLD
            val left = -1.16 + 2.21 * i / FOLDS
            val right = left + 2.21 / FOLDS
            fun crease(x: Double) = 0.033 * x + (0.639 - 0.44 * closure) * arch(x) - 0.022 * x * x
            return cord(Material.MAGENTA_TERRACOTTA, left, crease(left), right, crease(right), 0.580,
                (0.014 + 0.012 * closure) * arch((left + right) * 0.5), 0.016)
        }
        if (index >= VEINS) {
            val i = index - VEINS
            val v = veins[i]
            val z = 0.134 + 0.20 * maxOf(arch(v.x), arch(v.tipX))
            return cord(if (i == 0 || i == 5) Material.RED_TERRACOTTA else Material.PINK_TERRACOTTA,
                v.x, v.y, v.tipX, v.tipY, z, if (i == 0 || i == 5) 0.019 else 0.012, 0.020)
        }
        val focus = storyBeat(time, 0.488, 0.510) - storyBeat(time, 0.610, 0.680)
        val gazeX = (-0.43 + 0.91 * storyBeat(time, 0.028, 0.215) - 1.03 * storyBeat(time, 0.254, 0.276) +
            0.20 * storyBeat(time, 0.34, 0.46) - 0.08 * storyBeat(time, 0.83, 0.97)) * (1.0 - focus)
        val gazeY = -0.036 + 0.068 * sin(TURN * time) * (1.0 - focus)
        if (index < LID_FOLD) {
            val slot = index - IRIS
            val rim = slot < IRIS_ROWS
            // Each rectangle spans two sectors of the same fourteen-sided disk.
            val sector = slot % IRIS_ROWS
            val halfSector = PI / (2 * IRIS_ROWS)
            val radius = if (rim) 0.545 else 0.499
            return storyPart(if (rim) Material.BLACK_TERRACOTTA else Material.SHROOMLIGHT,
                gazeX, gazeY, (if (rim) 0.360 else 0.394) + sector * 0.0008,
                2 * radius * cos(halfSector), 2 * radius * sin(halfSector), if (rim) 0.048 else 0.026,
                roll = sector * PI / IRIS_ROWS + 0.072)
        }
        val dilation = 0.098 + 0.035 * (1.0 - cos(TURN * time)) - 0.099 * focus
        if (index < GLINT) {
            val band = (index - PUPIL + 0.5) * 2.0 / PUPIL_ROWS - 1.0
            val tilt = -0.054
            return storyPart(Material.BLACK_CONCRETE, gazeX + dilation * band * cos(tilt), gazeY + dilation * band * sin(tilt),
                0.448, 2.0 * dilation / PUPIL_ROWS + 0.003, 0.906 * (1.0 - 0.96 * abs(band)), 0.018, tilt)
        }
        val main = index == GLINT
        return storyPart(if (main) Material.PEARLESCENT_FROGLIGHT else Material.SMOOTH_QUARTZ,
            gazeX + (if (main) -0.125 else -0.046), gazeY + (if (main) 0.175 else 0.115), 0.470,
            if (main) 0.090 else 0.033, if (main) 0.050 else 0.027, 0.013, -0.24)
    }
}
