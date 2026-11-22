package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraForbiddenStories : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private const val ARM_SEGMENTS = 6
    private const val HINGE_X = 0.516
    private const val LOCK_X = -0.299
    private const val LOCK_Y = -0.095
    private const val GATE_YAW = -0.60

    private fun point(x: Double, y: Double, z: Double = 0.0) = AuroraOffset(x, y, z)

    private fun beam(m: Material, a: AuroraOffset, b: AuroraOffset, width: Double, depth: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dy)
        return storyPart(m, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(flat, dz) + width * 0.12, width, depth, atan2(dy, dx), atan2(-dz, flat))
    }

    private fun place(part: AuroraMiniaturePart, facing: Quaternionf, origin: AuroraOffset, size: Double = 1.0): AuroraMiniaturePart {
        val p = part.center
        val offset = facing.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())).mul(size.toFloat())
        return AuroraMiniaturePart(part.material, point(origin.x + offset.x, origin.y + offset.y, origin.z + offset.z),
            Quaternionf(facing).mul(part.rotation), Vector3f(part.scale).mul(size.toFloat()))
    }

    // Each snow shelf exposes a blue fracture face and hanging ice teeth when the two plates separate.
    private val ice = buildList {
        for (side in listOf(-1.0, 1.0)) {
            val level = if (side < 0) 0.0 else -0.087
            add(storyPart(Material.BLUE_ICE, side * 0.83, -0.58 + level, -0.06, 0.88, 0.29, 0.51, side * 0.11, side * 0.10))
            add(storyPart(Material.PACKED_ICE, side * 0.69, -0.46 + level, -0.048, 0.90, 0.17, 0.54, side * 0.092))
            add(storyPart(Material.SNOW_BLOCK, side * 0.83, -0.363 + level, -0.042, 0.68, 0.058, 0.51, side * 0.092))
            add(storyPart(Material.PACKED_ICE, side * 0.35, -0.51 + level, 0.021, 0.31, 0.18, 0.43, side * -0.28))
            add(storyPart(Material.BLUE_ICE, side * 0.52, -0.68 + level, 0.025, 0.175, 0.335, 0.33, side * -0.26))
            val peak = if (side < 0) 0.43 else 0.295
            add(storyPart(Material.PACKED_ICE, side * 1.03, -0.38 + level, -0.17, 0.33, peak, 0.25, side * 0.61))
            add(storyPart(Material.SNOW_BLOCK, side * 0.99, -0.306 + peak * 0.33 + level, -0.16,
                0.164, 0.12, 0.23, side * 0.61))
            add(storyPart(Material.PACKED_ICE, side * 0.75, -0.343 + level, -0.23, 0.235, 0.252, 0.22, side * 0.74))
            add(storyPart(Material.BLUE_ICE, side * 0.43, -0.80 + level, 0.01, 0.089, 0.267, 0.21, side * -0.26))
        }
    }

    // Generated pearl and ink-sac models have a stepped outline and 1/16 native depth.
    // Extruding each lobe keeps its oil-dark underside visible from below and on an oblique pass.
    private val mantle = listOf(
        storyPart(Material.ENDER_PEARL, -0.04, -0.06, -0.030, 0.92, 0.71, 5.0, 0.09),
        storyPart(Material.GLOW_INK_SAC, -0.21, 0.13, 0.064, 0.79, 0.65, 4.35, -0.91),
        storyPart(Material.ENDER_PEARL, 0.13, 0.22, -0.18, 0.61, 0.62, 4.3, 0.34, 0.50),
        storyPart(Material.GLOW_INK_SAC, 0.265, -0.005, 0.083, 0.73, 0.65, 4.65, 1.04, -0.36),
        storyPart(Material.ENDER_PEARL, -0.42, -0.14, 0.024, 0.47, 0.42, 3.15, -0.19),
        storyPart(Material.ENDER_PEARL, 0.035, -0.205, 0.115, 0.57, 0.37, 3.17, -0.54),
    )

    private data class Arm(val root: AuroraOffset, val shoulder: AuroraOffset, val bend: AuroraOffset,
        val tip: AuroraOffset, val girth: Double, val skin: Material)

    private val arms = listOf(
        Arm(point(-0.31, 0.07, -0.02), point(-0.90, 0.54, 0.10), point(-1.21, -0.29, 0.11),
            point(-1.38, 0.35, 0.16), 0.235, Material.DARK_PRISMARINE),
        Arm(point(0.18, 0.18, -0.14), point(0.31, 0.88, -0.20), point(1.00, 0.94, -0.12),
            point(0.79, 0.53, 0.02), 0.214, Material.WARPED_WART_BLOCK),
        Arm(point(0.35, -0.06, 0.05), point(0.80, -0.62, 0.15), point(1.20, 0.42, 0.21),
            point(1.36, -0.026, 0.26), 0.248, Material.DARK_PRISMARINE),
        Arm(point(-0.18, -0.19, 0.07), point(-0.27, -0.71, 0.28), point(-0.57, -0.86, 0.24),
            point(-0.96, -0.60, 0.19), 0.205, Material.WARPED_HYPHAE),
    )

    private data class Eye(val x: Double, val y: Double, val z: Double, val size: Double, val tilt: Double)

    private val eyes = listOf(
        Eye(-0.205, 0.145, 0.256, 0.270, -0.23), Eye(0.188, 0.125, 0.265, 0.194, 0.31),
        Eye(-0.399, -0.01, 0.223, 0.137, 0.35), Eye(0.015, -0.137, 0.269, 0.176, -0.38),
        Eye(0.037, 0.315, 0.159, 0.142, 0.18), Eye(0.407, -0.037, 0.199, 0.136, -0.13),
    )

    private fun armPoint(arm: Arm, u: Double, t: Double, reach: Double, n: Int): AuroraOffset {
        val v = 1.0 - u
        val a = v * v * v
        val b = 3.0 * v * v * u
        val c = 3.0 * v * u * u
        val d = u * u * u
        fun axis(r: Double, s: Double, k: Double, e: Double) = r + (a * r + b * s + c * k + d * e - r) * reach
        val ripple = reach * u * sin(TURN * (2.25 * t - 0.89 * u + n * 0.21))
        return point(axis(arm.root.x, arm.shoulder.x, arm.bend.x, arm.tip.x) + 0.066 * ripple,
            axis(arm.root.y, arm.shoulder.y, arm.bend.y, arm.tip.y) + 0.165 * ripple,
            axis(arm.root.z, arm.shoulder.z, arm.bend.z, arm.tip.z) + 0.09 * reach * u * cos(TURN * (2 * t - u + n * 0.23)))
    }

    private fun shoggoth(index: Int, t: Double): AuroraMiniaturePart {
        val fracture = storyBeat(t, 0.065, 0.385) * (1 - storyBeat(t, 0.74, 0.95))
        if (index < ice.size) {
            val slab = ice[index]
            val side = if (slab.center.x < 0) -1.0 else 1.0
            return place(slab, Quaternionf().rotateZ((side * fracture * 0.064).toFloat()),
                point(side * 0.17 * fracture, -0.045 * fracture, 0.0))
        }
        val rise = storyBeat(t, 0.28, 0.55)
        val escape = storyBeat(t, 0.70, 0.95)
        val breath = sin(TURN * (2 * t - 0.11))
        val bodySize = (0.42 + 0.58 * storyBeat(t, 0.065, 0.46)) * (1 - 0.48 * escape) * (1 - storyBeat(t, 0.87, 0.97))
        val center = point(-0.085 + 0.16 * rise + 0.62 * escape, -0.59 + 0.77 * rise + 0.64 * escape,
            -0.05 + 0.16 * rise - 0.36 * escape)
        val facing = Quaternionf().rotateZ((-0.07 + 0.10 * rise + 0.11 * escape + 0.024 * breath).toFloat())
            .rotateY((-0.16 + 0.34 * rise - 0.69 * escape).toFloat())
        val local = index - ice.size
        val firstEye = mantle.size + arms.size * ARM_SEGMENTS
        val firstChip = firstEye + 2 * eyes.size
        if (local >= firstChip) {
            val k = local - firstChip
            val elapsed = ((t - 0.345 - k * 0.014) / 0.225).coerceIn(0.0, 1.0)
            val visible = if (elapsed > 0.0 && elapsed < 1.0) sin(PI * elapsed) else 0.0
            val side = if (k == 1) 1.0 else -1.0
            return storyPart(if (k == 1) Material.BLUE_ICE else Material.PACKED_ICE,
                side * (0.28 + (0.36 + 0.08 * k) * elapsed), -0.42 + (0.37 + 0.08 * k) * visible - 0.27 * elapsed,
                0.13 + 0.17 * elapsed, 0.082 * visible, 0.164 * visible, 0.108 * visible, side * (0.55 + 1.4 * elapsed))
        }
        val part = when {
            local < mantle.size -> {
                val lobe = mantle[local]
                val rolling = sin(TURN * (2 * t + local * 0.19))
                val swell = 0.040 * rolling
                val squeeze = storyBeat(t, 0.21, 0.35) * (1 - storyBeat(t, 0.37, 0.52))
                lobe.copy(center = point(lobe.center.x * (1 - 0.30 * squeeze), lobe.center.y + swell * 0.5, lobe.center.z),
                    rotation = Quaternionf(lobe.rotation).rotateY((0.265 * rolling).toFloat()),
                    scale = Vector3f(lobe.scale).mul((1 + swell - 0.23 * squeeze).toFloat(),
                        (1 - swell + 0.23 * squeeze).toFloat(), 1f))
            }
            local < firstEye -> {
                val slot = local - mantle.size
                val n = slot / ARM_SEGMENTS
                val k = slot % ARM_SEGMENTS
                val arm = arms[n]
                val reach = 0.20 + 0.80 * storyBeat(t, 0.09 + n * 0.025, 0.43 + n * 0.024) - 0.35 * escape
                val width = arm.girth * (1 - 0.84 * (k + 0.35) / ARM_SEGMENTS) * (0.68 + 0.32 * reach)
                beam(arm.skin, armPoint(arm, k.toDouble() / ARM_SEGMENTS, t, reach, n),
                    armPoint(arm, (k + 1.0) / ARM_SEGMENTS, t, reach, n), width, width * 1.08)
            }
            else -> {
                val k = (local - firstEye) / 2
                val e = eyes[k]
                val blinkAt = 0.54 + 0.024 * k
                val blink = storyBeat(t, blinkAt, blinkAt + 0.018) * (1 - storyBeat(t, blinkAt + 0.018, blinkAt + 0.046))
                val height = 1 - 0.86 * blink
                val drift = 0.017 * sin(TURN * (2 * t + k * 0.23))
                if ((local - firstEye) % 2 == 0) {
                    storyPart(Material.RAW_GOLD, e.x, e.y + drift, e.z, e.size * 1.12, e.size * 0.84 * height,
                        e.size * 2.7, e.tilt)
                } else {
                    storyPart(Material.BLACK_CONCRETE, e.x + 0.008 * breath, e.y + drift, e.z + e.size * 0.097,
                        e.size * 0.183, e.size * 0.497 * height, e.size * 0.075, e.tilt)
                }
            }
        }
        return place(part, facing, center, bodySize)
    }

    private val gate = buildList {
        add(storyPart(Material.DEEPSLATE_TILES, 0.0, -0.80, 0.0, 1.49, 0.14, 0.43))
        add(storyPart(Material.WEATHERED_CUT_COPPER, 0.0, -0.696, 0.0, 1.34, 0.074, 0.36))
        for (s in listOf(-1.0, 1.0)) {
            add(storyPart(Material.CHISELED_TUFF, s * 0.595, -0.080, 0.0, 0.185, 1.16, 0.29))
            add(storyPart(Material.OXIDIZED_CHISELED_COPPER, s * 0.595, -0.535, 0.0, 0.242, 0.22, 0.34))
            add(storyPart(Material.WEATHERED_CUT_COPPER, s * 0.595, 0.492, 0.0, 0.247, 0.115, 0.33))
            add(beam(Material.WEATHERED_CUT_COPPER, point(s * 0.59, 0.53), point(s * 0.42, 0.765), 0.145, 0.278))
            add(beam(Material.WEATHERED_CUT_COPPER, point(s * 0.42, 0.765), point(0.0, 1.005), 0.142, 0.278))
            add(storyPart(Material.EXPOSED_CUT_COPPER, s * 0.595, 0.010, 0.150, 0.043, 0.886, 0.028))
            add(storyPart(Material.CHISELED_COPPER, s * 0.645, 0.608, -0.012, 0.120, 0.120, 0.226, PI / 4))
        }
        add(storyPart(Material.CHISELED_COPPER, 0.0, 1.005, 0.025, 0.175, 0.175, 0.297, PI / 4))
    }

    private val leaf = buildList {
        // Four pieces surround a through-hole. The brass escutcheon and moving bit share its center.
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, -0.45, -0.045, 0.0, 0.124, 1.21, 0.126))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, 0.152, -0.045, 0.0, 0.729, 1.21, 0.126))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, LOCK_X, 0.296, 0.0, 0.18, 0.53, 0.126))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, LOCK_X, -0.442, 0.0, 0.18, 0.418, 0.126))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, 0.0, 0.609, 0.0, 0.83, 0.099, 0.123))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, 0.0, 0.700, 0.0, 0.60, 0.086, 0.119))
        add(storyPart(Material.STRIPPED_CRIMSON_STEM, 0.0, 0.787, 0.0, 0.33, 0.092, 0.116))
        val escutcheon = listOf(-0.067 to 0.133, 0.067 to 0.133, 0.124 to 0.042, 0.056 to -0.032,
            0.102 to -0.144, -0.102 to -0.144, -0.056 to -0.032, -0.124 to 0.042)
        for (i in escutcheon.indices) {
            val (x1, y1) = escutcheon[i]
            val (x2, y2) = escutcheon[(i + 1) % escutcheon.size]
            add(beam(Material.EXPOSED_CUT_COPPER, point(LOCK_X + x1, LOCK_Y + y1, 0.083),
                point(LOCK_X + x2, LOCK_Y + y2, 0.083), 0.039, 0.046))
        }
        for (side in listOf(-1.0, 1.0)) {
            val shoulder = point(0.032 + side * 0.25, 0.325, 0.080)
            add(beam(Material.WEATHERED_CUT_COPPER, shoulder, point(0.032, 0.710, 0.080), 0.044, 0.030))
            add(beam(Material.WEATHERED_CUT_COPPER, shoulder, point(0.032, 0.074, 0.080), 0.040, 0.030))
            add(beam(Material.EXPOSED_CUT_COPPER, point(side * 0.307, -0.313, 0.080),
                point(0.032, -0.578, 0.080), 0.049, 0.034))
        }
    }

    // The bit is at z = 0. The shaft, toothed wards and open bow turn around its insertion axis.
    private val key = buildList {
        add(storyPart(Material.IRON_BLOCK, 0.0, 0.0, 0.292, 0.081, 0.084, 0.584))
        add(storyPart(Material.SMOOTH_QUARTZ, 0.0, -0.049, 0.066, 0.089, 0.174, 0.067))
        add(storyPart(Material.IRON_BLOCK, 0.0, -0.050, 0.174, 0.092, 0.128, 0.070))
        add(storyPart(Material.POLISHED_ANDESITE, 0.0, 0.0, 0.367, 0.119, 0.121, 0.049))
        add(storyPart(Material.SMOOTH_QUARTZ, 0.0, 0.0, 0.545, 0.129, 0.134, 0.064))
        val bow = listOf(0.552 to 0.0, 0.625 to 0.172, 0.747 to 0.223, 0.812 to 0.156,
            0.936 to 0.219, 1.044 to 0.143, 1.106 to 0.0, 1.044 to -0.143, 0.936 to -0.219,
            0.812 to -0.156, 0.747 to -0.223, 0.625 to -0.172)
        for (i in bow.indices) {
            val (z1, y1) = bow[i]
            val (z2, y2) = bow[(i + 1) % bow.size]
            add(beam(Material.IRON_BLOCK, point(0.0, y1, z1), point(0.0, y2, z2), 0.059, 0.067))
        }
    }

    private val moon = buildList {
        // Upturned crescent built from only the lit material. Open sky remains between its horns.
        fun curve(k: Int): AuroraOffset {
            val a = -PI * 0.69 + PI * 0.69 * k / 4
            return point(0.36 * sin(a), -0.34 * cos(a), 0.0)
        }
        for (i in 0 until 8) {
            val w = 0.033 + 0.102 * sin(PI * (i + 0.5) / 8)
            add(beam(Material.PEARLESCENT_FROGLIGHT, curve(i), curve(i + 1), w, 0.12))
        }
        add(storyPart(Material.SNOWBALL, -0.045, 0.062, 0.014, 0.281, 0.281, 1.72))
    }

    private fun silverKey(index: Int, t: Double): AuroraMiniaturePart {
        val insert = storyBeat(t, 0.12, 0.368)
        val turn = storyBeat(t, 0.39, 0.525)
        val withdraw = storyBeat(t, 0.55, 0.70)
        val opening = storyBeat(t, 0.55, 0.782) * (1 - storyBeat(t, 0.895, 0.995))
        val gateFacing = Quaternionf().rotateY(GATE_YAW.toFloat())
        val gateCenter = point(0.36, -0.06, 0.0)
        if (index < gate.size) return place(gate[index], gateFacing, gateCenter)
        var slot = index - gate.size
        if (slot < leaf.size) {
            val panel = leaf[slot]
            val p = panel.center
            val swing = Quaternionf().rotateY((-1.56 * opening).toFloat())
            val offHinge = panel.copy(center = point(p.x - HINGE_X, p.y, p.z))
            return place(place(offHinge, swing, point(HINGE_X, 0.0)), gateFacing, gateCenter)
        }
        slot -= leaf.size
        if (slot < key.size) {
            val keySize = storyBeat(t, 0.015, 0.095) * (1 - storyBeat(t, 0.628, 0.742))
            val keyFacing = Quaternionf().rotateY((-0.55 * (1 - insert) - 0.45 * withdraw).toFloat())
                .rotateZ((-1.45 * turn).toFloat())
            val tip = point(LOCK_X - 0.255 * (1 - insert) - 0.22 * withdraw,
                LOCK_Y + 0.10 * (1 - insert) + 0.116 * withdraw, 0.65 - 0.925 * insert + 0.97 * withdraw)
            return place(place(key[slot], keyFacing, tip, keySize), gateFacing, gateCenter)
        }
        slot -= key.size
        val rise = storyBeat(t, 0.63, 0.808)
        val sink = storyBeat(t, 0.884, 0.976)
        val visible = rise * (1 - sink)
        val moonFacing = Quaternionf().rotateZ((0.20 + 0.23 * rise).toFloat())
        val lunar = if (slot == moon.lastIndex) {
            val orb = moon[slot]
            orb.copy(center = point(-0.045 + 0.074 * sin(PI * rise), 0.331 - 0.219 * rise, orb.center.z))
        } else moon[slot]
        return place(place(lunar, moonFacing, point(-0.10, -0.62 + 0.86 * rise - 0.31 * sink, -0.22), visible),
            gateFacing, gateCenter)
    }

    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.SHOGGOTH_ESCAPE -> ice.size + mantle.size + arms.size * ARM_SEGMENTS + eyes.size * 2 + 3
        AuroraShape.SILVER_KEY -> gate.size + leaf.size + key.size + moon.size
        else -> error("Not a forbidden story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (shape) {
        AuroraShape.SHOGGOTH_ESCAPE -> shoggoth(index, t)
        AuroraShape.SILVER_KEY -> silverKey(index, t)
        else -> error("Not a forbidden story: $shape")
    }
}
