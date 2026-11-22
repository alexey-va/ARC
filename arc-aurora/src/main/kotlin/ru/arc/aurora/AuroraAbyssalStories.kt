package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraAbyssalStories : AuroraStoryParts {
    private const val WING = 16
    private const val HAND = 10
    private const val EYE = 5
    private const val TENTACLE = 9
    private const val TURN = 2 * PI

    override fun partCount(shape: AuroraShape) = when (shape) {
        AuroraShape.CTHULHU -> bust.size + 2 * (WING + HAND + EYE) + beard.size * TENTACLE
        AuroraShape.DAGON -> hide.size + 8 + 6 + mandible.size + whiskers.size * 4 + 32
        else -> error("Not an abyssal god: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (shape) {
        AuroraShape.CTHULHU -> cthulhu(index, t)
        AuroraShape.DAGON -> dagon(index, t)
        else -> error("Not an abyssal god: $shape")
    }

    private fun beam(m: Material, a: AuroraOffset, b: AuroraOffset, h: Double, d: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val run = hypot(dx, dy)
        return storyPart(m, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(run, dz) + h * 0.26, h, d, atan2(dy, dx), atan2(-dz, run))
    }

    private fun rail(m: Material, ax: Double, ay: Double, bx: Double, by: Double, z: Double, h: Double, d: Double) =
        beam(m, AuroraOffset(ax, ay, z), AuroraOffset(bx, by, z), h, d)

    private fun moved(p: AuroraMiniaturePart, x: Double, y: Double, z: Double, roll: Double = 0.0,
        yaw: Double = 0.0, pitch: Double = 0.0): AuroraMiniaturePart {
        val q = Quaternionf().rotateZ(roll.toFloat()).rotateY(yaw.toFloat()).rotateX(pitch.toFloat())
        val v = q.transform(Vector3f(p.center.x.toFloat(), p.center.y.toFloat(), p.center.z.toFloat()))
        return p.copy(center = AuroraOffset(x + v.x, y + v.y, z + v.z), rotation = q.mul(p.rotation))
    }


    private fun v(x: Double, y: Double, z: Double) = AuroraOffset(x, y, z)

    // The mantle grows back and up behind the brow. A slit of sky on each side separates it from the wings.
    private val bust = buildList {
        add(storyPart(Material.WARPED_HYPHAE, 0.0, -0.155, -0.153, 1.064, 0.981, 0.636, pitch = -0.116))
        add(storyPart(Material.DARK_PRISMARINE, 0.0, -0.608, -0.169, 0.716, 0.586, 0.523, roll = -0.065, pitch = 0.116))
        add(storyPart(Material.DARK_PRISMARINE, 0.0, -0.895, -0.147, 0.345, 0.345, 0.351, roll = PI / 4, pitch = 0.16))
        for (s in listOf(-1, 1)) {
            add(beam(Material.WARPED_HYPHAE, v(s * 0.204, 0.196, -0.110), v(s * 0.852, 0.016, -0.076), 0.423, 0.559))
            add(beam(Material.CYAN_TERRACOTTA, v(s * 0.137, -0.465, 0.062), v(s * 0.590, 0.003, 0.080), 0.225, 0.342))
            add(beam(Material.WARPED_WART_BLOCK, v(s * 0.300, 0.197, -0.201), v(s * 0.640, 0.151, -0.190), 0.246, 0.338))
        }
        add(storyPart(Material.STRIPPED_WARPED_HYPHAE, 0.0, 0.619, 0.121, 0.760, 0.438, 0.673, pitch = -0.185))
        add(storyPart(Material.OXIDIZED_COPPER, 0.0, 0.856, 0.009, 0.704, 0.332, 0.692, roll = 0.056, pitch = -0.280))
        add(storyPart(Material.WEATHERED_COPPER, -0.022, 1.026, -0.091, 0.418, 0.174, 0.491, roll = 0.144, pitch = -0.337))
        for (s in listOf(-1, 1)) {
            add(beam(Material.OXIDIZED_COPPER, v(s * 0.302, 0.910, -0.032), v(s * 0.404, 0.614, 0.138), 0.162, 0.504))
            add(beam(Material.WARPED_WART_BLOCK, v(s * 0.389, 0.621, 0.219), v(s * 0.339, 0.331, 0.298), 0.195, 0.361))
            add(beam(Material.PRISMARINE, v(s * 0.309, 0.936, 0.280), v(s * 0.107, 0.643, 0.524), 0.105, 0.108))
        }
        add(storyPart(Material.STRIPPED_WARPED_HYPHAE, 0.0, 0.350, 0.353, 0.657, 0.298, 0.417, pitch = -0.16))
        add(beam(Material.STRIPPED_WARPED_HYPHAE, v(0.0, 0.630, 0.472), v(-0.012, 0.323, 0.569), 0.131, 0.149))
    }

    private fun watchers(i: Int, t: Double): AuroraMiniaturePart {
        val s = if (i < EYE) - 1 else 1
        val slot = i % EYE
        val open = storyBeat(t, 0.065, 0.282) * (1 - storyBeat(t, 0.842, 0.984))
        val track = -0.050 + 0.100 * storyBeat(t, 0.225, 0.452) - 0.100 * storyBeat(t, 0.716, 0.867)
        val x = s * 0.237
        val angle = s * 0.342
        return when (slot) {
            0 -> storyPart(Material.BLACKSTONE, x, 0.531, 0.475, 0.324, 0.196, 0.118, angle)
            1 -> storyPart(Material.HONEYCOMB_BLOCK, x, 0.528, 0.534, 0.276, 0.042 + 0.083 * open, 0.040, angle)
            2 -> storyPart(Material.OCHRE_FROGLIGHT, x + track, 0.525 + s * track * 0.28, 0.558,
                0.100, 0.040 + 0.089 * open, 0.028, -0.094 * s)
            3 -> storyPart(Material.BLACK_CONCRETE, x + track, 0.525 + s * track * 0.28, 0.575,
                0.037, 0.034 + 0.087 * open, 0.018, -0.094 * s)
            else -> rail(Material.STRIPPED_WARPED_HYPHAE, s * 0.072, 0.526 + 0.035 * open,
                s * 0.408, 0.617 + 0.052 * open, 0.523, 0.093, 0.157)
        }
    }

    private val membraneTop = doubleArrayOf(0.541, 0.917, 1.203, 1.296, 1.271, 1.214, 1.128, 1.017, 0.862)
    private val membraneHem = doubleArrayOf(0.074, -0.037, 0.203, 0.337, 0.161, -0.145, 0.274, 0.423, 0.683)

    private fun wing(i: Int, t: Double): AuroraMiniaturePart {
        val s = if (i < WING) - 1 else 1
        val k = i % WING
        val open = storyBeat(t, 0.100 + (s + 1) * 0.014, 0.461 + (s + 1) * 0.010) *
            (1 - storyBeat(t, 0.767, 0.974))
        val flex = 0.035 * sin(TURN * (t + s * 0.04)) * open
        val spread = 0.48 + 0.485 * open
        fun p(x: Double, y: Double, relief: Double = 0.0) = v(s * (0.34 + (x - 0.34) * spread),
            0.134 + (y - 0.134) * (0.54 + 0.46 * open) + (x - 0.34) * flex,
            -0.283 - (x - 0.34) * (0.16 + 0.37 * (1 - open)) - 0.057 * y + relief)
        if (k < membraneTop.size) {
            val x = 0.478 + k * 0.142
            return beam(if (k == 2 || k == 6) Material.STRIPPED_CRIMSON_HYPHAE else Material.PURPLE_TERRACOTTA,
                p(x, membraneHem[k]), p(x, membraneTop[k]), 0.149 * spread, 0.062)
        }
        val wrist = p(0.875, 1.329, 0.049)
        return when (k) {
            9 -> beam(Material.WARPED_HYPHAE, p(0.349, 0.131, 0.040), p(0.668, 0.945, 0.048), 0.150, 0.175)
            10 -> beam(Material.OXIDIZED_COPPER, p(0.668, 0.945, 0.048), wrist, 0.121, 0.132)
            11 -> beam(Material.STRIPPED_WARPED_HYPHAE, wrist, p(1.301, 1.191, 0.042), 0.121, 0.128)
            12 -> beam(Material.OXIDIZED_COPPER, p(1.301, 1.191, 0.042), p(1.648, 0.807, 0.047), 0.069, 0.089)
            13 -> beam(Material.CYAN_TERRACOTTA, wrist, p(1.195, -0.163, 0.050), 0.064, 0.078)
            14 -> beam(Material.CYAN_TERRACOTTA, wrist, p(1.515, 0.371, 0.045), 0.056, 0.076)
            else -> beam(Material.DRIPSTONE_BLOCK, wrist, p(0.849, 1.486, 0.059), 0.073, 0.082)
        }
    }

    private fun hand(i: Int, t: Double): AuroraMiniaturePart {
        val s = if (i < HAND) - 1 else 1
        val k = i % HAND
        val delay = (s + 1) * 0.052
        val strike = storyBeat(t, 0.344 + delay, 0.532 + delay) * (1 - storyBeat(t, 0.701, 0.905))
        val grip = storyBeat(t, 0.458 + delay, 0.616 + delay) * (1 - storyBeat(t, 0.763, 0.917))
        fun p(x: Double, y: Double, z: Double) = v(s * x, y, z)
        val elbow = p(0.924 + 0.116 * strike, -0.288 + 0.110 * strike, -0.059 + 0.131 * strike)
        val wrist = p(1.065 + 0.072 * strike, -0.585 + 0.325 * strike, 0.162 + 0.459 * strike)
        val palm = p(1.062 + 0.078 * strike, -0.727 + 0.344 * strike, 0.259 + 0.552 * strike)
        if (k == 0) return beam(Material.WARPED_HYPHAE, p(0.611, 0.098, -0.071), elbow, 0.271, 0.330)
        if (k == 1) return beam(Material.OXIDIZED_COPPER, elbow, wrist, 0.224, 0.250)
        if (k == 2) return beam(Material.WARPED_WART_BLOCK, wrist, palm, 0.215, 0.230)
        if (k == 3) return beam(Material.WEATHERED_COPPER, elbow,
            p(1.172 + 0.118 * strike, -0.176 + 0.058 * strike, -0.026), 0.095, 0.128)
        val finger = (k - 4) / 2
        val offset = (finger - 1) * 0.091
        val a = v(palm.x + s * offset, palm.y + abs(offset) * 0.26, palm.z)
        val b = v(a.x + s * (0.009 + 0.60 * offset), a.y - 0.176 + grip * 0.049,
            a.z + 0.038 + 0.129 * grip)
        val tip = v(b.x + s * (-0.052 + 0.46 * offset - 0.050 * grip),
            b.y - 0.138 + grip * 0.135 + abs(offset) * 0.33, b.z + 0.050 + 0.062 * grip)
        return if (k % 2 == 0) beam(Material.STRIPPED_WARPED_HYPHAE, a, b, 0.085, 0.102)
            else beam(Material.DRIPSTONE_BLOCK, b, tip, 0.055, 0.061)
    }

    private val beard = listOf(
        listOf(
            v(-0.328, 0.350, 0.379), v(-0.554, 0.183, 0.473),
            v(-0.652, -0.052, 0.510), v(-0.753, -0.313, 0.543),
            v(-0.866, -0.218, 0.579), v(-0.845, -0.059, 0.603),
        ),
        listOf(
            v(-0.242, 0.300, 0.451), v(-0.377, 0.021, 0.545),
            v(-0.423, -0.316, 0.602), v(-0.566, -0.520, 0.626),
            v(-0.647, -0.801, 0.633), v(-0.521, -0.912, 0.628),
        ),
        listOf(
            v(-0.140, 0.283, 0.502), v(-0.201, -0.041, 0.619),
            v(-0.154, -0.369, 0.658), v(-0.287, -0.683, 0.701),
            v(-0.317, -1.013, 0.738), v(-0.187, -1.186, 0.757),
        ),
        listOf(
            v(0.001, 0.278, 0.522), v(0.054, -0.078, 0.659),
            v(0.036, -0.463, 0.742), v(-0.010, -0.777, 0.750),
            v(0.054, -1.144, 0.759), v(0.201, -1.311, 0.785),
        ),
        listOf(
            v(0.140, 0.285, 0.508), v(0.259, -0.041, 0.599),
            v(0.310, -0.357, 0.689), v(0.224, -0.630, 0.697),
            v(0.316, -0.885, 0.725), v(0.461, -0.857, 0.764),
        ),
        listOf(
            v(0.231, 0.310, 0.450), v(0.390, 0.032, 0.551),
            v(0.497, -0.145, 0.591), v(0.522, -0.427, 0.653),
            v(0.696, -0.555, 0.628), v(0.786, -0.402, 0.669),
        ),
        listOf(
            v(0.322, 0.354, 0.367), v(0.503, 0.253, 0.426),
            v(0.675, 0.137, 0.412), v(0.786, -0.061, 0.450),
            v(0.900, 0.026, 0.487), v(0.960, 0.153, 0.518),
        ),
    )
    private val tentacleWidths = doubleArrayOf(0.180, 0.156, 0.130, 0.097, 0.047)

    private fun tentacle(i: Int, t: Double): AuroraMiniaturePart {
        val arm = i / TENTACLE
        val slot = i % TENTACLE
        val segment = if (slot < 5) slot else (if (slot <= 6) 1 else 3)
        fun joint(j: Int): AuroraOffset {
            val a = beard[arm][j]
            val u = j / 5.0
            val travel = storyBeat(t - 0.151 * u, 0.289, 0.453) * (1 - storyBeat(t - 0.151 * u, 0.591, 0.772))
            val phase = TURN * (2.0 * t - 0.63 * u) + arm * 0.83
            val side = (arm - 3) / 3.0
            return v(a.x + u * (0.078 * sin(phase) + side * 0.132 * travel),
                a.y + u * (0.056 * cos(phase) + 0.137 * travel),
                a.z + u * (0.047 * sin(phase + 0.9) + (0.21 + 0.028 * (arm % 3)) * travel))
        }
        val a = joint(segment)
        val b = joint(segment + 1)
        val w = tentacleWidths[segment] * (if (arm == 3) 1.18 else 1.0)
        if (slot < 5) return beam(if (arm % 2 == 0) Material.OXIDIZED_COPPER else Material.WARPED_WART_BLOCK,
            a, b, w, w * 1.10)
        val inside = (arm - 3) * -0.057 * w
        fun underside(u: Double, z: Double) = v(a.x + (b.x - a.x) * u + inside,
            a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u + z * w)
        if (slot % 2 == 1) return beam(Material.WEATHERED_COPPER, underside(0.075, 0.485), underside(0.836, 0.485),
            w * 0.47, w * 0.235)
        val cup = underside(0.525, 0.620)
        return storyPart(Material.CYAN_TERRACOTTA, cup.x, cup.y, cup.z, 0.268 * w, 0.219 * w,
            0.104 * w, atan2(b.y - a.y, b.x - a.x))
    }

    private fun cthulhu(i: Int, t: Double): AuroraMiniaturePart {
        val wings = bust.size
        val hands = wings + 2 * WING
        val eyes = hands + 2 * HAND
        val tendrils = eyes + 2 * EYE
        val p = when {
            i < wings -> bust[i]
            i < hands -> wing(i - wings, t)
            i < eyes -> hand(i - hands, t)
            i < tendrils -> watchers(i - eyes, t)
            else -> tentacle(i - tendrils, t)
        }
        val emerge = storyBeat(t, 0.032, 0.251) * (1 - storyBeat(t, 0.809, 0.983))
        val turn = storyBeat(t, 0.235, 0.526) * (1 - storyBeat(t, 0.748, 0.958))
        return moved(p, 0.0, -0.173 + 0.215 * emerge, 0.055 * emerge, 0.015 * sin(TURN * t),
            -0.083 + 0.104 * turn, -0.063 + 0.094 * emerge)
    }

    // A hunched, asymmetrical fish torso supports a frontal, bucket-shaped jaw.
    private val hide = buildList {
        add(storyPart(Material.BLUE_TERRACOTTA, -0.202, -0.229, -0.196, 0.887, 0.908, 0.562, -0.211))
        add(storyPart(Material.CYAN_TERRACOTTA, -0.205, -0.556, -0.184, 0.666, 0.620, 0.530, -0.281))
        add(beam(Material.WARPED_HYPHAE, v(-0.507, -0.478, -0.250), v(-0.331, 0.293, -0.179), 0.357, 0.436))
        for (s in listOf(-1, 1)) {
            add(beam(Material.LIGHT_BLUE_TERRACOTTA, v(-0.122 + s * 0.135, 0.181, -0.151),
                v(-0.115 + s * 0.553, -0.066, -0.127), 0.310, 0.428))
            add(beam(Material.DEAD_BUBBLE_CORAL_BLOCK, v(-0.125 + s * 0.403, -0.135, 0.042),
                v(-0.174 + s * 0.126, -0.636, 0.055), 0.170, 0.205))
            add(beam(Material.LIGHT_BLUE_TERRACOTTA, v(-0.202 + s * 0.343, -0.435, 0.069),
                v(-0.259 + s * 0.117, -0.811, 0.059), 0.080, 0.172))
        }
        add(beam(Material.CYAN_TERRACOTTA, v(-0.274, -0.741, -0.146), v(-0.425, -1.039, -0.203), 0.303, 0.421))
        add(beam(Material.WARPED_HYPHAE, v(-0.413, -1.018, -0.204), v(-0.266, -1.208, -0.242), 0.196, 0.298))
        add(beam(Material.BLUE_TERRACOTTA, v(-0.282, -1.183, -0.235), v(0.139, -1.397, -0.269), 0.123, 0.134))
        add(beam(Material.LIGHT_BLUE_TERRACOTTA, v(-0.190, -1.224, -0.213), v(0.049, -1.067, -0.271), 0.116, 0.126))
        // Spines are buried in broad, receding fans and angle away from the uneven crown.
        for ((r, height) in doubleArrayOf(0.309, 0.429, 0.554, 0.602, 0.445, 0.298).withIndex()) {
            val x = -0.594 + 0.162 * r
            val y = 0.346 + 0.106 * minOf(r, 4)
            add(beam(Material.STRIPPED_CRIMSON_HYPHAE, v(x + 0.053, y - 0.016, -0.196), v(x - 0.101, y + height * 0.87, -0.272),
                0.179, 0.104))
            add(beam(Material.LIGHT_BLUE_TERRACOTTA, v(x + 0.005, y - 0.013, -0.160), v(x - 0.198, y + height, -0.254), 0.061, 0.121))
        }
        add(storyPart(Material.DEAD_BRAIN_CORAL_BLOCK, 0.097, 0.501, -0.068, 0.886, 0.611, 0.568, -0.087, pitch = -0.116))
        add(storyPart(Material.LIGHT_BLUE_TERRACOTTA, 0.113, 0.727, -0.012, 0.693, 0.309, 0.524, -0.117))
        add(storyPart(Material.CYAN_TERRACOTTA, 0.148, 0.886, -0.077, 0.453, 0.149, 0.382, -0.160, pitch = -0.212))
        add(beam(Material.WARPED_HYPHAE, v(-0.058, 0.805, 0.134), v(0.228, 0.678, 0.293), 0.172, 0.236))
        for (s in listOf(-1, 1)) {
            fun at(x: Double, y: Double, z: Double) = v(0.148 + s * x, y, z)
            add(beam(Material.DEAD_TUBE_CORAL_BLOCK, at(0.401, 0.582, 0.028), at(0.627, 0.250, -0.032), 0.224, 0.321))
            add(beam(Material.BLUE_TERRACOTTA, at(0.407, 0.566, 0.080), at(0.429, 0.205, 0.214), 0.251, 0.385))
            add(beam(Material.CYAN_TERRACOTTA, at(0.445, 0.410, 0.266), at(0.360, 0.194, 0.507), 0.111, 0.193))
            add(beam(Material.LIGHT_BLUE_TERRACOTTA, at(0.011, 0.615, 0.393), at(0.254, 0.551, 0.495), 0.172, 0.187))
            add(beam(Material.CYAN_TERRACOTTA, at(0.237, 0.557, 0.496), at(0.424, 0.397, 0.437), 0.148, 0.171))
            add(beam(Material.CRIMSON_HYPHAE, at(0.002, 0.537, 0.467), at(0.249, 0.480, 0.549), 0.077, 0.092))
            add(beam(Material.CRIMSON_HYPHAE, at(0.230, 0.484, 0.535), at(0.413, 0.355, 0.500), 0.084, 0.101))
            // Broad blood-dark opercula fan out beyond the eye. Even from below their gill slits cut the outline.
            for (r in 0..2) {
                val y = 0.438 - r * 0.146
                add(beam(Material.STRIPPED_CRIMSON_HYPHAE, at(0.390, y, -0.027), at(0.688 + 0.043 * r, y + 0.113, -0.177),
                    0.124, 0.217))
                add(beam(Material.LIGHT_BLUE_TERRACOTTA, at(0.467, y + 0.046, 0.050), at(0.765 + 0.040 * r, y + 0.197, -0.163),
                    0.064, 0.093))
            }
        }
        // Unequal canine roots carry a second, hooked tip. The gaps let the deep throat show through.
        for ((r, x) in doubleArrayOf(-0.344, -0.244, -0.089, 0.104, 0.248, 0.348).withIndex()) {
            val y = 0.508 - abs(x) * 0.32
            val z = 0.500 + (0.35 - abs(x)) * 0.31
            val height = doubleArrayOf(0.256, 0.158, 0.110, 0.132, 0.183, 0.288)[r]
            val sign = if (x < 0) - 1.0 else 1.0
            val a = v(0.148 + x, y, z)
            val b = v(0.148 + x + sign * 0.019, y - height * 0.63, z + 0.034)
            val tip = v(0.148 + x - sign * 0.067, y - height, z + 0.010)
            add(beam(Material.BONE_BLOCK, a, b, if (r == 0 || r == 5) 0.081 else 0.056, 0.084))
            add(beam(Material.SMOOTH_SANDSTONE, b, tip, if (r == 0 || r == 5) 0.047 else 0.033, 0.050))
        }
    }

    private fun blindEye(i: Int, t: Double): AuroraMiniaturePart {
        val s = if (i < 4) - 1 else 1
        val r = i % 4
        val x = 0.148 + s * 0.445
        val y = 0.579 + s * 0.026
        val stare = storyBeat(t, 0.196, 0.379) * (1 - storyBeat(t, 0.719, 0.935))
        val shade = s * 0.340
        return when (r) {
            0 -> storyPart(Material.BLACKSTONE, x, y, 0.221, 0.248, 0.184, 0.285, shade, s * 0.31)
            1 -> storyPart(Material.SEA_LANTERN, x + s * 0.018, y, 0.357, 0.185, 0.111, 0.097, shade, s * 0.273)
            2 -> storyPart(Material.LIGHT_GRAY_TERRACOTTA, x + s * 0.040 - 0.041 * stare, y - 0.020 * stare, 0.411,
                0.043, 0.073, 0.029, shade - 0.091)
            else -> beam(Material.CYAN_TERRACOTTA, v(x - s * 0.118, y + 0.016, 0.361),
                v(x + s * 0.155, y + 0.129, 0.279), 0.090, 0.171)
        }
    }

    private fun throat(i: Int, open: Double): AuroraMiniaturePart {
        val top = 0.536
        val bottom = 0.093 - 0.536 * open
        val y = (top + bottom) * 0.5
        val h = top - bottom
        val tilt = 0.19 + 0.31 * open
        return when (i) {
            0 -> storyPart(Material.BLACK_TERRACOTTA, 0.148, y, 0.128, 0.490, h * 0.98, 0.104, pitch = tilt)
            1 -> storyPart(Material.BLACK_CONCRETE, 0.146, y + 0.012, 0.172, 0.721, h * 0.74, 0.084, pitch = tilt)
            2, 3 -> {
                val s = if (i == 2) - 1 else 1
                beam(Material.STRIPPED_CRIMSON_HYPHAE, v(0.148 + s * 0.408, top - 0.128, 0.371),
                    v(0.148 + s * 0.262, bottom + 0.060, 0.177), 0.135, 0.144)
            }
            4 -> beam(Material.CRIMSON_HYPHAE, v(0.091, top - 0.058, 0.350), v(0.114, top - 0.163 - 0.078 * open, 0.305),
                0.071, 0.077)
            else -> storyPart(Material.NETHER_WART_BLOCK, 0.132, bottom + h * 0.142, 0.195, 0.278, h * 0.136, 0.109, -0.109, pitch = tilt)
        }
    }

    // Hinged in depth: the foremost lip swings below the throat, and the tusks rotate with the mandible.
    private val mandible = buildList {
        for (s in listOf(-1, 1)) {
            add(beam(Material.BLUE_TERRACOTTA, v(s * 0.362, 0.044, -0.023), v(s * 0.408, -0.046, 0.311), 0.161, 0.183))
            add(beam(Material.LIGHT_BLUE_TERRACOTTA, v(s * 0.411, -0.044, 0.286), v(s * 0.239, -0.102, 0.610), 0.154, 0.161))
            add(beam(Material.MANGROVE_PLANKS, v(s * 0.358, 0.012, 0.347), v(s * 0.182, -0.032, 0.620), 0.070, 0.101))
        }
        add(storyPart(Material.CYAN_TERRACOTTA, 0.0, -0.120, 0.587, 0.547, 0.141, 0.200, 0.037))
        add(storyPart(Material.STRIPPED_CRIMSON_HYPHAE, 0.0, -0.042, 0.611, 0.464, 0.063, 0.151, 0.034))
        for ((r, x) in doubleArrayOf(-0.286, -0.114, 0.104, 0.277).withIndex()) {
            val a = v(x, -0.016, 0.588 - abs(x) * 0.096)
            val h = if (r == 0 || r == 3) 0.217 else 0.125
            val b = v(x * 1.08, h * 0.60, 0.623)
            val c = v(x * 0.87, h, 0.578)
            add(beam(Material.BONE_BLOCK, a, b, 0.061, 0.075))
            add(beam(Material.SMOOTH_SANDSTONE, b, c, 0.039, 0.047))
        }
    }

    private val whiskers = listOf(
        listOf(
            v(-0.256, 0.357, 0.431), v(-0.502, 0.339, 0.579),
            v(-0.680, 0.579, 0.513), v(-0.918, 0.610, 0.426),
            v(-1.021, 0.431, 0.473),
        ),
        listOf(
            v(-0.240, 0.245, 0.413), v(-0.484, 0.060, 0.559),
            v(-0.570, -0.214, 0.636), v(-0.761, -0.399, 0.674),
            v(-0.766, -0.612, 0.703),
        ),
        listOf(
            v(0.541, 0.236, 0.427), v(0.731, 0.081, 0.558),
            v(0.743, -0.183, 0.649), v(0.919, -0.375, 0.710),
            v(1.013, -0.255, 0.748),
        ),
        listOf(
            v(0.547, 0.377, 0.425), v(0.796, 0.352, 0.516),
            v(0.987, 0.560, 0.392), v(1.188, 0.658, 0.365),
            v(1.331, 0.541, 0.451),
        ),
    )

    private fun barbel(i: Int, t: Double, open: Double): AuroraMiniaturePart {
        val n = i / 4
        val k = i % 4
        fun joint(j: Int): AuroraOffset {
            val a = whiskers[n][j]
            val u = j / 4.0
            val wave = TURN * (t - u * 0.68) + n * 1.19
            return v(a.x + u * (0.065 * sin(wave) + (n - 1.5) * 0.056 * open),
                a.y + u * (0.086 * cos(wave) - open * 0.079), a.z + u * (0.041 * sin(wave + 0.82) + open * 0.095))
        }
        val w = 0.108 - k * 0.022
        return beam(if (n % 2 == 0) Material.STRIPPED_CRIMSON_HYPHAE else Material.WEATHERED_COPPER, joint(k), joint(k + 1), w, w * 0.93)
    }

    private fun webbedClaw(i: Int, t: Double): AuroraMiniaturePart {
        val large = i < 16
        val k = i % 16
        val reach = storyBeat(t, if (large) 0.194 else 0.376, if (large) 0.493 else 0.580) *
            (1 - storyBeat(t, 0.683, 0.932))
        val close = storyBeat(t, 0.493, 0.650) * (1 - storyBeat(t, 0.717, 0.939))
        val root = if (large) v(-0.499, -0.069, -0.117) else v(0.361, -0.097, -0.152)
        val elbow = if (large) v(-0.756 - 0.104 * reach, -0.518 + 0.159 * reach, -0.039 + 0.099 * reach)
            else v(0.670 + 0.065 * reach, -0.418, 0.009 + 0.044 * reach)
        val palm = if (large) v(-0.937 - 0.137 * reach, -0.344 + 0.219 * reach, 0.213 + 0.546 * reach)
            else v(0.736 + 0.173 * reach, -0.735 + 0.160 * reach, 0.219 + 0.281 * reach)
        val direction = if (large) 1.0 else - 1.0
        val size = if (large) 1.0 else 0.79
        fun point(x: Double, y: Double, z: Double) = v(palm.x + size * x, palm.y + size * direction * y, palm.z + size * z)
        fun knuckle(u: Double) = point(u * 0.185, 0.065 - abs(u) * 0.024, 0.048)
        fun finger(u: Double) = point(u * (0.279 + 0.046 * reach) - 0.062 * direction,
            0.256 + (1 - abs(u)) * 0.082 - close * 0.043, 0.117 + 0.132 * close)
        fun nail(u: Double) = point(u * (0.358 + 0.034 * reach - 0.074 * close) - 0.165 * direction, 0.432 + (1 - abs(u)) * 0.114 - close * 0.252,
            0.132 + 0.294 * close)
        if (k == 0) return beam(Material.BLUE_TERRACOTTA, root, elbow, 0.252 * size, 0.299 * size)
        if (k == 1) return beam(Material.DEAD_TUBE_CORAL_BLOCK, elbow, palm, 0.206 * size, 0.269 * size)
        if (k == 2) return storyPart(Material.LIGHT_BLUE_TERRACOTTA, palm.x, palm.y, palm.z,
            0.428 * size, 0.225 * size, 0.187 * size, 0.128 * direction)
        if (k == 3) return beam(Material.STRIPPED_CRIMSON_HYPHAE, elbow,
            v(elbow.x - 0.165 * direction, elbow.y - 0.169, elbow.z - 0.036), 0.100 * size, 0.120 * size)
        if (k < 12) {
            val u = (k - 4) / 2 * (2.0 / 3.0) - 1.0
            return if (k % 2 == 0) beam(Material.CYAN_TERRACOTTA, knuckle(u), finger(u), 0.095 * size, 0.091 * size)
            else beam(if (abs(u) < 0.8) Material.DRIPSTONE_BLOCK else Material.SMOOTH_SANDSTONE, finger(u), nail(u), 0.056 * size, 0.057 * size)
        }
        if (k < 15) {
            val u = (k - 13) * (2.0 / 3.0)
            val f = finger(u)
            return beam(Material.STRIPPED_CRIMSON_HYPHAE, knuckle(u), v(f.x, f.y - 0.040 * direction, f.z - 0.047), 0.201 * size, 0.084 * size)
        }
        return beam(Material.DRIPSTONE_BLOCK, point(-0.159, -0.018, 0.054), point(-0.352, 0.079, 0.156), 0.087 * size, 0.094 * size)
    }

    private fun dagon(i: Int, t: Double): AuroraMiniaturePart {
        val eyes = hide.size
        val mouth = eyes + 8
        val hinge = mouth + 6
        val whisker = hinge + mandible.size
        val hand = whisker + whiskers.size * 4
        val open = storyBeat(t, 0.262, 0.514) * (1 - storyBeat(t, 0.657, 0.815))
        val rise = storyBeat(t, 0.071, 0.373) * (1 - storyBeat(t, 0.716, 0.956))
        val p = when {
            i < eyes -> hide[i]
            i < mouth -> blindEye(i - eyes, t)
            i < hinge -> throat(i - mouth, open)
            i < whisker -> moved(mandible[i - hinge], 0.148, 0.280 - 0.106 * open, 0.008, 0.012, 0.0, 0.108 + 0.956 * open)
            i < hand -> barbel(i - whisker, t, open)
            else -> webbedClaw(i - hand, t)
        }
        return moved(p, -0.107 + 0.149 * rise, -0.249 + 0.488 * rise, 0.082 + 0.217 * rise,
            0.062 - 0.141 * rise + 0.014 * sin(TURN * t), 0.216 - 0.185 * rise, 0.179 - 0.385 * rise + 0.110 * open)
    }
}
