package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ju Fufu raises her tiger-paw guards, then catches a kernel tossed by Hu Wei. */
internal object AuroraJuFufu : AuroraStoryParts {
    private val fur = Material.YELLOW_CONCRETE
    private val honey = Material.YELLOW_WOOL
    private val amber = Material.YELLOW_TERRACOTTA
    private val ivory = Material.BONE_BLOCK
    private val skin = Material.WHITE_TERRACOTTA
    private val ink = Material.BLACK_TERRACOTTA
    private val cloth = Material.GRAY_CONCRETE
    private val lapel = Material.CYAN_TERRACOTTA
    private val navy = Material.BLUE_TERRACOTTA
    private val brass = Material.GOLD_BLOCK
    private val silver = Material.LIGHT_GRAY_CONCRETE

    private enum class Bone {
        BODY, CHEST, HEAD, L_UPPER, R_UPPER, L_PAW, R_PAW,
        L_THIGH, R_THIGH, L_CALF, R_CALF, L_SHOE, R_SHOE, POT, LID
    }

    private data class Point(val x: Double, val y: Double, val z: Double = 0.0) {
        fun mix(b: Point, t: Double) = Point(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t)
    }

    private data class Frame(val p: Point, val q: Quaternionf = Quaternionf()) {
        fun point(v: Point): Point {
            val w = q.transform(Vector3f(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
            return Point(p.x + w.x, p.y + w.y, p.z + w.z)
        }
        fun child(x: Double, y: Double, z: Double, roll: Double = 0.0, yaw: Double = 0.0, pitch: Double = 0.0) =
            Frame(point(Point(x, y, z)), Quaternionf(q).rotateZ(roll.toFloat()).rotateY(yaw.toFloat()).rotateX(pitch.toFloat()))
    }

    private data class Piece(val bone: Bone, val pose: AuroraMiniaturePart)

    private fun bar(m: Material, a: Point, b: Point, width: Double, depth: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dy)
        return storyPart(m, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(flat, dz) + 0.20 * width, width, depth, atan2(dy, dx), -atan2(dz, flat))
    }

    private class Model {
        val parts = ArrayList<Piece>()
        var joint = Bone.BODY
        fun box(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
            roll: Double = 0.0, yaw: Double = 0.0, pitch: Double = 0.0, sprite: Boolean = false) {
            val pose = storyPart(m, x, y, z, w, h, d, roll, yaw, pitch)
            parts += Piece(joint, if (sprite) pose.copy(generatedSprite = true) else pose)
        }
        fun line(m: Material, a: Point, b: Point, w: Double, d: Double) {
            parts += Piece(joint, bar(m, a, b, w, d))
        }
    }

    private fun Model.uniform() {
        joint = Bone.BODY
        box(cloth, 0.0, -0.123, -0.008, 0.399, 0.192, 0.252, -0.065)
        box(ink, 0.0, -0.042, -0.004, 0.370, 0.069, 0.262, -0.065)
        box(lapel, 0.023, -0.043, 0.135, 0.076, 0.050, 0.018, -0.05)
        joint = Bone.CHEST
        box(lapel, 0.0, 0.262, -0.008, 0.311, 0.447, 0.195)
        for (s in listOf(-1, 1)) {
            line(lapel, Point(s * 0.162, 0.438, -0.019), Point(s * 0.116, 0.063, -0.013), 0.105, 0.183)
        }
        box(cloth, 0.0, 0.469, -0.018, 0.381, 0.113, 0.192)
        box(lapel, 0.0, 0.519, 0.004, 0.144, 0.093, 0.147)
        box(skin, 0.0, 0.552, 0.001, 0.103, 0.121, 0.121)
        line(ink, Point(-0.058, 0.501, 0.108), Point(0.093, 0.373, 0.108), 0.017, 0.017)
        line(ink, Point(0.093, 0.373, 0.108), Point(0.077, 0.116, 0.106), 0.016, 0.016)
        line(amber, Point(-0.105, 0.487, 0.110), Point(-0.066, 0.290, 0.136), 0.013, 0.019)
        line(amber, Point(0.075, 0.480, 0.106), Point(-0.066, 0.290, 0.136), 0.013, 0.019)
        box(amber, -0.061, 0.259, 0.141, 0.073, 0.122, 0.025, -0.19)
        box(ink, -0.064, 0.265, 0.158, 0.034, 0.051, 0.009, -0.19)
        for (i in 0..2) box(amber, 0.068, 0.322 - i * 0.080, 0.111, 0.033, 0.012, 0.019, 0.03)
    }

