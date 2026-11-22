package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraDreamlandStories : AuroraStoryParts {
    private const val GAUNT_PARTS = 63
    private const val GARDEN_PARTS = 61

    override fun partCount(shape: AuroraShape) = when (shape) {
        AuroraShape.NIGHT_GAUNT -> GAUNT_PARTS
        AuroraShape.COLOUR_FROM_SPACE -> GARDEN_PARTS
        else -> error("Not a Dreamland story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double) = when (shape) {
        AuroraShape.NIGHT_GAUNT -> gaunt(index, t)
        AuroraShape.COLOUR_FROM_SPACE -> garden(index, t)
        else -> error("Not a Dreamland story: $shape")
    }

    private fun point(x: Double, y: Double, z: Double = 0.0) = AuroraOffset(x, y, z)

    private fun along(a: AuroraOffset, b: AuroraOffset, p: Double) =
        point(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p, a.z + (b.z - a.z) * p)

    private fun beam(material: Material, a: AuroraOffset, b: AuroraOffset, width: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dy)
        return storyPart(material, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            width, hypot(flat, dz) + width * 0.15, width, atan2(-dx, dy), pitch = atan2(dz, flat))
    }

    private fun place(local: AuroraMiniaturePart, origin: AuroraOffset, size: Double, rotation: Quaternionf): AuroraMiniaturePart {
        val p = local.center
        val offset = rotation.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())).mul(size.toFloat())
        return AuroraMiniaturePart(local.material, point(origin.x + offset.x, origin.y + offset.y, origin.z + offset.z),
            rotation.mul(local.rotation), Vector3f(local.scale).mul(size.toFloat()))
    }

    private val wingX = doubleArrayOf(0.0, 0.18, 0.36, 0.54, 0.73, 0.90, 1.08, 1.23)
    private val leading = doubleArrayOf(0.16, 0.33, 0.47, 0.51, 0.49, 0.445, 0.335, 0.21)
    private val trailing = doubleArrayOf(-0.015, 0.025, -0.13, 0.105, -0.09, 0.115, -0.002, 0.032)

    private fun wing(slot: Int, side: Double, fold: Double, breath: Double): AuroraMiniaturePart {
        val angle = -1.16 * fold + breath
        val span = 1.0 - 0.25 * fold
        fun joint(x: Double, y: Double, front: Double): AuroraOffset {
            val reach = span * x
            val lift = y - 0.135
            return point(side * (0.109 + reach * cos(angle) - lift * sin(angle)),
                0.135 + reach * sin(angle) + lift * cos(angle), front - (0.095 + 0.42 * fold) * x)
        }
        if (slot >= 14) {
            val a: AuroraOffset
            val b: AuroraOffset
            when (slot) {
                14 -> { a = joint(0.0, 0.145, 0.075); b = joint(0.53, 0.505, 0.071) }
                15 -> { a = joint(0.53, 0.505, 0.071); b = joint(0.91, 0.445, 0.061) }
                16 -> { a = joint(0.91, 0.445, 0.061); b = joint(1.39, 0.04, 0.025) }
                else -> { a = joint(0.53, 0.505, 0.071); b = joint(0.36, -0.125, 0.061) }
            }
            return beam(if (slot == 17) Material.POLISHED_BLACKSTONE else Material.POLISHED_BASALT, a, b,
                if (slot == 17) 0.032 else 0.046)
        }

        // Two thin facets meet along each scallop. Tilting the membrane in depth gives each edge its own slope
        // without leaving the stair-step gaps of a stack of upright boxes.
        val i = slot / 2
        val upper = slot % 2 == 0
        val border = if (upper) leading else trailing
        val width = (wingX[i + 1] - wingX[i]) * span
        val height = minOf(leading[i] - trailing[i], leading[i + 1] - trailing[i + 1]) + 0.006
        val x = (wingX[i] + wingX[i + 1]) / 2
        val y = (border[i] + border[i + 1]) / 2 + (if (upper) -0.5 else 0.5) * height
        val p = joint(x, y, if (upper) -0.15 else -0.178)
        val tilt = 0.70
        val skew = atan(side * (border[i + 1] - border[i]) / (width * sin(tilt)))
        val rotation = Quaternionf().rotateZ((side * angle).toFloat()).rotateX(tilt.toFloat()).rotateY(skew.toFloat())
        return AuroraMiniaturePart(Material.BLUE_TERRACOTTA, p, rotation,
            Vector3f(((width + 0.006) / cos(skew)).toFloat(), (height / cos(tilt)).toFloat(), 0.037f))
    }

    private fun gaunt(index: Int, t: Double): AuroraMiniaturePart {
        val arrive = storyBeat(t, 0.0, 0.151)
        val fold = storyBeat(t, 0.258, 0.426) * (1.0 - storyBeat(t, 0.538, 0.636))
        val dive = storyBeat(t, 0.366, 0.608)
        val rise = storyBeat(t, 0.605, 0.873)
        val exit = storyBeat(t, 0.845, 1.0)
        val gather = fold + (1.0 - arrive) * 0.44 + 0.42 * exit
        val sway = sin(2 * PI * (1.65 * t + 0.07))
        val local = when (index) {
            in 0..35 -> wing(index % 18, if (index < 18) -1.0 else 1.0, gather, 0.036 * sway)
            36 -> storyPart(Material.POLISHED_BLACKSTONE, 0.0, 0.070, 0.053, 0.246, 0.228, 0.165, -0.044)
            37 -> storyPart(Material.BLACK_CONCRETE, 0.0, -0.087, 0.033, 0.147, 0.198, 0.128, 0.048)
            38 -> storyPart(Material.POLISHED_BASALT, 0.0, 0.194, 0.050, 0.074, 0.110, 0.079)
            39 -> storyPart(Material.POLISHED_BLACKSTONE, 0.0, 0.29, 0.079, 0.180, 0.207, 0.149, 0.045)
            40 -> storyPart(Material.BLACK_CONCRETE, 0.0, 0.287, 0.155, 0.154, 0.177, 0.020, 0.045)
            in 41..44 -> {
                val side = if (index < 43) -1.0 else 1.0
                val hook = point(side * 0.175, 0.525, 0.065)
                if ((index - 41) % 2 == 0) beam(Material.POLISHED_BASALT, point(side * 0.061, 0.371, 0.070), hook, 0.050)
                else beam(Material.GRAY_CONCRETE, hook, point(side * 0.122, 0.639, 0.086), 0.031)
            }
            in 45..52 -> {
                val side = if (index < 49) -1.0 else 1.0
                val elbow = point(side * (0.282 - 0.107 * fold), -0.064 - 0.047 * fold, 0.111)
                val wrist = point(side * (0.423 - 0.207 * fold), -0.198 - 0.071 * fold, 0.145)
                when ((index - 45) % 4) {
                    0 -> beam(Material.POLISHED_BLACKSTONE, point(side * 0.112, 0.127, 0.058), elbow, 0.057)
                    1 -> beam(Material.POLISHED_BASALT, elbow, wrist, 0.048)
                    2 -> beam(Material.POLISHED_ANDESITE, wrist, point(wrist.x + side * 0.06, wrist.y - 0.086, 0.159), 0.032)
                    else -> beam(Material.POLISHED_ANDESITE, wrist, point(wrist.x - side * 0.035, wrist.y - 0.114, 0.17), 0.026)
                }
            }
            in 53..56 -> {
                val side = if (index < 55) -1.0 else 1.0
                val knee = point(side * (0.102 + 0.038 * sway), -0.319 + side * 0.045 * fold, 0.042)
                if ((index - 53) % 2 == 0) beam(Material.POLISHED_BLACKSTONE, point(side * 0.046, -0.156, 0.039), knee, 0.061)
                else beam(Material.POLISHED_BASALT, knee, point(side * (0.177 - 0.054 * fold), -0.487, -0.025), 0.041)
            }
            else -> {
                val n = index - 57
                fun tail(k: Int): AuroraOffset {
                    val u = k / 6.0
                    return point(-0.19 * sin(1.7 * PI * u) + 0.32 * u * u + 0.105 * u * sin(9 * t - 3 * u),
                        -0.165 - 0.51 * sin(0.79 * PI * u) - 0.13 * u + fold * 0.24 * u, -0.025 - 0.117 * u)
                }
                beam(if (n >= 4) Material.POLISHED_ANDESITE else Material.POLISHED_BASALT, tail(n), tail(n + 1), 0.061 - n * 0.006)
            }
        }
        val size = (0.62 + 0.38 * arrive - 0.51 * exit) * storyBeat(t, 0.0, 0.064) * (1 - storyBeat(t, 0.935, 1.0))
        val origin = point(-0.22 + 0.10 * arrive + 0.21 * dive - 0.13 * exit,
            0.14 + 0.22 * arrive - 0.73 * dive + 0.57 * rise + 0.12 * exit, -0.16 + 0.28 * dive - 0.30 * exit)
        val rotation = Quaternionf().rotateZ((-0.085 + 0.17 * fold - 0.07 * rise).toFloat())
            .rotateY((0.055 * sway + 0.12 * fold).toFloat()).rotateX((-0.058 - 0.15 * fold).toFloat())
        return place(local, origin, size, rotation)
    }

    private data class Bough(val a: AuroraOffset, val b: AuroraOffset, val width: Double, val at: Double, val colour: Material)
    private val tree = buildList {
        val violet = Material.MAGENTA_CONCRETE
        val cyan = Material.DIAMOND_BLOCK
        val acid = Material.LIME_CONCRETE
        val roots = listOf(point(-0.64, -0.392, 0.259), point(-0.49, -0.523, 0.264), point(-0.145, -0.545, 0.266),
            point(0.108, -0.473, 0.262), point(0.29, -0.395, 0.102))
        for (i in 0..3) add(Bough(roots[i], roots[i + 1], 0.044, 0.335 + 0.030 * i, if (i == 2) cyan else violet))
        val n = listOf(roots.last(), point(0.21, -0.12, 0.085), point(0.31, 0.095, 0.068), point(0.31, 0.34, 0.042),
            point(0.015, 0.22, 0.05), point(-0.13, 0.39, 0.073), point(-0.33, 0.46, 0.055), point(-0.15, 0.58, 0.078),
            point(-0.01, 0.43, -0.039), point(0.39, 0.53, 0.04), point(0.31, 0.69, 0.077), point(0.60, 0.67, 0.06),
            point(0.62, 0.25, 0.042), point(0.78, 0.38, 0.062), point(0.99, 0.45, 0.061), point(0.82, 0.57, 0.038),
            point(0.67, 0.11, 0.091), point(0.82, 0.18, 0.11))
        fun branch(a: Int, b: Int, width: Double, at: Double, colour: Material) = add(Bough(n[a], n[b], width, at, colour))
        branch(0, 1, 0.124, 0.451, violet)
        branch(1, 2, 0.103, 0.485, cyan)
        branch(2, 3, 0.083, 0.527, violet)
        branch(2, 4, 0.082, 0.520, violet)
        branch(4, 5, 0.062, 0.552, cyan)
        branch(5, 6, 0.041, 0.587, violet)
        branch(5, 7, 0.039, 0.605, acid)
        branch(4, 8, 0.040, 0.592, violet)
        branch(3, 9, 0.061, 0.571, cyan)
        branch(9, 10, 0.038, 0.615, acid)
        branch(9, 11, 0.041, 0.638, violet)
        branch(2, 12, 0.077, 0.554, violet)
        branch(12, 13, 0.056, 0.594, cyan)
        branch(13, 14, 0.037, 0.650, acid)
        branch(13, 15, 0.042, 0.669, violet)
        branch(12, 16, 0.046, 0.615, violet)
        branch(16, 17, 0.032, 0.653, cyan)
    }
    private val fruit = intArrayOf(9, 10, 13, 14, 17, 18)

    private fun garden(index: Int, t: Double): AuroraMiniaturePart {
        val local = when (index) {
            0 -> storyPart(Material.ROOTED_DIRT, -0.68, -0.548, 0.014, 0.80, 0.21, 0.46)
            1 -> storyPart(Material.MUD, 0.012, -0.637, -0.006, 1.82, 0.134, 0.35)
            2 -> storyPart(Material.COARSE_DIRT, 0.06, -0.442, -0.05, 1.91, 0.062, 0.367, 0.012)
            3 -> storyPart(Material.DEAD_BUSH, -0.813, -0.284, -0.035, 0.348, 0.35, 0.77)
            4 -> storyPart(Material.DEAD_BUSH, 0.84, -0.324, -0.064, 0.244, 0.255, 0.67, 0.16)
            5 -> storyPart(Material.SPRUCE_FENCE, -0.32, -0.308, -0.172, 0.19, 0.244, 0.48, -0.055, PI / 2)
            in 6..10 -> {
                val comet = storyBeat(t, 0.047, 0.279)
                val split = storyBeat(t, 0.279, 0.376)
                val flight = point(-1.11 + 0.47 * comet, 0.856 - 1.182 * comet, -0.112 + 0.224 * comet)
                val seen = storyBeat(t, 0.035, 0.089)
                if (index == 10) {
                    val streak = seen * (1.0 - storyBeat(t, 0.236, 0.295))
                    beam(Material.PEARLESCENT_FROGLIGHT, flight,
                        point(flight.x - 0.155 * streak, flight.y + 0.38 * streak, flight.z - 0.055 * streak), 0.036 * streak)
                } else if (index == 9) {
                    val core = storyBeat(t, 0.265, 0.309) * (1.0 - storyBeat(t, 0.366, 0.448))
                    storyPart(Material.MAGENTA_GLAZED_TERRACOTTA, flight.x, flight.y, flight.z + 0.04,
                        0.164 * core, 0.13 * core, 0.141 * core, PI / 4)
                } else {
                    val shard = index - 7
                    val y = if (shard == 0) 0.058 else -0.028
                    val material = when (shard) { -1 -> Material.CALCITE; 0 -> Material.DIORITE; else -> Material.SMOOTH_QUARTZ }
                    storyPart(material, flight.x + shard * (0.069 + 0.16 * split),
                        flight.y + y + 0.15 * sin(PI * split) - (0.039 + y) * split, flight.z + 0.041 * shard,
                        0.145 * seen, 0.133 * seen, 0.163 * seen, 0.24 + 0.4 * comet + shard * 0.91 * split,
                        0.26 * shard + 0.48 * comet)
                }
            }
            in 11..52 -> {
                val n = (index - 11) % tree.size
                val b = tree[n]
                if (index < 32) beam(if (n < 4) Material.STRIPPED_MANGROVE_WOOD else Material.STRIPPED_SPRUCE_WOOD,
                    b.a, b.b, b.width)
                else {
                    val head = storyBeat(t, b.at, b.at + 0.046)
                    val drain = if (n < 4) b.at + 0.138 else 0.741 + (b.at - 0.451) * 0.52
                    val tail = storyBeat(t, drain, drain + 0.071)
                    val a = along(b.a, b.b, tail)
                    val end = along(b.a, b.b, head)
                    val visible = storyBeat(head - tail, 0.0, 0.18)
                    val lip = b.width * 0.28
                    beam(b.colour, point(a.x, a.y, a.z + lip), point(end.x, end.y, end.z + lip),
                        (b.width * 1.08 + 0.014) * visible)
                }
            }
            in 53..58 -> {
                val n = index - 53
                val b = tree[fruit[n]]
                val bloom = storyBeat(t, b.at + 0.038, b.at + 0.091) *
                    (1.0 - storyBeat(t, 0.781 + 0.013 * n, 0.852 + 0.013 * n))
                val s = (0.235 + (n % 3) * 0.032) * bloom
                val material = when (n % 3) {
                    0 -> Material.AMETHYST_CLUSTER
                    1 -> Material.BRAIN_CORAL_FAN
                    else -> Material.TUBE_CORAL_FAN
                }
                storyPart(material, b.b.x, b.b.y + 0.026, b.b.z + 0.052, s, s * 1.08, s * 1.52, 0.29 * n)
            }
            59 -> storyPart(Material.COARSE_DIRT, -0.005, -0.562, -0.008, 0.72, 0.228, 0.476)
            else -> storyPart(Material.DIRT, 0.672, -0.535, 0.0, 0.816, 0.213, 0.452)
        }
        val size = storyBeat(t, 0.0, 0.065) * (1.0 - storyBeat(t, 0.938, 1.0))
        return place(local, point(0.0, 0.0, 0.0), size, Quaternionf())
    }
}
