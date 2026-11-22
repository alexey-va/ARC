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
    private const val ARM_PARTS = 15
    private const val FLIPPER_PARTS = 5
    private const val BREAKER_PARTS = 10

    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.KRAKEN_DRAG -> ship.size + arms.size * ARM_PARTS + 6
        AuroraShape.RLYEH_RISING -> districts.size * districts.first().size + gate.size + door.size
        AuroraShape.DAGON_TIDE -> leviathan.size + jaw.size + fins.size * FLIPPER_PARTS + 2 * BREAKER_PARTS
        else -> error("Not an abyssal story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val p = when (shape) {
            AuroraShape.KRAKEN_DRAG -> kraken(index, t)
            AuroraShape.RLYEH_RISING -> city(index, t)
            AuroraShape.DAGON_TIDE -> dagon(index, t)
            else -> error("Not an abyssal story: $shape")
        }
        val size = storyBeat(t, 0.0, 0.074) * (1 - storyBeat(t, 0.925, 1.0))
        if (size == 1.0) return p
        val c = p.center
        return p.copy(center = AuroraOffset(c.x * size, c.y * size, c.z * size), scale = Vector3f(p.scale).mul(size.toFloat()))
    }

    private fun beam(m: Material, a: AuroraOffset, b: AuroraOffset, h: Double, d: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val run = hypot(dx, dy)
        return storyPart(m, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(run, dz) + h * 0.16, h, d, atan2(dy, dx), atan2(-dz, run))
    }

    private fun rail(m: Material, ax: Double, ay: Double, bx: Double, by: Double, z: Double, h: Double, d: Double) =
        beam(m, AuroraOffset(ax, ay, z), AuroraOffset(bx, by, z), h, d)

    private fun moved(p: AuroraMiniaturePart, x: Double, y: Double, z: Double, roll: Double = 0.0,
        yaw: Double = 0.0, pitch: Double = 0.0): AuroraMiniaturePart {
        val q = Quaternionf().rotateZ(roll.toFloat()).rotateY(yaw.toFloat()).rotateX(pitch.toFloat())
        val v = q.transform(Vector3f(p.center.x.toFloat(), p.center.y.toFloat(), p.center.z.toFloat()))
        return p.copy(center = AuroraOffset(x + v.x, y + v.y, z + v.z), rotation = q.mul(p.rotation))
    }

    /** Keep the top of each box at its moving height while its submerged portion disappears. */
    private fun aboveWater(p: AuroraMiniaturePart, level: Double): AuroraMiniaturePart {
        val q = p.rotation
        val s = p.scale
        val half = maxOf(0.092, 0.5 * (abs(2 * (q.x * q.y + q.z * q.w)) * s.x +
            abs(1 - 2 * (q.x * q.x + q.z * q.z)) * s.y + abs(2 * (q.y * q.z - q.x * q.w)) * s.z))
        val y = p.center.y
        if (y - half >= level) return p
        val fraction = if (y + half <= level) 0.0 else (y + half - level) / (2 * half)
        return p.copy(center = p.center.copy(y = if (fraction == 0.0) level else y + half * (1 - fraction)),
            scale = Vector3f(s).mul(fraction.toFloat()))
    }

    private val ship = buildList {
        add(storyPart(Material.DARK_OAK_PLANKS, -0.025, -0.120, 0.0, 1.02, 0.121, 0.225))
        add(storyPart(Material.SPRUCE_PLANKS, -0.027, -0.040, 0.0, 1.25, 0.168, 0.279))
        add(storyPart(Material.YELLOW_TERRACOTTA, -0.03, 0.012, 0.0, 1.29, 0.071, 0.292))
        add(rail(Material.SPRUCE_PLANKS, -0.49, -0.085, -0.73, 0.103, 0.0, 0.130, 0.265))
        add(rail(Material.SPRUCE_PLANKS, 0.46, -0.083, 0.79, 0.104, 0.0, 0.129, 0.251))
        add(rail(Material.OAK_PLANKS, 0.56, 0.060, 0.936, 0.230, 0.0, 0.047, 0.066))
        add(storyPart(Material.DARK_OAK_PLANKS, -0.162, 0.483, -0.070, 0.055, 0.982, 0.068))
        add(storyPart(Material.SPRUCE_PLANKS, -0.205, 0.802, 0.027, 0.777, 0.050, 0.064, 0.045))
        add(storyPart(Material.SMOOTH_SANDSTONE, -0.205, 0.731, 0.044, 0.650, 0.155, 0.090, 0.045))
        add(storyPart(Material.WHITE_WOOL, -0.192, 0.570, 0.069, 0.586, 0.203, 0.118, 0.038))
        add(storyPart(Material.SMOOTH_SANDSTONE, -0.178, 0.400, 0.061, 0.475, 0.157, 0.097, 0.022))
        // Four stepped courses form the triangular headsail. The slit between the two sails stays open.
        for (r in 0..3) {
            val width = 0.498 - 0.112 * r
            add(storyPart(Material.BONE_BLOCK, 0.177 + width / 2 - 0.057 * r, 0.245 + 0.114 * r,
                0.006, width, 0.119, 0.065, 0.045))
        }
        add(rail(Material.DARK_OAK_PLANKS, -0.155, 0.901, 0.877, 0.190, -0.085, 0.025, 0.033))
        add(rail(Material.DARK_OAK_PLANKS, -0.179, 0.869, -0.675, 0.091, -0.106, 0.026, 0.035))
        add(storyPart(Material.OAK_PLANKS, -0.545, 0.077, 0.0, 0.271, 0.136, 0.25))
        add(storyPart(Material.RED_WOOL, -0.284, 0.936, -0.067, 0.201, 0.099, 0.038, 0.045))
        add(storyPart(Material.RED_CONCRETE, -0.419, 0.941, -0.064, 0.087, 0.054, 0.04, -0.07))
        for (x in listOf(-0.31, -0.038, 0.232)) {
            add(storyPart(Material.BLACK_TERRACOTTA, x, 0.009, 0.0, 0.073, 0.061, 0.319))
        }
    }

    // Seven joints describe each closed curl. Their different depths put one arm around the front of the hull.
    private val arms = listOf(
        listOf(AuroraOffset(-0.72, -0.57, -0.06), AuroraOffset(-1.00, -0.33, -0.06), AuroraOffset(-1.06, 0.058, -0.03),
            AuroraOffset(-0.96, 0.380, 0.00), AuroraOffset(-0.72, 0.465, 0.080), AuroraOffset(-0.54, 0.305, 0.160), AuroraOffset(-0.55, 0.105, 0.190)),
        listOf(AuroraOffset(0.73, -0.57, -0.13), AuroraOffset(1.063, -0.27, -0.16), AuroraOffset(1.123, 0.200, -0.160),
            AuroraOffset(0.98, 0.600, -0.14), AuroraOffset(0.73, 0.691, -0.063), AuroraOffset(0.552, 0.506, 0.036), AuroraOffset(0.578, 0.235, 0.106)),
        listOf(AuroraOffset(-0.365, -0.57, 0.164), AuroraOffset(-0.024, -0.399, 0.255), AuroraOffset(0.264, -0.265, 0.329),
            AuroraOffset(0.431, -0.020, 0.303), AuroraOffset(0.341, 0.170, 0.240), AuroraOffset(0.134, 0.211, 0.206), AuroraOffset(0.035, 0.078, 0.119)),
    )

    private fun kraken(i: Int, t: Double): AuroraMiniaturePart {
        val grip = storyBeat(t, 0.365, 0.555)
        val sink = storyBeat(t, 0.605, 0.919)
        val roll = 0.047 * sin(4 * PI * t + 0.3) - 0.36 * grip - 0.19 * sink
        val heave = -0.219 + 0.035 * sin(4 * PI * t) - 1.44 * sink
        val drift = -0.12 + 0.19 * storyBeat(t, 0.0, 0.315) + 0.133 * sink
        val water = -0.357
        if (i < ship.size) return aboveWater(moved(ship[i], drift, heave, 0.0, roll), water)
        val n = i - ship.size
        val waterSlot = n - arms.size * ARM_PARTS
        if (waterSlot >= 0) {
            val lane = waterSlot / 2
            val cap = waterSlot % 2 == 1
            val chop = 0.047 * sin(4 * PI * t + lane * 1.7)
            val slope = (if (lane == 1) 0.100 else -0.100) + chop
            val capX = if (lane == 1) 0.158 else -0.169
            return storyPart(if (cap) Material.PRISMARINE else Material.CYAN_CONCRETE,
                (lane - 1) * 0.72 + (if (cap) capX else 0.0),
                water + chop + (if (cap) slope * capX + 0.012 else -0.055), 0.05 * (lane - 1),
                if (cap) 0.375 else 0.806, if (cap) 0.039 else 0.123, if (cap) 0.390 else 0.382, slope)
        }
        val arm = n / ARM_PARTS
        val slot = n % ARM_PARTS
        val segment = if (slot < 6) slot else 1 + (slot - 6) / 3
        val rise = storyBeat(t, 0.123 + arm * 0.034, 0.410 + arm * 0.032)
        val thickness = (0.288 - 0.0365 * segment) * (if (arm == 2) 0.87 else 1.0)
        fun joint(j: Int): AuroraOffset {
            val a = arms[arm][j]
            val u = j / 6.0
            val spread = sin(PI * u * 0.53)
            val outside = if (arm == 0) -1.0 else 1.0
            val x = a.x + outside * 0.20 * spread * (1 - grip) - outside * 0.08 * grip * u
            val y = a.y - 0.205 * grip * u + (if (arm == 2) 0.150 * spread else 0.0)
            val curlX = drift + cos(roll) * x - sin(roll) * y
            val curlY = heave + sin(roll) * x + cos(roll) * y
            val anchor = arms[arm][0]
            return AuroraOffset(anchor.x + (curlX - anchor.x) * (0.52 + 0.48 * rise),
                water - 0.162 + (curlY - water + 0.162) * rise, a.z)
        }
        val a = joint(segment)
        val b = joint(segment + 1)
        if (slot < 6) return aboveWater(beam(if (arm == 1) Material.PURPLE_CONCRETE else Material.MAGENTA_TERRACOTTA,
            a, b, thickness, thickness * 1.05), water)
        // Two crossed plates leave clipped corners around each dark suction pore on both faces.
        val course = (slot - 6) % 3
        val width = thickness * when (course) {
            0 -> 0.78
            1 -> 0.64
            else -> 0.34
        }
        val height = thickness * when (course) {
            0 -> 0.45
            1 -> 0.64
            else -> 0.29
        }
        return aboveWater(storyPart(if (course == 2) Material.PURPLE_TERRACOTTA else Material.PINK_CONCRETE,
            (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2, width, height,
            thickness * (1.29 + 0.023 * course), atan2(b.y - a.y, b.x - a.x)), water)
    }

    private fun district(side: Int): List<AuroraMiniaturePart> = buildList {
        // One tower leans out under a corbelled crown. The smaller one leans back towards its bridge.
        val tall = if (side < 0) 1.0 else 0.79
        val lean = if (side < 0) 0.15 else 0.21
        fun stone(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double, roll: Double = 0.0) {
            add(storyPart(m, side * x, -0.40 + (y + 0.40) * tall, z, w, h * tall, d, roll * side))
        }
        stone(Material.MOSSY_STONE_BRICKS, 0.647, -0.275, -0.16, 0.464, 0.345, 0.495)
        stone(Material.DARK_PRISMARINE, 0.658, 0.077, -0.16, 0.348, 0.742, 0.387, lean)
        stone(Material.TUFF_BRICKS, 0.604, 0.379, -0.16, 0.443, 0.113, 0.445, 0.072)
        stone(Material.OXIDIZED_CUT_COPPER, 0.564, 0.513, -0.16, 0.298, 0.225, 0.316, 0.077)
        stone(Material.PRISMARINE_BRICKS, 0.557, 0.638, -0.16, 0.373, 0.086, 0.375, 0.068)
        stone(Material.DARK_PRISMARINE, 0.514, 0.749, -0.16, 0.159, 0.202, 0.226, lean)
        stone(Material.WEATHERED_CUT_COPPER, 0.486, 0.861, -0.16, 0.119, 0.100, 0.144, PI / 4)
        for (dx in listOf(-0.048, 0.075)) stone(Material.BLACK_TERRACOTTA, 0.628 + dx, 0.217, -0.16, 0.041, 0.153, 0.433, lean)
        stone(Material.SEA_LANTERN, 0.560, 0.519, -0.16, 0.052, 0.100, 0.336, 0.077)
        stone(Material.MOSSY_COBBLESTONE, 0.913, -0.221, -0.304, 0.288, 0.411, 0.344, -0.17)
        stone(Material.TUFF_BRICKS, 0.961, 0.032, -0.304, 0.224, 0.307, 0.269, -0.17)
        stone(Material.PRISMARINE_BRICKS, 0.983, 0.213, -0.304, 0.282, 0.094, 0.316, -0.24)
        stone(Material.OXIDIZED_CUT_COPPER, 0.994, 0.313, -0.304, 0.140, 0.187, 0.172, -0.20)
        stone(Material.TUFF_BRICKS, 0.828, -0.015, -0.200, 0.274, 0.070, 0.255, -0.51)

        // Each tread has an exposed vertical riser and also projects toward the viewer.
        for (r in 0..5) {
            val top = -0.483 + 0.084 * r
            val foot = -0.608
            val x = side * (0.981 - 0.112 * r)
            val z = 0.344 - 0.038 * r
            add(storyPart(Material.TUFF_BRICKS, x, (foot + top) * 0.5, z, 0.119, top - foot, 0.383, 0.0, side * 0.071))
            add(storyPart(Material.PRISMARINE_BRICKS, x, top + 0.010, z, 0.156, 0.046, 0.437, 0.0, side * 0.071))
        }
    }

    private val districts = listOf(district(-1), district(1))
    private val gate = buildList {
        add(storyPart(Material.PRISMARINE_BRICKS, 0.0, -0.47, 0.0, 1.081, 0.209, 0.569))
        add(storyPart(Material.TUFF_BRICKS, -0.011, -0.342, 0.019, 0.949, 0.092, 0.455))
        add(storyPart(Material.BLACK_CONCRETE, -0.04, 0.058, -0.061, 0.744, 0.866, 0.119, 0.104))
        add(rail(Material.MOSSY_STONE_BRICKS, -0.386, -0.374, -0.482, 0.540, 0.0, 0.136, 0.329))
        add(rail(Material.MOSSY_STONE_BRICKS, 0.373, -0.366, 0.299, 0.590, 0.0, 0.150, 0.334))
        add(rail(Material.PRISMARINE_BRICKS, -0.544, 0.506, 0.395, 0.584, -0.002, 0.153, 0.385))
        add(rail(Material.OXIDIZED_CUT_COPPER, -0.496, 0.626, 0.365, 0.699, -0.003, 0.083, 0.328))
        // A recessed, crooked slit of cold light is uncovered only when the seal lifts.
        add(rail(Material.SEA_LANTERN, -0.102, -0.294, 0.018, -0.010, -0.058, 0.075, 0.148))
        add(rail(Material.PRISMARINE, 0.018, -0.010, -0.071, 0.108, -0.058, 0.068, 0.153))
        add(rail(Material.SEA_LANTERN, -0.071, 0.108, 0.032, 0.328, -0.058, 0.053, 0.149))
    }

    private val door = buildList {
        add(storyPart(Material.OXIDIZED_CUT_COPPER, 0.0, 0.0, 0.0, 0.621, 0.790, 0.197))
        add(storyPart(Material.DARK_PRISMARINE, -0.003, 0.0, 0.0, 0.461, 0.651, 0.211))
        add(storyPart(Material.PRISMARINE_BRICKS, 0.0, 0.281, 0.0, 0.474, 0.083, 0.239))
        add(storyPart(Material.PRISMARINE_BRICKS, 0.0, -0.280, 0.0, 0.474, 0.076, 0.240))
        add(storyPart(Material.WEATHERED_COPPER, 0.0, 0.009, 0.0, 0.252, 0.252, 0.245, PI / 4))
        add(storyPart(Material.BLACK_TERRACOTTA, 0.0, 0.008, 0.0, 0.180, 0.180, 0.260, PI / 4))
        add(storyPart(Material.SEA_LANTERN, 0.0, 0.009, 0.0, 0.041, 0.164, 0.272, -0.117))
    }

    private fun city(i: Int, t: Double): AuroraMiniaturePart {
        val rise = storyBeat(t, 0.018, 0.337) * (1 - storyBeat(t, 0.829, 0.991))
        val split = storyBeat(t, 0.245, 0.537) * (1 - storyBeat(t, 0.805, 0.962))
        val open = storyBeat(t, 0.414, 0.668) * (1 - storyBeat(t, 0.784, 0.925))
        val y = -0.080 - 0.987 * (1 - rise)
        val districtSize = districts.first().size
        val fixed = districtSize * 2
        val part = when {
            i < fixed -> {
                val side = if (i < districtSize) -1 else 1
                val district = districts[i / districtSize][i % districtSize]
                moved(district, side * (-0.156 + 0.340 * split), y, -0.035 * split,
                    side * 0.043 * split, side * 0.100 * split)
            }
            i < fixed + gate.size -> moved(gate[i - fixed], 0.0, y, 0.0)
            else -> moved(door[i - fixed - gate.size], -0.046 + 0.170 * open, y + 0.069 + 0.673 * open,
                0.084 + 0.303 * open, 0.106 - 0.637 * open, -0.637 * open)
        }
        return aboveWater(part, -0.685)
    }

    private val leviathan = buildList {
        // The forked tail and broad shoulders sit against the narrow neck, well clear of the open jaws.
        add(rail(Material.DARK_PRISMARINE, -0.707, -0.327, -0.438, -0.203, -0.025, 0.145, 0.159))
        add(rail(Material.WARPED_WART_BLOCK, -0.674, -0.326, -0.923, -0.153, -0.033, 0.104, 0.104))
        add(rail(Material.OXIDIZED_COPPER, -0.674, -0.326, -0.843, -0.546, -0.032, 0.114, 0.109))
        add(rail(Material.DARK_PRISMARINE, -0.503, -0.250, -0.116, 0.152, 0.0, 0.365, 0.307))
        add(rail(Material.OXIDIZED_CUT_COPPER, -0.353, -0.040, -0.085, 0.268, 0.0, 0.328, 0.356))
        add(rail(Material.WEATHERED_COPPER, -0.396, -0.286, 0.041, 0.109, 0.0, 0.118, 0.326))
        add(rail(Material.PRISMARINE_BRICKS, -0.196, 0.197, 0.097, 0.384, 0.0, 0.367, 0.365))
        add(storyPart(Material.WARPED_WART_BLOCK, 0.168, 0.388, 0.0, 0.481, 0.358, 0.378, 0.019))
        add(rail(Material.OXIDIZED_COPPER, 0.071, 0.521, 0.374, 0.551, 0.0, 0.202, 0.378))
        add(rail(Material.PRISMARINE_BRICKS, 0.301, 0.542, 0.610, 0.489, 0.0, 0.202, 0.353))
        add(rail(Material.WEATHERED_COPPER, 0.315, 0.451, 0.632, 0.390, 0.0, 0.074, 0.369))
        add(storyPart(Material.DARK_PRISMARINE, 0.613, 0.479, 0.0, 0.134, 0.136, 0.325, -0.408))
        for (r in 0..3) {
            val x = 0.345 + 0.090 * r
            val y = 0.432 - r * 0.013
            add(rail(Material.BONE_BLOCK, x, y, x + 0.021, y - (if (r == 3 || r == 1) 0.124 else 0.083),
                0.0, if (r == 3) 0.055 else 0.039, 0.178))
        }
        add(storyPart(Material.BLACKSTONE, 0.258, 0.492, 0.0, 0.195, 0.154, 0.405, -0.073))
        add(storyPart(Material.YELLOW_CONCRETE, 0.259, 0.492, 0.0, 0.162, 0.108, 0.421, -0.073))
        add(storyPart(Material.BLACK_CONCRETE, 0.275, 0.490, 0.0, 0.038, 0.100, 0.435, -0.119))
        add(rail(Material.DARK_PRISMARINE, 0.141, 0.600, 0.377, 0.541, 0.0, 0.060, 0.436))
        for (r in 0..2) {
            val x = -0.165 + r * 0.079
            val y = 0.345 + r * 0.032
            add(rail(Material.BLACK_TERRACOTTA, x, y, x + 0.070, y - 0.153, 0.0, 0.031, 0.392))
        }
        // Successive bony rays are sunk into broad scallops of darker fin membrane.
        for ((r, height) in listOf(0.259, 0.343, 0.379, 0.352, 0.267).withIndex()) {
            val x = -0.435 + 0.156 * r
            val y = if (r < 3) 0.135 + 0.129 * r else 0.465 + 0.054 * (r - 3)
            add(rail(Material.WARPED_WART_BLOCK, x + 0.089, y + 0.003, x - 0.091, y + height * 0.84,
                -0.028, 0.177, 0.120))
            add(rail(Material.PRISMARINE, x + 0.009, y + 0.012, x - 0.156, y + height,
                -0.028, 0.066, 0.144))
        }
        // A single luminous lure bends forward of the serrated dorsal sail.
        add(rail(Material.DARK_PRISMARINE, 0.206, 0.574, 0.336, 0.846, -0.019, 0.047, 0.058))
        add(rail(Material.OXIDIZED_COPPER, 0.332, 0.844, 0.512, 0.884, -0.019, 0.039, 0.048))
        add(rail(Material.OXIDIZED_COPPER, 0.508, 0.882, 0.595, 0.763, -0.019, 0.033, 0.047))
        add(storyPart(Material.OCHRE_FROGLIGHT, 0.596, 0.743, -0.019, 0.099, 0.118, 0.116, -0.234))
    }

    // The lower lip and its teeth share the hinge just behind the eye.
    private val jaw = buildList {
        add(rail(Material.DARK_PRISMARINE, -0.019, -0.060, 0.274, -0.067, 0.0, 0.169, 0.318))
        add(rail(Material.WEATHERED_COPPER, 0.211, -0.077, 0.561, -0.028, 0.0, 0.129, 0.289))
        add(rail(Material.PINK_TERRACOTTA, 0.119, 0.018, 0.575, 0.031, 0.0, 0.046, 0.302))
        add(rail(Material.OXIDIZED_COPPER, 0.500, -0.054, 0.609, 0.017, 0.0, 0.083, 0.272))
        for (r in 0..2) {
            val x = 0.226 + r * 0.137
            add(rail(Material.BONE_BLOCK, x, 0.029, x + 0.022, if (r == 1) 0.130 else 0.096,
                0.0, 0.040, 0.156))
        }
    }

    private val fins = listOf(
        // Far flipper lifted broadside against the sky, near flipper reaching forward below the gills.
        listOf(rail(Material.DARK_PRISMARINE, 0.0, 0.0, -0.269, -0.071, -0.099, 0.159, 0.147),
            rail(Material.WARPED_WART_BLOCK, -0.219, -0.068, -0.452, 0.018, -0.098, 0.173, 0.145),
            rail(Material.OXIDIZED_COPPER, -0.380, -0.005, -0.623, 0.177, -0.100, 0.071, 0.086),
            rail(Material.PRISMARINE, -0.395, -0.026, -0.656, 0.052, -0.097, 0.067, 0.094),
            rail(Material.OXIDIZED_COPPER, -0.361, -0.065, -0.602, -0.090, -0.099, 0.063, 0.088)),
        listOf(rail(Material.DARK_PRISMARINE, 0.0, 0.0, -0.087, -0.268, 0.180, 0.150, 0.142),
            rail(Material.WEATHERED_COPPER, -0.083, -0.240, 0.079, -0.410, 0.188, 0.142, 0.132),
            rail(Material.OXIDIZED_COPPER, 0.043, -0.367, 0.260, -0.405, 0.190, 0.066, 0.087),
            rail(Material.PRISMARINE, 0.039, -0.388, 0.233, -0.509, 0.191, 0.063, 0.091),
            rail(Material.OXIDIZED_COPPER, 0.039, -0.415, 0.146, -0.555, 0.190, 0.060, 0.084)),
    )

    private fun breaker(i: Int, t: Double): AuroraMiniaturePart {
        val side = if (i < BREAKER_PARTS) -1.0 else 1.0
        val r = i % BREAKER_PARTS
        val breach = storyBeat(t, 0.178, 0.438) * (1 - storyBeat(t, 0.655, 0.878))
        val splash = storyBeat(t, 0.662, 0.804) * (1 - storyBeat(t, 0.819, 0.984))
        val surge = breach + splash * 0.78
        val gap = 0.31 * breach + 0.22 * splash
        val bob = 0.029 * sin(t * 5 * PI + side * 1.17)
        val ax = -0.032 + gap
        val bx = 0.333 + gap * 0.73
        val cx = 0.593 + gap * 0.63
        val dx = 0.766 + gap * 0.63
        val ex = 0.908 + gap * 0.55
        val fx = 1.031 + gap * 0.48 - 0.039 * surge
        val ay = -0.550 + bob
        val by = -0.546 + bob + 0.082 * surge
        val cy = -0.501 + bob + 0.253 * surge
        val dy = -0.463 + bob + 0.409 * surge
        val ey = -0.459 + bob + 0.397 * surge
        val fy = -0.497 + bob + 0.210 * surge
        fun crest(m: Material, x0: Double, y0: Double, x1: Double, y1: Double, width: Double, depth: Double) =
            rail(m, side * x0, y0, side * x1, y1, -0.042 * side, width, depth)
        return when (r) {
            0 -> crest(Material.CYAN_CONCRETE, ax, ay - 0.052, ex + 0.009, ay - 0.071, 0.137, 0.390)
            1 -> crest(Material.CYAN_WOOL, bx - 0.034, by - 0.049, dx + 0.034, dy - 0.086, 0.219, 0.354)
            2 -> crest(Material.LIGHT_BLUE_CONCRETE, cx - 0.027, cy, dx + 0.017, dy, 0.164, 0.346)
            3 -> crest(Material.LIGHT_BLUE_CONCRETE, dx - 0.034, dy, ex + 0.008, ey, 0.129, 0.316)
            4 -> crest(Material.LIGHT_BLUE_WOOL, ex - 0.027, ey, fx, fy, 0.117, 0.288)
            5 -> crest(Material.CALCITE, cx + 0.024, cy + 0.060, dx + 0.034, dy + 0.074, 0.059, 0.388)
            6 -> crest(Material.CALCITE, dx + 0.018, dy + 0.073, ex + 0.023, ey + 0.062, 0.071, 0.371)
            7 -> crest(Material.LIGHT_BLUE_CONCRETE, ax + 0.02, ay + 0.010, bx + 0.024, by + 0.035, 0.050, 0.409)
            8 -> {
                val h = 0.081 + 0.260 * surge
                storyPart(Material.CYAN_CONCRETE, side * (cx + 0.048), ay - 0.058 + h / 2, -0.042 * side,
                    0.345, h, 0.336)
            }
            else -> crest(Material.CALCITE, fx - 0.006, fy + 0.046, fx - 0.025 - 0.081 * surge,
                fy - 0.009 - 0.022 * surge, 0.060, 0.294)
        }
    }

    private fun dagon(i: Int, t: Double): AuroraMiniaturePart {
        val hinge = leviathan.size
        val flippers = hinge + jaw.size
        val water = flippers + fins.size * FLIPPER_PARTS
        if (i >= water) return breaker(i - water, t)

        val breach = storyBeat(t, 0.172, 0.482)
        val dive = storyBeat(t, 0.651, 0.895)
        val roar = storyBeat(t, 0.313, 0.505) * (1 - storyBeat(t, 0.618, 0.723))
        val local = when {
            i < hinge -> leviathan[i]
            i < flippers -> moved(jaw[i - hinge], 0.091, 0.275, 0.0, 0.168 - 0.784 * roar)
            else -> {
                val n = i - flippers
                val far = n < FLIPPER_PARTS
                val sweep = storyBeat(t, 0.259, 0.480) - storyBeat(t, 0.632, 0.825)
                moved(fins[n / FLIPPER_PARTS][n % FLIPPER_PARTS], if (far) -0.286 else -0.089, if (far) 0.143 else 0.158, 0.0,
                    if (far) -0.31 * sweep else 0.22 * sweep)
            }
        }
        val surge = moved(local, -0.163 + 0.159 * breach + 0.411 * dive, -1.475 + 1.617 * breach - 1.762 * dive,
            0.041 + 0.061 * breach, 0.197 * (1 - breach) - 0.889 * dive, -0.091 + 0.160 * dive)
        return aboveWater(surge, -0.529)
    }
}