    private fun Model.head() {
        joint = Bone.HEAD
        box(skin, 0.0, 0.235, 0.018, 0.301, 0.272, 0.267)
        box(skin, 0.0, 0.102, 0.024, 0.215, 0.115, 0.224)
        for (s in listOf(-1, 1)) box(skin, s * 0.144, 0.196, 0.009, 0.105, 0.181, 0.211, -s * 0.22, s * 0.28)
        box(honey, 0.0, 0.251, -0.081, 0.384, 0.355, 0.232)
        box(fur, 0.0, 0.380, -0.023, 0.330, 0.159, 0.283, -0.05)
        for (s in listOf(-1, 1)) {
            line(honey, Point(s * 0.035, 0.415, 0.027), Point(s * 0.186, 0.297, 0.054), 0.116, 0.236)
            line(fur, Point(s * 0.170, 0.322, 0.098), Point(s * 0.209, 0.099, 0.079), 0.084, 0.132)
            box(Material.MUSIC_DISC_STAL, s * 0.169, 0.437, -0.015, 0.198, 0.210, 1.55,
                -s * 0.36, s * 0.18, sprite = true)
            box(ink, s * 0.168, 0.434, -0.020, 0.083, 0.090, 0.086, -s * 0.26)
            box(Material.FEATHER, s * 0.173, 0.435, 0.050, 0.132, 0.131, 0.48,
                -0.76 - s * 0.21, sprite = true)
            line(honey, Point(s * 0.127, 0.361, 0.038), Point(s * 0.205, 0.393, 0.040), 0.028, 0.052)
            box(Material.SMOOTH_QUARTZ, s * 0.100, 0.235, 0.158, 0.111, 0.077, 0.018, s * 0.07)
            box(Material.LIME_TERRACOTTA, s * 0.097, 0.235, 0.172, 0.062, 0.078, 0.018)
            box(Material.LIME_CONCRETE, s * 0.095, 0.221, 0.182, 0.040, 0.040, 0.013)
            box(Material.BLACK_TERRACOTTA, s * 0.101, 0.244, 0.185, 0.018, 0.046, 0.012)
            box(honey, s * 0.092, 0.240, 0.194, 0.020, 0.020, 0.009, PI / 4)
            box(Material.SMOOTH_QUARTZ, s * 0.105 - 0.017, 0.255, 0.195, 0.018, 0.018, 0.009)
            line(ink, Point(s * 0.046, 0.265, 0.172), Point(s * 0.107, 0.277, 0.176), 0.013, 0.014)
            line(ink, Point(s * 0.107, 0.277, 0.176), Point(s * 0.164, 0.265, 0.161), 0.018, 0.017)
        }
        line(fur, Point(-0.043, 0.428, 0.133), Point(-0.093, 0.287, 0.161), 0.090, 0.064)
        line(honey, Point(-0.005, 0.424, 0.145), Point(0.013, 0.287, 0.165), 0.097, 0.061)
        line(fur, Point(0.059, 0.417, 0.130), Point(0.112, 0.287, 0.149), 0.083, 0.064)
        box(Material.PINK_TERRACOTTA, 0.0, 0.134, 0.144, 0.052, 0.042, 0.023, 0.05)
        for (s in listOf(-1, 1)) line(Material.PINK_TERRACOTTA, Point(s * 0.014, 0.126, 0.145),
            Point(s * 0.042, 0.152 + s * 0.003, 0.141), 0.023, 0.020)
        box(Material.SMOOTH_QUARTZ, -0.022, 0.151, 0.157, 0.018, 0.016, 0.009, PI / 4)
        box(skin, 0.0, 0.180, 0.158, 0.030, 0.030, 0.035, -0.15)
    }

