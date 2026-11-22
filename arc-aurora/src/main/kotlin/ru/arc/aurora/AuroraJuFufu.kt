package ru.arc.aurora

import org.bukkit.Material
import org.joml.Matrix3f
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
        BODY, CHEST, HEAD, L_MANE, R_MANE, L_UPPER, R_UPPER, L_PAW, R_PAW,
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

    // Sections are in joint-local coordinates. Their positive Z side faces the spectator.
    private data class Section(val y: Double, val rx: Double, val rz: Double, val x: Double = 0.0, val z: Double = 0.0) {
        fun at(angle: Double) = Point(x + rx * sin(angle), y, z + rz * cos(angle))
    }

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
        var portraitSlots = IntRange.EMPTY
            private set

        fun portrait(draw: Model.() -> Unit) {
            val first = parts.size
            draw()
            portraitSlots = first until parts.size
        }
        fun box(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
            roll: Double = 0.0, yaw: Double = 0.0, pitch: Double = 0.0, sprite: Boolean = false) {
            val pose = storyPart(m, x, y, z, w, h, d, roll, yaw, pitch)
            parts += Piece(joint, if (sprite) pose.copy(generatedSprite = true) else pose)
        }
        fun line(m: Material, a: Point, b: Point, w: Double, d: Double, twist: Double = 0.0) {
            val pose = bar(m, a, b, w, d)
            if (twist != 0.0) pose.rotation.rotateX(twist.toFloat())
            parts += Piece(joint, pose)
        }

        fun on(frame: Frame, local: AuroraMiniaturePart, sprite: Boolean = false) {
            val c = local.center
            val center = frame.point(Point(c.x, c.y, c.z))
            parts += Piece(joint, local.copy(center = AuroraOffset(center.x, center.y, center.z),
                rotation = Quaternionf(frame.q).mul(local.rotation), generatedSprite = sprite))
        }

        fun panel(m: Material, a: Point, b: Point, c: Point, d: Point, gauge: Double, overlap: Double) {
            fun between(from: Point, to: Point) = Vector3f((to.x - from.x).toFloat(),
                (to.y - from.y).toFloat(), (to.z - from.z).toFloat())
            val lower = between(a, b)
            val upper = between(c, d)
            val right = Vector3f(lower).add(upper).normalize()
            val rise = between(a.mix(b, 0.5), c.mix(d, 0.5))
            val normal = right.cross(rise, Vector3f()).normalize()
            val up = normal.cross(right, Vector3f()).normalize()
            val middle = a.mix(b, 0.5).mix(c.mix(d, 0.5), 0.5)
            val thickness = gauge.toFloat()
            val center = AuroraOffset(middle.x - normal.x * thickness / 2, middle.y - normal.y * thickness / 2,
                middle.z - normal.z * thickness / 2)
            val scale = Vector3f(maxOf(lower.length(), upper.length()) + overlap.toFloat(), rise.dot(up) + overlap.toFloat(), thickness)
            parts += Piece(joint, AuroraMiniaturePart(m, center,
                Quaternionf().setFromNormalized(Matrix3f(right, up, normal)), scale))
        }

        fun clothPanel(a: Point, b: Point, c: Point, d: Point) = panel(cloth, a, b, c, d, 0.032, 0.009)

        // A regular hexagon is exactly the union of three inscribed rectangles.
        // It gives small cuffs and short rounded joints solid backs at every yaw.
        fun sleeve(m: Material, bottom: Section, top: Section) {
            val dx = (top.x - bottom.x).toFloat()
            val dy = (top.y - bottom.y).toFloat()
            val dz = (top.z - bottom.z).toFloat()
            val axis = Vector3f(dx, dy, dz)
            val height = axis.length()
            val basis = Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), axis.normalize())
            val x = (bottom.rx + top.rx) * 0.5
            val z = (bottom.rz + top.rz) * 0.5
            for (i in 0..2) {
                val a = i * PI / 3
                val width = 1.73205 / hypot(cos(a) / x, sin(a) / z)
                val depth = 1 / hypot(sin(a) / x, cos(a) / z)
                parts += Piece(joint, AuroraMiniaturePart(m,
                    AuroraOffset((bottom.x + top.x) / 2, (bottom.y + top.y) / 2, (bottom.z + top.z) / 2),
                    Quaternionf(basis).rotateY(a.toFloat()), Vector3f(width.toFloat(), height + 0.003f, depth.toFloat())))
            }
        }

        fun taper(m: Material, lo: Section, hi: Section, wide: Boolean = true) {
            val angles = if (wide) doubleArrayOf(-1.78, -0.53, 0.53, 1.78) else doubleArrayOf(-1.78, 0.0, 1.78)
            val depth = 0.97 * minOf(lo.rx, lo.rz, hi.rx, hi.rz)
            for (i in 0 until angles.lastIndex) panel(m, lo.at(angles[i]), lo.at(angles[i + 1]),
                hi.at(angles[i]), hi.at(angles[i + 1]), depth, 0.003)
            panel(m, lo.at(2.44), lo.at(2 * PI - 2.44), hi.at(2.44), hi.at(2 * PI - 2.44), depth * 1.2, 0.003)
        }

        // The sprite is turned flat, so its opaque pixel contour becomes the actual
        // horizontal cross-section. Its extruded rim keeps the shoe or band solid in profile.
        fun band(m: Material, x: Double, y: Double, z: Double, w: Double, length: Double, height: Double,
            yaw: Double = 0.0) = box(m, x, y, z, w, length, height * 16, yaw = yaw, pitch = PI / 2, sprite = true)

        // Two narrow sides follow the taper all the way to the pointed end.
        // The broad root sits between them and stops before their meeting point.
        fun tress(m: Material, edge: Material, a: Point, b: Point, width: Double, depth: Double) {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            val nx = -dy / length
            val ny = dx / length
            val margin = width * 0.21
            for (side in listOf(-1, 1)) {
                val r = side * (width - margin) / 2
                val start = Point(a.x + nx * r, a.y + ny * r, a.z)
                val end = Point(b.x, b.y, b.z + side * 0.003)
                line(if (side > 0) m else edge, start, end, margin, depth * 0.60)
            }
            val root = a.mix(b, 0.63)
            line(m, a, root, width * 0.61, depth * 0.91)
        }

        fun wing(m: Material, points: List<Point>, widths: List<Double>, depth: Double) {
            for (i in 0 until points.lastIndex - 1) {
                val a = points[i]
                val b = points[i + 1]
                line(m, a, b, (widths[i] + widths[i + 1]) / 2, depth * (0.98 - 0.17 * i))
            }
            val a = points[points.lastIndex - 1]
            val b = points.last()
            tress(m, if (m == fur) honey else m, a, b, widths[widths.lastIndex - 1], depth * 0.62)
        }
    }

    private fun Model.uniform() {
        joint = Bone.BODY
        sleeve(cloth, Section(-0.155, 0.159, 0.112, z = -0.007), Section(-0.055, 0.156, 0.109))
        band(Material.MUSIC_DISC_STAL, 0.0, -0.046, -0.002, 0.344, 0.245, 0.032)
        joint = Bone.CHEST
        // The centre follows four slopes in profile. The wider side panels share
        // the hem, middle and neck points, turning the cloth toward each armhole.
        val hem = jerseyRing(0.174, 0.051, 0.089, 0.119, 0.046)
        val lowerRibs = jerseyRing(0.285, 0.061, 0.125, 0.143, 0.064)
        val ribs = jerseyRing(0.337, 0.064, 0.143, 0.149, 0.068)
        val upperRibs = jerseyRing(0.384, 0.062, 0.139, 0.143, 0.065)
        val neck = jerseyRing(0.451, 0.053, 0.105, 0.125, 0.047, 0.020)
        val front = listOf(hem, lowerRibs, ribs, upperRibs, neck)
        for ((lower, upper) in front.zipWithNext()) clothPanel(lower[1], lower[2], upper[1], upper[2])
        for (edge in listOf(0, 2)) {
            clothPanel(hem[edge], hem[edge + 1], ribs[edge], ribs[edge + 1])
            clothPanel(ribs[edge], ribs[edge + 1], neck[edge], neck[edge + 1])
        }
        val backL = Point(-0.086, 0.176, -0.079)
        val backR = Point(0.086, 0.176, -0.079)
        val shoulderL = Point(-0.097, 0.425, -0.096)
        val shoulderR = Point(0.097, 0.425, -0.096)
        clothPanel(hem.last(), backR, neck.last(), shoulderR)
        clothPanel(backR, backL, shoulderR, shoulderL)
        clothPanel(backL, hem.first(), shoulderL, neck.first())
        band(Material.MUSIC_DISC_STAL, 0.0, 0.169, -0.001, 0.280, 0.210, 0.024)
        val waist = Section(0.080, 0.113, 0.079, x = -0.003, z = -0.008)
        taper(skin, Section(0.015, 0.132, 0.092, z = -0.007), waist)
        taper(skin, waist, Section(0.174, 0.122, 0.089, z = -0.005))
        sleeve(skin, Section(0.424, 0.053, 0.056, z = -0.010),
            Section(0.640, 0.049, 0.065, z = -0.024))
        for (s in listOf(-1, 1)) {
            line(skin, Point(s * 0.047, 0.474, -0.018), Point(s * 0.183, 0.444, -0.011), 0.094, 0.111)
            line(lapel, Point(s * 0.071, 0.492, 0.049), Point(s * 0.103, 0.429, 0.087), 0.057, 0.031)
        }
        line(amber, Point(-0.079, 0.489, 0.096), Point(-0.047, 0.362, 0.156), 0.011, 0.011)
        line(amber, Point(0.071, 0.489, 0.096), Point(-0.047, 0.362, 0.156), 0.011, 0.011)
        box(amber, -0.048, 0.329, 0.151, 0.062, 0.079, 0.016, -0.15, pitch = 0.19)
        box(ink, -0.047, 0.332, 0.161, 0.022, 0.034, 0.008, -0.15, pitch = 0.19)
    }

    private fun jerseyRing(y: Double, innerX: Double, frontZ: Double, outerX: Double, sideZ: Double,
        armhole: Double = 0.0) = listOf(Point(-outerX, y - armhole, sideZ), Point(-innerX, y, frontZ),
            Point(innerX, y, frontZ), Point(outerX, y - armhole, sideZ))

    private fun Model.face() {
        joint = Bone.HEAD
        // The front and side meridians follow the same convex cheek and jaw profile.
        box(skin, 0.0, 0.222, -0.013, 0.203, 0.183, 0.196)
        val chin = Section(0.066, 0.039, 0.044, z = 0.075)
        val jaw = Section(0.097, 0.093, 0.080, z = 0.061)
        val cheek = Section(0.155, 0.140, 0.109, z = 0.052)
        val eyes = Section(0.223, 0.159, 0.128, z = 0.037)
        val brow = Section(0.310, 0.151, 0.117, z = 0.026)
        for ((lo, hi) in listOf(chin, jaw, cheek, eyes, brow).zipWithNext()) {
            val angles = when (lo) {
                chin -> doubleArrayOf(-0.89, 0.89)
                eyes -> doubleArrayOf(-1.20, 0.0, 1.20)
                else -> doubleArrayOf(-1.20, -0.43, 0.43, 1.20)
            }
            for (j in 0 until angles.lastIndex) panel(skin, lo.at(angles[j]), lo.at(angles[j + 1]),
                hi.at(angles[j]), hi.at(angles[j + 1]), 0.023, 0.003)
        }
        // The silhouette follows the oblique mandible through to the temple.
        // Frontal plates end inside it so their horizontal ends do not notch the jaw.
        for (side in listOf(-1, 1)) {
            fun pt(x: Double, y: Double, z: Double) = Point(side * x, y, z)
            line(skin, pt(0.017, 0.082, 0.073), pt(0.083, 0.113, 0.061), 0.037, 0.115)
            line(skin, pt(0.081, 0.113, 0.061), pt(0.127, 0.163, 0.046), 0.044, 0.127)
            line(skin, pt(0.126, 0.164, 0.047), pt(0.145, 0.257, 0.031), 0.038, 0.136)
        }
        for (side in listOf(-1, 1)) eye(side)
        line(skin, Point(0.0, 0.211, 0.149), Point(-0.003, 0.179, 0.166), 0.018, 0.026)
        box(Material.BRICK, 0.001, 0.144, 0.161, 0.096, 0.040, 0.110, 0.13, sprite = true)
        box(Material.PAPER, 0.003, 0.146, 0.166, 0.086, 0.024, 0.090, 0.13, sprite = true)
        line(Material.PINK_TERRACOTTA, Point(-0.021, 0.129, 0.163), Point(0.026, 0.134, 0.160), 0.007, 0.008)
    }

    private fun Model.head() {
        joint = Bone.HEAD
        box(amber, 0.0, 0.272, -0.096, 0.254, 0.214, 0.162, -0.06)
        val crest = Section(0.447, 0.053, 0.049, x = -0.009, z = -0.012)
        val crown = Section(0.371, 0.145, 0.135, z = 0.008)
        val fringe = Section(0.282, 0.171, 0.161, z = 0.021)
        val hairAngles = doubleArrayOf(-1.78, -0.99, -0.43, 0.035, 0.79, 1.78)
        fun fringeAt(i: Int): Point {
            val p = fringe.at(hairAngles[i])
            val y = when (i) {
                0, 5 -> 0.224
                1 -> 0.259
                2, 4 -> 0.304
                else -> 0.250
            }
            return p.copy(y = y)
        }
        for (i in 0 until hairAngles.lastIndex) {
            val a = hairAngles[i]
            val b = hairAngles[i + 1]
            val c = if (i == 1 || i == 4) honey else fur
            panel(c, crown.at(a), crown.at(b), crest.at(a), crest.at(b), 0.069, 0.008)
            panel(c, fringeAt(i), fringeAt(i + 1), crown.at(a), crown.at(b), 0.072, 0.008)
        }
        tress(honey, fur, Point(-0.040, 0.370, 0.144), Point(0.007, 0.245, 0.185), 0.064, 0.046)
        // The high, sprung forelock is recognizable above the two round ears.
        line(honey, Point(-0.020, 0.424, -0.003), Point(0.025, 0.502, -0.007), 0.030, 0.035)
        line(fur, Point(0.025, 0.500, -0.007), Point(0.013, 0.543, -0.013), 0.026, 0.030)
        line(fur, Point(0.013, 0.543, -0.013), Point(-0.034, 0.567, -0.020), 0.018, 0.021)
        line(honey, Point(-0.034, 0.567, -0.020), Point(-0.072, 0.556, -0.015), 0.010, 0.012)
        for (s in listOf(-1, 1)) {
            line(amber, Point(s * 0.147, 0.321, 0.037), Point(s * 0.184, 0.248, 0.060), 0.043, 0.059)
            line(fur, Point(s * 0.185, 0.256, 0.062), Point(s * 0.182, 0.189, 0.082), 0.043, 0.055)
            line(honey, Point(s * 0.183, 0.191, 0.084), Point(s * 0.148, 0.158, 0.121), 0.029, 0.037)
            box(Material.MUSIC_DISC_STAL, s * 0.169, 0.437, -0.015, 0.198, 0.210, 1.55,
                -s * 0.36, s * 0.18, sprite = true)
            box(Material.FEATHER, s * 0.173, 0.435, 0.050, 0.132, 0.131, 0.48,
                -0.76 - s * 0.21, sprite = true)
            line(honey, Point(s * 0.078, 0.434, -0.026), Point(s * 0.173, 0.368, -0.032), 0.087, 0.142)
        }
    }

    private fun Model.eye(side: Int) {
        val f = Frame(Point(side * 0.082, 0.231, 0.151)).child(0.0, 0.0, 0.0, -side * 0.055, side * 0.35)
        // Coloured generated meshes sit tangent to the cheek, with a separate dark pupil and light.
        on(f, storyPart(Material.SNOWBALL, 0.0, 0.0, 0.002, 0.150, 0.067, 0.070), true)
        on(f, storyPart(Material.GOLD_NUGGET, -0.004, -0.001, 0.008, 0.128, 0.116, 0.062), true)
        on(f, storyPart(Material.MUSIC_DISC_STAL, -0.001, 0.001, 0.013, 0.032, 0.043, 0.055), true)
        on(f, storyPart(Material.SNOWBALL, -0.010, 0.010, 0.019, 0.024, 0.026, 0.047), true)
        on(f, bar(ink, Point(-side * 0.050, 0.015, 0.011), Point(side * 0.008, 0.028, 0.014), 0.008, 0.009))
        on(f, bar(ink, Point(side * 0.008, 0.028, 0.014), Point(side * 0.060, 0.021, 0.010), 0.009, 0.009))
        on(f, bar(Material.BROWN_TERRACOTTA, Point(-side * 0.037, 0.059, 0.005), Point(side * 0.038, 0.055, -0.002), 0.007, 0.011))
    }

    private fun Model.mane() {
        for (s in listOf(-1, 1)) {
            joint = if (s < 0) Bone.L_MANE else Bone.R_MANE
            val k = if (s > 0) 0.014 else 0.0
            fun strand(x: Double, y: Double, z: Double) = Point(s * x, y, z)
            // The upper layer flips away from the cheek. Its pale inner layer curls back under the jaw.
            wing(fur, listOf(strand(0.125, 0.306, -0.095), strand(0.182, 0.198, -0.105),
                strand(0.230, 0.110, -0.086), strand(0.274 + k, 0.070, -0.059),
                strand(0.312 + k, 0.105, -0.035)), listOf(0.106, 0.146, 0.125, 0.066, 0.0), 0.109)
            wing(ivory, listOf(strand(0.130, 0.184, -0.037), strand(0.179, 0.132, -0.023),
                strand(0.204 + k, 0.083, 0.008), strand(0.163 + k, 0.057, 0.050)),
                listOf(0.074, 0.108, 0.066, 0.0), 0.078)
            if (s > 0) {
                line(honey, strand(0.166, 0.277, -0.049), strand(0.232, 0.279, -0.023), 0.018, 0.024)
                line(fur, strand(0.232, 0.279, -0.023), strand(0.250, 0.246, -0.004), 0.014, 0.019)
                line(honey, strand(0.250, 0.246, -0.004), strand(0.232, 0.220, 0.011), 0.009, 0.014)
            }
        }
    }

    private fun Model.limb(s: Int) {
        joint = if (s < 0) Bone.L_THIGH else Bone.R_THIGH
        taper(skin, Section(-0.469, 0.056, 0.060, x = s * 0.003, z = 0.014),
            Section(-0.146, 0.083, 0.089, x = -s * 0.002, z = 0.0))
        sleeve(cloth, Section(-0.151, 0.105, 0.113, x = s * 0.006), Section(0.016, 0.103, 0.115))
        band(Material.MUSIC_DISC_STAL, s * 0.004, -0.148, 0.0, 0.240, 0.255, 0.013)
        line(amber, Point(s * 0.081, -0.017, 0.068), Point(s * 0.087, -0.141, 0.061), 0.011, 0.013)
        if (s > 0) {
            band(Material.MUSIC_DISC_STAL, 0.0, -0.226, 0.0, 0.188, 0.190, 0.033)
            box(brass, -0.033, -0.226, 0.082, 0.038, 0.036, 0.013)
        }
        joint = if (s < 0) Bone.L_CALF else Bone.R_CALF
        val knee = Section(0.011, 0.055, 0.057, z = 0.003)
        val calf = Section(-0.130, 0.065, 0.071, x = s * 0.006, z = -0.012)
        val ankle = Section(-0.478, 0.040, 0.045, x = -s * 0.004)
        sleeve(skin, calf, knee)
        if (s < 0) {
            taper(Material.WHITE_WOOL, ankle, calf)
        } else {
            val sock = Section(-0.324, 0.049, 0.054, x = s * 0.001, z = -0.005)
            taper(skin, sock, calf)
            sleeve(Material.WHITE_WOOL, ankle, sock)
        }
        joint = if (s < 0) Bone.L_SHOE else Bone.R_SHOE
        line(navy, Point(0.0, 0.047, -0.024), Point(0.0, -0.054, 0.038), 0.114, 0.123)
        box(navy, 0.0, -0.072, 0.054, 0.122, 0.057, 0.185, pitch = 0.12)
        band(Material.CLAY_BALL, 0.0, -0.103, 0.052, 0.189, 0.304, 0.025)
        line(amber, Point(-0.043, 0.058, 0.070), Point(0.043, -0.018, 0.085), 0.015, 0.012)
        joint = if (s < 0) Bone.L_UPPER else Bone.R_UPPER
        taper(skin, Section(-0.283, 0.043, 0.042, x = s * 0.006),
            Section(-0.032, 0.059, 0.066, x = s * 0.002, z = -0.001))
        joint = if (s < 0) Bone.L_PAW else Bone.R_PAW
        taper(skin, Section(-0.270, 0.031, 0.035, x = -s * 0.006),
            Section(-0.016, 0.046, 0.053, x = s * 0.005), wide = false)
        box(skin, 0.0, 0.001, 0.003, 0.054, 0.067, 0.074, s * 0.52, -s * 0.34)
        band(Material.MUSIC_DISC_13, 0.0, -0.250, 0.0, 0.124, 0.128, 0.027)
        box(Material.FLINT, 0.0, -0.314, 0.010, 0.180, 0.198, 1.05, s * 0.16, s * 0.22, sprite = true)
        line(skin, Point(s * 0.044, -0.309, 0.030), Point(s * 0.082, -0.354, 0.069), 0.033, 0.036)
        box(amber, 0.0, -0.357, 0.038, 0.129, 0.043, 0.066, s * 0.07)
        for (j in -1..1) {
            val x = j * 0.044
            line(skin, Point(x * 0.87, -0.354, 0.053), Point(x * 1.23, -0.405 + 0.010 * j * j, 0.083),
                0.032, 0.037)
            box(Material.FLINT, x * 1.29, -0.420 + 0.011 * j * j, 0.093, 0.061, 0.089, 0.49,
                0.45 - j * 0.33, sprite = true)
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

    private val figure = Model().apply {
        uniform()
        portrait { face() }
        head()
        mane()
        limb(-1)
        limb(1)
    }
    private val model = figure.parts

    val packFaceIndex: Int get() = figure.portraitSlots.first
    fun isPackFaceFallback(index: Int) = index in figure.portraitSlots

    // In ItemDisplay.NONE the north UV face is the positive-Z side of the sculpture.
    private val mask = storyPart(Material.PAPER, 0.0, 0.205, 0.020, 0.36, 0.36, 0.36)
    fun packFacePart(t: Double): AuroraMiniaturePart {
        val action = Action(t)
        return action.apply(action.bone(Bone.HEAD), mask)
    }

    private val pot = Model().apply { huWei() }.parts
    val huWeiPartCount: Int get() = pot.size

    fun huWeiPart(index: Int, t: Double): AuroraMiniaturePart {
        val action = Action(t)
        val piece = pot[index]
        return action.apply(action.bone(piece.bone), piece.pose)
    }

    private const val TAIL_PARTS = 23
    private const val CORD_PARTS = 4
    private const val KERNELS = 5
    val PART_COUNT = model.size + pot.size + TAIL_PARTS + CORD_PARTS + KERNELS * 2

    override fun partCount(shape: AuroraShape) = PART_COUNT
    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        if (index in model.size until model.size + pot.size) return huWeiPart(index - model.size, t)
        val action = Action(t)
        if (index < model.size) {
            val p = model[index]
            return action.apply(action.bone(p.bone), p.pose)
        }
        val n = index - model.size - pot.size
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
            Bone.HEAD -> bone(Bone.CHEST).child(0.0, 0.504, 0.0, 0.071 + 0.123 * ready,
                0.011 + 0.245 * catch, -0.070 - 0.059 * ready)
            Bone.L_MANE, Bone.R_MANE -> {
                val s = if (b == Bone.L_MANE) -1 else 1
                bone(Bone.HEAD).child(s * 0.13, 0.26, -0.10,
                    s * (0.065 * lift - 0.040 * catch), s * 0.065 * catch, -0.035 * lift)
                    .child(-s * 0.13, -0.26, 0.10)
            }
            Bone.L_UPPER, Bone.R_UPPER -> {
                val s = if (b == Bone.L_UPPER) -1 else 1
                bone(Bone.CHEST).child(s * 0.185, 0.450, 0.001,
                    s * (if (s < 0) 0.32 + 0.39 * ready else 0.31 + 0.19 * ready), s * 0.08, 0.09)
            }
            Bone.L_PAW, Bone.R_PAW -> {
                val s = if (b == Bone.L_PAW) -1 else 1
                val roll = 2.52 + 0.32 * ready + if (s < 0) 0.14 else -0.42 * catch
                bone(if (s < 0) Bone.L_UPPER else Bone.R_UPPER)
                    .child(0.0, -0.286, 0.0, s * roll, s * 0.10, -0.43)
            }
            Bone.L_THIGH, Bone.R_THIGH -> {
                val front = b == Bone.L_THIGH
                bone(Bone.BODY).child(if (front) -0.109 else 0.109, -0.127, if (front) 0.048 else -0.057,
                    if (front) -0.102 + 0.037 * lift else -0.20 - 0.19 * lift, if (front) -0.07 else 0.18,
                    if (front) -0.036 else -0.09)
            }
            Bone.L_CALF -> bone(Bone.L_THIGH).child(0.0, -0.479, 0.0, 0.186)
            Bone.R_CALF -> bone(Bone.R_THIGH).child(0.0, -0.479, 0.0, 1.55 + 0.26 * lift, 0.0, 0.15)
            Bone.L_SHOE -> bone(Bone.L_CALF).child(0.0, -0.466, 0.0, -0.07, -0.48)
            Bone.R_SHOE -> bone(Bone.R_CALF).child(0.0, -0.466, 0.0, -1.84, 0.31)
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
                val a = bone(Bone.BODY).point(Point(-0.078, -0.135, -0.188))
                val p = a.mix(root, u)
                return Point(p.x, p.y - (0.440 + 0.014 * open) * sin(PI * u), p.z)
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