    private fun Model.mane() {
        joint = Bone.HEAD
        for (s in listOf(-1, 1)) {
            val k = if (s > 0) 0.037 else 0.0
            line(ivory, Point(s * 0.127, 0.115, -0.129), Point(s * 0.252, -0.170, -0.141), 0.188, 0.148)
            line(ivory, Point(s * 0.252, -0.170, -0.141), Point(s * (0.466 + k), -0.326, -0.143), 0.160, 0.131)
            line(ivory, Point(s * (0.466 + k), -0.326, -0.143), Point(s * (0.609 + k), -0.265, -0.138), 0.079, 0.087)
            line(ivory, Point(s * (0.510 + k), -0.312, -0.138), Point(s * (0.666 + k), -0.213, -0.132), 0.050, 0.060)
            line(fur, Point(s * 0.149, 0.269, -0.109), Point(s * 0.229, -0.071, -0.085), 0.168, 0.171)
            line(fur, Point(s * 0.229, -0.071, -0.085), Point(s * (0.454 + k), -0.219, -0.092), 0.143, 0.130)
            line(honey, Point(s * (0.454 + k), -0.219, -0.092), Point(s * (0.608 + k), -0.154, -0.093), 0.079, 0.086)
            line(ivory, Point(s * 0.181, 0.150, 0.096), Point(s * 0.182, -0.012, 0.093), 0.049, 0.060)
            line(amber, Point(s * 0.184, 0.223, 0.147), Point(s * 0.223, 0.239, 0.108), 0.016, 0.019)
        }
    }

    private fun Model.limb(s: Int) {
        joint = if (s < 0) Bone.L_THIGH else Bone.R_THIGH
        for (flank in listOf(-1, 1)) {
            line(skin, Point(flank * 0.049, -0.025), Point(flank * 0.024, -0.473), 0.083, 0.150)
        }
        box(cloth, 0.0, -0.055, 0.0, 0.211, 0.171, 0.234, s * 0.042)
        box(lapel, 0.0, -0.137, 0.0, 0.205, 0.045, 0.235, s * 0.042)
        if (s > 0) {
            box(ink, 0.0, -0.226, 0.0, 0.159, 0.045, 0.156)
            box(brass, -0.033, -0.226, 0.082, 0.038, 0.036, 0.013)
        }
        joint = if (s < 0) Bone.L_CALF else Bone.R_CALF
        val sock = if (s < 0) 0.117 else 0.305
        line(skin, Point(0.0, 0.019), Point(0.0, -sock - 0.005), 0.125, 0.126)
        line(Material.WHITE_WOOL, Point(0.0, -sock), Point(0.0, -0.483), 0.111, 0.108)
        box(ivory, 0.0, -sock, 0.0, 0.124, 0.040, 0.125)
        joint = if (s < 0) Bone.L_SHOE else Bone.R_SHOE
        box(navy, 0.0, 0.002, -0.010, 0.135, 0.163, 0.151, 0.0, 0.0, 0.07)
        box(navy, 0.0, -0.073, 0.060, 0.150, 0.078, 0.258)
        box(silver, 0.0, -0.109, 0.060, 0.155, 0.027, 0.262)
        line(amber, Point(-0.043, 0.058, 0.070), Point(0.043, -0.018, 0.085), 0.015, 0.012)
        line(amber, Point(0.043, 0.058, 0.070), Point(-0.043, -0.018, 0.085), 0.015, 0.013)
        joint = if (s < 0) Bone.L_UPPER else Bone.R_UPPER
        box(cloth, 0.0, -0.131, 0.0, 0.137, 0.287, 0.150, s * 0.025)
        box(lapel, 0.0, -0.029, 0.0, 0.169, 0.110, 0.179, s * 0.10)
        joint = if (s < 0) Bone.L_PAW else Bone.R_PAW
        line(cloth, Point(0.0, 0.013), Point(0.0, -0.282), 0.104, 0.119)
        box(amber, 0.0, -0.245, 0.0, 0.130, 0.034, 0.129)
        box(ink, 0.0, -0.321, 0.0, 0.144, 0.146, 0.141, s * 0.09)
        box(skin, s * 0.063, -0.338, 0.066, 0.055, 0.083, 0.052, -s * 0.27)
        for (j in -1..1) {
            val x = j * 0.046
            box(amber, x, -0.381 + 0.009 * j * j, 0.041, 0.057, 0.088, 0.075, -j * 0.24)
            box(Material.FLINT, x * 1.21, -0.412 + 0.012 * j * j, 0.067, 0.077, 0.112, 0.71,
                0.50 - j * 0.30, sprite = true)
        }
    }

    private fun Model.huWei() {
        joint = Bone.POT
        box(cloth, 0.0, 0.004, 0.006, 0.520, 0.199, 0.338)
        box(lapel, 0.0, 0.014, 0.012, 0.380, 0.448, 0.366)
        for (s in listOf(-1, 1)) {
            line(lapel, Point(s * 0.135, 0.174), Point(s * 0.215, 0.079), 0.096, 0.328)
            line(lapel, Point(s * 0.222, -0.065), Point(s * 0.144, -0.157), 0.096, 0.328)
        }
        box(cloth, 0.0, -0.184, 0.004, 0.479, 0.104, 0.337)
        box(amber, 0.0, -0.208, 0.006, 0.446, 0.024, 0.337)
        for (s in listOf(-1, 1)) {
            box(cloth, s * 0.147, -0.243, -0.010, 0.077, 0.120, 0.104, s * 0.21)
            line(amber, Point(s * 0.236, -0.061, 0.018), Point(s * 0.316, 0.092, 0.015), 0.073, 0.125)
            box(ink, s * 0.092, 0.150, 0.206, 0.105, 0.064, 0.021, s * 0.16)
            box(fur, s * 0.085, 0.147, 0.220, 0.049, 0.027, 0.020)
            line(silver, Point(s * 0.018, 0.168, 0.228), Point(s * 0.146, 0.205, 0.205), 0.043, 0.043)
            line(ivory, Point(s * 0.025, 0.085, 0.239), Point(s * 0.144, 0.031, 0.222), 0.058, 0.045)
            line(silver, Point(s * 0.116, 0.035, 0.217), Point(s * 0.083, -0.056, 0.220), 0.051, 0.037)
        }
        box(ink, 0.0, 0.104, 0.240, 0.073, 0.047, 0.040, PI / 4)
        box(silver, 0.0, -0.044, 0.218, 0.081, 0.089, 0.030, PI / 4)
        joint = Bone.LID
        box(cloth, 0.0, 0.018, 0.130, 0.451, 0.064, 0.316)
        box(lapel, 0.0, 0.060, 0.114, 0.340, 0.044, 0.232)
        box(silver, 0.0, 0.098, 0.080, 0.122, 0.052, 0.072)
    }

    private val model = Model().apply {
        uniform()
        head()
        mane()
        limb(-1)
        limb(1)
        huWei()
    }.parts
    private const val TAIL_PARTS = 23
    private const val CORD_PARTS = 4
    private const val KERNELS = 5
    val PART_COUNT = model.size + TAIL_PARTS + CORD_PARTS + KERNELS * 2

    override fun partCount(shape: AuroraShape) = PART_COUNT
    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val action = Action(t)
        if (index < model.size) {
            val p = model[index]
            return action.apply(action.bone(p.bone), p.pose)
        }
        val n = index - model.size
        return when {
            n < TAIL_PARTS -> action.tail(n)
            n < TAIL_PARTS + CORD_PARTS -> action.cord(n - TAIL_PARTS)
            else -> action.kernel(n - TAIL_PARTS - CORD_PARTS)
        }
    }

    private class Action(val t: Double) {
        val ready = storyBeat(t, 0.11, 0.33) * (1 - storyBeat(t, 0.67, 0.87))
        val catch = storyBeat(t, 0.38, 0.62) * (1 - storyBeat(t, 0.68, 0.83))
        val lift = sin(PI * storyBeat(t, 0.30, 0.68))
        val open = sin(PI * storyBeat(t, 0.357, 0.555))
        val appear = storyBeat(t, 0.0, 0.070) * (1 - storyBeat(t, 0.924, 1.0))

        fun bone(b: Bone): Frame = when (b) {
            Bone.BODY -> Frame(Point(-0.256 - 0.025 * ready, -0.008 + 0.070 * lift, 0.0))
                .child(0.0, 0.0, 0.0, -0.040 + 0.043 * ready, -0.085 + 0.13 * catch)
            Bone.CHEST -> bone(Bone.BODY).child(0.0, -0.058, 0.0, -0.050 - 0.085 * ready, -0.10 + 0.16 * catch)
            Bone.HEAD -> bone(Bone.CHEST).child(0.0, 0.520, 0.0, 0.062 + 0.105 * ready,
                -0.025 + 0.20 * catch, -0.02)
            Bone.L_UPPER, Bone.R_UPPER -> {
                val s = if (b == Bone.L_UPPER) -1 else 1
                bone(Bone.CHEST).child(s * 0.210, 0.457, 0.002, s * (0.45 + 0.32 * ready))
            }
            Bone.L_PAW, Bone.R_PAW -> {
                val s = if (b == Bone.L_PAW) -1 else 1
                val roll = 2.45 - 0.15 * ready - if (s > 0) 0.42 * catch else 0.0
                bone(if (s < 0) Bone.L_UPPER else Bone.R_UPPER)
                    .child(0.0, -0.286, 0.0, s * roll, s * 0.08, -0.37)
            }
            Bone.L_THIGH, Bone.R_THIGH -> {
                val front = b == Bone.L_THIGH
                bone(Bone.BODY).child(if (front) -0.109 else 0.109, -0.127, if (front) 0.048 else -0.057,
                    if (front) -0.102 + 0.038 * lift else -0.054 - 0.25 * lift, if (front) -0.07 else 0.13)
            }
            Bone.L_CALF -> bone(Bone.L_THIGH).child(0.0, -0.479, 0.0, 0.186)
            Bone.R_CALF -> bone(Bone.R_THIGH).child(0.0, -0.479, 0.0, 1.67 + 0.39 * lift, 0.0, 0.15)
            Bone.L_SHOE -> bone(Bone.L_CALF).child(0.0, -0.466, 0.0, -0.07, -0.48)
            Bone.R_SHOE -> bone(Bone.R_CALF).child(0.0, -0.466, 0.0, -2.15, 0.31)
            Bone.POT -> Frame(Point(0.726 + 0.031 * open, -0.589 + 0.110 * open, -0.061))
                .child(0.0, 0.0, 0.0, -0.1 + 0.10 * open, -0.25)
            Bone.LID -> bone(Bone.POT).child(0.0, 0.222, -0.129, -0.07 * open, 0.0, -0.90 * open)
        }

        fun apply(frame: Frame, pose: AuroraMiniaturePart, show: Double = 1.0): AuroraMiniaturePart {
            val c = pose.center
            val p = frame.point(Point(c.x, c.y, c.z))
            return pose.copy(center = AuroraOffset(p.x * appear, p.y * appear, p.z * appear),
                rotation = Quaternionf(frame.q).mul(pose.rotation), scale = Vector3f(pose.scale).mul((appear * show).toFloat()))
        }

        private fun tailPoint(i: Int): Point {
            val rest = tailPath[i]
            val u = i / (tailPath.size - 1.0)
            val sweep = u * u * (0.109 * ready - 0.125 * catch)
            return Point(rest.x + sweep, rest.y + 0.047 * sin(PI * u) * lift, rest.z - 0.067 * catch * u)
        }

        fun tail(i: Int): AuroraMiniaturePart {
            val local = when {
                i < 9 -> bar(if (i == 8) honey else fur, tailPoint(i), tailPoint(i + 1), tailWidths[i], tailWidths[i] * 0.85)
                i < 14 -> {
                    val k = i - 8
                    val a = tailPoint(k)
                    val b = tailPoint(k + 1)
                    bar(if (i % 2 == 1) ink else amber, a.mix(b, 0.27), a.mix(b, 0.62),
                        tailWidths[k] + 0.009, tailWidths[k] * 0.85 + 0.009)
                }
                else -> {
                    val p = tailPoint(7)
                    when (i) {
                        14 -> bar(ivory, tailPoint(7).copy(x = p.x + 0.064), tailPoint(8).copy(x = tailPoint(8).x + 0.084), 0.101, 0.097)
                        15 -> bar(ivory, tailPoint(8).copy(x = tailPoint(8).x + 0.084), tailPoint(9), 0.076, 0.064)
                        16 -> storyPart(navy, p.x, p.y, p.z + 0.128, 0.081, 0.079, 0.083, 0.71)
                        17, 18 -> {
                            val s = if (i == 17) -1 else 1
                            bar(navy, Point(p.x + s * 0.012, p.y, p.z + 0.118),
                                Point(p.x + s * 0.146, p.y + 0.097, p.z + 0.095), 0.097, 0.061)
                        }
                        19, 20 -> {
                            val s = if (i == 19) -1 else 1
                            bar(navy, Point(p.x + s * 0.021, p.y - 0.018, p.z + 0.100),
                                Point(p.x + s * 0.086, p.y - 0.149, p.z + 0.089), 0.053, 0.044)
                        }
                        21 -> storyPart(brass, p.x, p.y, p.z + 0.170, 0.035, 0.035, 0.021, PI / 4)
                        else -> bar(fur, tailPoint(8).mix(tailPoint(9), 0.55),
                            tailPoint(9).let { Point(it.x - 0.046, it.y + 0.032, it.z - 0.010) }, 0.043, 0.038)
                    }
                }
            }
            return apply(bone(Bone.BODY), local)
        }

        fun cord(i: Int): AuroraMiniaturePart {
            val root = bone(Bone.POT).point(Point(-0.195, -0.103, -0.130))
            fun sample(n: Int): Point {
                val u = n / CORD_PARTS.toDouble()
                val a = Point(-0.442, -0.423 + 0.030 * lift, -0.244)
                val p = a.mix(root, u)
                return Point(p.x, p.y - (0.296 + 0.014 * open) * sin(PI * u), p.z)
            }
            return apply(Frame(Point(0.0, 0.0)), bar(amber, sample(i), sample(i + 1), 0.041, 0.042))
        }

        fun kernel(i: Int): AuroraMiniaturePart {
            val n = i / 2
            val start = 0.363 + n * 0.014
            val u = ((t - start) / (0.267 + n * 0.008)).coerceIn(0.0, 1.0)
            val end = if (n == 0) storyBeat(t, 0.648, 0.700) else storyBeat(u, 0.81, 1.0)
            val size = storyBeat(t, start, start + 0.035) * (1 - end)
            val from = Point(0.714, -0.305, -0.005)
            val paw = bone(Bone.R_PAW).point(Point(0.0, -0.468, 0.124))
            val to = if (n == 0) paw else Point(0.355 + n * 0.136, -0.190 + 0.085 * (n % 2), 0.041)
            val k = from.mix(to, u)
            val p = Point(k.x, k.y + (if (n == 0) 0.239 else 0.354 + 0.073 * n) * sin(PI * u), k.z)
            val r = 4.0 * u + n
            val pop = if (i % 2 == 0) storyPart(ivory, 0.0, 0.0, 0.0, 0.062, 0.073, 0.058, r, r * 0.47)
                else storyPart(honey, 0.016, -0.013, 0.012, 0.047, 0.045, 0.038, -r)
            return apply(Frame(p), pop, size)
        }
    }

    private val tailWidths = doubleArrayOf(0.125, 0.150, 0.180, 0.195, 0.225, 0.238, 0.228, 0.195, 0.082)
    private val tailPath = listOf(
        Point(0.121, -0.105, -0.142), Point(0.349, -0.239, -0.188), Point(0.564, -0.224, -0.214),
        Point(0.699, -0.079, -0.216), Point(0.723, 0.157, -0.206), Point(0.673, 0.371, -0.192),
        Point(0.655, 0.554, -0.166), Point(0.732, 0.724, -0.142), Point(0.810, 0.888, -0.110),
        Point(0.798, 1.069, -0.087),
    )
}
