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
import kotlin.math.sqrt

/** A rose-haired soloist turns from her upturned palm as a heart separates from her fingertips. */
internal object AuroraSakuraIdol : AuroraStoryParts {
    const val PART_COUNT = 259

    private val skin = Material.WHITE_TERRACOTTA
    private val ink = Material.BLUE_TERRACOTTA
    private val shadow = Material.GRAY_TERRACOTTA
    private val gold = Material.GOLD_BLOCK
    private val oldGold = Material.YELLOW_TERRACOTTA
    private val pearl = Material.SMOOTH_QUARTZ
    private val hair = Material.PINK_CONCRETE
    private val hairLight = Material.STRIPPED_CHERRY_WOOD
    private val heartColor = Material.PINK_CONCRETE

    override fun partCount(shape: AuroraShape) = PART_COUNT

    @Volatile
    private var currentPose: Pose? = null

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val pose = currentPose?.takeIf { it.t == t } ?: Pose(t).also { currentPose = it }
        return pose.part(index)
    }

    private data class P(val x: Double, val y: Double, val z: Double = 0.0) {
        fun offset(dx: Double, dy: Double, dz: Double = 0.0) = P(x + dx, y + dy, z + dz)
        fun at(b: P, u: Double) = offset((b.x - x) * u, (b.y - y) * u, (b.z - z) * u)
        fun vec() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun curve(a: P, b: P, c: P, d: P, u: Double): P {
        val v = 1 - u
        val aa = v * v * v
        val bb = 3 * v * v * u
        val cc = 3 * v * u * u
        val dd = u * u * u
        return P(aa * a.x + bb * b.x + cc * c.x + dd * d.x, aa * a.y + bb * b.y + cc * c.y + dd * d.y,
            aa * a.z + bb * b.z + cc * c.z + dd * d.z)
    }

    private data class Arm(val shoulder: P, val elbow: P, val wrist: P, val fingers: Double)

    // Each lock passes through sculpted knots. Catmull-Rom tangents keep the large S-curves continuous.
    private val locks = listOf(
        listOf(P(-0.095, 0.316, -0.102), P(-0.141, 0.120, -0.138), P(-0.260, -0.030, -0.159),
            P(-0.467, -0.180, -0.164), P(-0.428, -0.340, -0.182), P(-0.545, -0.535, -0.141),
            P(-0.533, -0.732, -0.080), P(-0.431, -0.852, -0.059)),
        listOf(P(0.068, 0.316, -0.116), P(0.041, 0.099, -0.200), P(-0.173, -0.067, -0.227),
            P(-0.363, -0.188, -0.260), P(-0.357, -0.401, -0.206), P(-0.452, -0.580, -0.174),
            P(-0.420, -0.745, -0.124), P(-0.324, -0.822, -0.106)),
        listOf(P(-0.112, 0.271, -0.063), P(-0.239, 0.069, -0.106), P(-0.495, -0.153, -0.142),
            P(-0.580, -0.315, -0.144), P(-0.553, -0.430, -0.133), P(-0.616, -0.523, -0.098)),
        listOf(P(0.115, 0.258, -0.032), P(0.201, 0.056, -0.068), P(0.190, -0.119, -0.069),
            P(0.263, -0.201, -0.036), P(0.245, -0.280, 0.015)),
        listOf(P(-0.037, 0.308, -0.167), P(-0.110, 0.063, -0.220), P(-0.338, -0.079, -0.302),
            P(-0.487, -0.273, -0.273), P(-0.447, -0.462, -0.217), P(-0.539, -0.635, -0.168)),
        listOf(P(0.046, 0.242, -0.162), P(0.040, -0.001, -0.209), P(-0.136, -0.155, -0.255),
            P(-0.287, -0.326, -0.251), P(-0.266, -0.495, -0.201), P(-0.356, -0.642, -0.177))
    )

    private fun hairCurve(points: List<P>, t: Double): P {
        val phase = t * (points.size - 1)
        val i = minOf(phase.toInt(), points.lastIndex - 1)
        val a = points[i]
        val b = points[i + 1]
        val prev = points[maxOf(0, i - 1)]
        val next = points[minOf(points.lastIndex, i + 2)]
        return curve(a, a.offset((b.x - prev.x) / 6, (b.y - prev.y) / 6, (b.z - prev.z) / 6),
            b.offset((a.x - next.x) / 6, (a.y - next.y) / 6, (a.z - next.z) / 6), b, phase - i)
    }

    private class Pose(val t: Double) {
        private val fade = storyBeat(t, 0.0, 0.065) * (1 - storyBeat(t, 0.932, 1.0))
        private val gather = storyBeat(t, 0.076, 0.323) * (1 - storyBeat(t, 0.735, 0.927))
        private val send = storyBeat(t, 0.447, 0.601) * (1 - storyBeat(t, 0.761, 0.910))
        private val breeze = sin(2 * PI * t)
        private val hipX = 0.062 + 0.032 * gather - 0.027 * send
        private val lean = -0.081 - 0.026 * gather + 0.031 * send
        private val bow = 0.254 + 0.093 * gather - 0.052 * send
        private val facing = 0.372 + 0.093 * gather - 0.116 * send + 0.013 * breeze
        private val sceneTurn = Quaternionf().rotationY(facing.toFloat())
        private val headRoll = 0.065 + 0.048 * gather - 0.032 * send
        private val headYaw = -0.461 - 0.064 * gather + 0.063 * send
        private val headPitch = 0.100 + 0.045 * gather - 0.032 * send
        private val headRotation = Quaternionf().rotateZ(headRoll.toFloat()).rotateY(headYaw.toFloat()).rotateX(headPitch.toFloat())

        private fun fromStage(p: P) = P(cos(facing) * p.x - sin(facing) * p.z, p.y, sin(facing) * p.x + cos(facing) * p.z)

        private fun torso(x: Double, y: Double, z: Double = 0.0): P {
            val up = (y - 0.04) * cos(bow) - z * sin(bow)
            return P(hipX - 0.031 + x * cos(lean) - up * sin(lean),
                0.04 + x * sin(lean) + up * cos(lean), z * cos(bow) + (y - 0.04) * sin(bow))
        }
        private fun hip(x: Double, y: Double, z: Double = 0.0) =
            P(hipX + x * cos(-0.12) - y * sin(-0.12), x * sin(-0.12) + y * cos(-0.12), z)

        private fun head(p: P): P {
            val root = torso(0.0, 0.698, 0.010)
            val turned = headRotation.transform(p.vec())
            return root.offset(turned.x.toDouble(), turned.y.toDouble(), turned.z.toDouble())
        }

        private fun box(m: Material, p: P, w: Double, h: Double, d: Double, roll: Double = 0.0,
            yaw: Double = 0.0, pitch: Double = 0.0, show: Double = 1.0): AuroraMiniaturePart {
            val s = fade * show
            return storyPart(m, p.x * fade, p.y * fade, p.z * fade, w * s, h * s, d * s, roll, yaw, pitch)
        }

        private fun line(m: Material, a: P, b: P, w: Double, d: Double, twist: Double = 0.0,
            join: Double = 0.19): AuroraMiniaturePart {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val xy = hypot(dx, dy)
            return box(m, a.at(b, 0.5), hypot(xy, dz) + join * w, w, d, atan2(dy, dx), -atan2(dz, xy), twist)
        }

        // Two strips share a narrow end. Their outside edges follow the actual taper all the way to the joint.
        private fun flank(m: Material, a: P, b: P, top: Double, bottom: Double, side: Int, d: Double): AuroraMiniaturePart {
            val length = hypot(b.x - a.x, b.y - a.y)
            val nx = (a.y - b.y) / length
            val ny = (b.x - a.x) / length
            val width = maxOf(top, bottom) * 0.54
            val da = side * (top - width) / 2
            val db = side * (bottom - width) / 2
            return line(m, a.offset(nx * da, ny * da, 0.0015 * side), b.offset(nx * db, ny * db, 0.0015 * side), width, d * 0.91, side * 0.25, 0.095)
        }

        private fun headBox(m: Material, p: P, w: Double, h: Double, d: Double, roll: Double = 0.0,
            yaw: Double = 0.0, show: Double = 1.0): AuroraMiniaturePart {
            val q = Quaternionf(headRotation)
                .rotateZ(roll.toFloat()).rotateY(yaw.toFloat())
            val world = head(p)
            val scale = (show * fade).toFloat()
            return AuroraMiniaturePart(m, AuroraOffset(world.x * fade, world.y * fade, world.z * fade),
                q, Vector3f(w.toFloat(), h.toFloat(), d.toFloat()).mul(scale))
        }

        private fun headLine(m: Material, a: P, b: P, w: Double, d: Double, twist: Double = 0.0): AuroraMiniaturePart {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val xy = hypot(dx, dy)
            val c = head(a.at(b, 0.5))
            val q = Quaternionf(headRotation)
                .rotateZ(atan2(dy, dx).toFloat()).rotateY((-atan2(dz, xy)).toFloat()).rotateX(twist.toFloat())
            return AuroraMiniaturePart(m, AuroraOffset(c.x * fade, c.y * fade, c.z * fade), q,
                Vector3f(((hypot(xy, dz) + 0.002) * fade).toFloat(), (w * fade).toFloat(), (d * fade).toFloat()))
        }

        fun part(i: Int): AuroraMiniaturePart {
            val mesh = localPart(i)
            if (i in 147..153) return mesh
            val p = mesh.center
            val position = sceneTurn.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
            return mesh.copy(center = AuroraOffset(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()),
                rotation = Quaternionf(sceneTurn).mul(mesh.rotation))
        }

        private fun localPart(i: Int): AuroraMiniaturePart = when (i) {
            in 0..25 -> leg(i >= 13, i % 13)
            in 26..60 -> body(i - 26)
            in 61..78 -> arms(i >= 70, (i - 61) % 9)
            in 79..113 -> portrait(i - 79)
            in 114..134 -> tresses(i - 114)
            in 135..146 -> ties(i - 135)
            in 147..153 -> heart(i - 147)
            in 154..174 -> faceVolume(i - 154)
            in 175..179 -> lock(4, i - 175, 5)
            in 180..184 -> lock(5, i - 180, 5)
            in 185..189 -> lock(0, i - 185 + 7, 12)
            in 190..194 -> lock(1, i - 190 + 6, 11)
            in 195..197 -> lock(2, i - 195 + 4, 7)
            in 198..200 -> lock(3, i - 198 + 4, 7)
            in 201..208 -> limbFinish(i - 201)
            in 209..216 -> handFinish(i - 209)
            in 217..224 -> fabricDetail(i - 217)
            in 225..231 -> portraitFinish(i - 225)
            in 232..239 -> skinJoins(i - 232)
            in 240..243 -> skinStrip(if (i < 242) frontLeg else backLeg, 5, if (i%2 == 0) -1 else 1)
            in 244..246 -> hipBlend(i - 244)
            else -> expression(i - 247)
        }

        private class Profile(val knots: List<P>, val widths: DoubleArray, val depths: DoubleArray) {
            // Shared normals give adjoining strips exactly the same silhouette endpoint at a knee or hip.
            val normals = knots.indices.map { i ->
                val above = knots[maxOf(0, i - 1)]
                val below = knots[minOf(i + 1, knots.lastIndex)]
                val length = hypot(above.y - below.y, below.x - above.x)
                P((above.y - below.y) / length, (below.x - above.x) / length)
            }
            fun edge(i: Int, side: Int) = knots[i].offset(normals[i].x * side * widths[i] / 2, normals[i].y * side * widths[i] / 2)
        }

        private val waist = Profile(listOf(torso(0.0, 0.342, -0.005), torso(0.0, 0.210, 0.004),
            torso(0.0, 0.091, 0.003), torso(0.0, 0.091, 0.003).at(hip(0.0, -0.120, -0.005), 0.47),
            hip(0.0, -0.125, -0.004), hip(0.0, -0.195, -0.007)), doubleArrayOf(0.339, 0.278, 0.243, 0.324, 0.445, 0.390),
            doubleArrayOf(0.218, 0.206, 0.191, 0.220, 0.242, 0.221))

        private fun legProfile(back: Boolean): Profile {
            val a = if (back) hip(-0.105, -0.109, -0.035) else hip(0.104, -0.113, 0.024)
            val knee = if (back) P(-0.129 - 0.020 * send, -0.702 + 0.028 * send, -0.117) else P(0.063 + 0.020 * gather, -0.715, 0.086)
            val ankle =  if (back) P(-0.109 + 0.013 * send, -1.151 + 0.036 * send, -0.135) else P(0.006, -1.231, 0.113)
            val thigh = a.at(knee, 0.44).offset(if (back) -0.020 else 0.018, 0.0, 0.019)
            val calf = knee.at(ankle, 0.32).offset(if (back) -0.004 else 0.018, 0.0, -0.025)
            val high = if (back) hip(-0.137, -0.231, -0.064) else hip(0.144, -0.234, 0.036)
            return Profile(listOf(a, high, thigh, knee.at(thigh, 0.17), knee.at(calf, 0.26), calf, ankle),
                doubleArrayOf(0.131, 0.217, 0.193, 0.136, 0.119, 0.141, 0.075),
                doubleArrayOf(0.145, 0.179, 0.165, 0.135, 0.120, 0.134, 0.091))
        }
        private val frontLeg = legProfile(false)
        private val backLeg = legProfile(true)

        private fun skinStrip(p: Profile, j: Int, side: Int): AuroraMiniaturePart {
            val a = p.edge(j, side)
            val b = p.edge(j + 1, side)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val distance = hypot(dx, dy)
            val nx = -dy * side / distance
            val ny = dx * side / distance
            val width = maxOf(p.widths[j], p.widths[j + 1]) * 0.548
            val depth = (p.depths[j] + p.depths[j + 1]) * 0.465
            return line(skin, a.offset(-nx * width / 2, -ny * width / 2), b.offset(-nx * width / 2, -ny * width / 2),
                width, depth, side * 0.065, 0.115)
        }

        // One leg carries the weight. The other foot steps back with its heel lifted.
        private fun leg(back: Boolean, i: Int): AuroraMiniaturePart {
            val profile = if (back) backLeg else frontLeg
            if (i < 8) return skinStrip(profile, i / 2, if (i%2 == 0) -1 else 1)
            val ankle = profile.knots.last()
            val toe = if (back) ankle.offset(-0.073, -0.113, 0.090) else ankle.offset(-0.102, -0.104, 0.109)
            return when (i) {
                8 -> line(skin, ankle.offset(0.012, 0.005, -0.017), toe, 0.066, 0.102)
                9 -> line(shadow, ankle.offset(0.033, -0.005, -0.061), ankle.offset(0.038, -0.111, -0.052), 0.020, 0.029)
                10 -> line(ink, toe.offset(-0.018, -0.006), toe.at(ankle, 0.26), 0.049, 0.111)
                11 -> line(ink, ankle.offset(-0.032, 0.044, 0.018), toe.offset(0.034, 0.022, 0.040), 0.018, 0.030)
                else -> line(oldGold, ankle.offset(0.029, 0.046, 0.020), ankle.offset(-0.035, -0.019, 0.057), 0.014, 0.025)
            }
        }

        private fun skinJoins(i: Int): AuroraMiniaturePart {
            if (i < 2) return skinStrip(waist, 4, if (i == 0) -1 else 1)
            if (i < 4) {
                val j = i - 1
                return line(skin, waist.knots[j].at(waist.knots[j - 1], 0.32), waist.knots[j].at(waist.knots[j + 1], 0.37),
                    0.81 * waist.widths[j], 0.96 * waist.depths[j], 0.025, 0.08)
            }
            val leg = if (i%2 == 0) frontLeg else backLeg
            val side = if (i%2 == 0) 1 else -1
            return if (i < 6) line(skin, hip(side * 0.119, -0.104, if (side > 0) 0.023 else -0.037),
                leg.knots[1].at(leg.knots[2], 0.18), 0.141, 0.151, side * 0.10, 0.09)
            else line(skin, leg.knots[3].at(leg.knots[2], 0.12), leg.knots[4].at(leg.knots[5], 0.40), 0.100, 0.112, 0.07)
        }

        private fun hipBlend(i: Int): AuroraMiniaturePart {
            if (i == 0) return line(skin, waist.knots[2].at(waist.knots[3], 0.42), waist.knots[3].at(waist.knots[4], 0.62),
                0.218, 0.213, 0.06, 0.12)
            val side = if (i == 1) -1 else 1
            return line(skin, hip(side * 0.111, -0.055, -0.050), hip(side * 0.129, -0.186, -0.068),
                0.137, 0.139, -side * 0.17, 0.10)
        }

        private fun body(i: Int): AuroraMiniaturePart = when (i) {
            0 -> line(skin, waist.knots[3].offset(0.0, -0.002, 0.005), waist.knots[5].offset(0.0, 0.011, 0.003),
                0.201, 0.226, 0.03)
            in 1..8 -> skinStrip(waist, (i - 1) / 2, if (i%2 == 1) -1 else 1)
            in 9..11 -> hipPanel(i - 9)
            in 12..15 -> {
                val side = if (i%2 == 0) -1 else 1
                val front = if (i < 14) 1 else -1
                line(ink, hip(side * 0.143, -0.114, front * 0.138), hip(side * 0.230, -0.060, front * 0.010),
                    0.017, 0.020, side * 0.10)
            }
            16 -> box(skin, torso(0.0, 0.437, -0.018), 0.335, 0.248, 0.201, lean, pitch = bow)
            17, 18 -> {
                val side = if (i == 17) -1 else 1
                line(skin, torso(side * 0.049, 0.609, -0.006), torso(side * 0.215, 0.557, -0.006), 0.080, 0.141, side * 0.19, 0.040)
            }
            19 -> box(skin, torso(0.0, 0.659, -0.003), 0.095, 0.171, 0.112, lean, pitch = bow)
            in 20..29 -> swimTop(if (i < 25) -1 else 1, (i - 20) % 5)
            30, 31 -> {
                val side = if (i == 30) -1 else 1
                line(oldGold, torso(side * 0.143, 0.508, 0.116), torso(side * 0.055, 0.643, 0.053), 0.013, 0.017)
            }
            32, 33 -> {
                val side = if (i == 32) -1 else 1
                line(ink, torso(side * 0.175, 0.415, -0.088), torso(0.0, 0.424, -0.131), 0.022, 0.019)
            }
            else -> box(gold, torso(0.0, 0.415, 0.174), 0.022, 0.022, 0.012, PI / 4 + lean)
        }

        private fun swimTop(side: Int, i: Int): AuroraMiniaturePart = when (i) {
            0 -> box(ink, torso(side * 0.099, 0.438, 0.100), 0.158, 0.124, 0.145, lean + side * 0.22, side * 0.28, bow - 0.16)
            1 -> line(ink, torso(side * 0.112, 0.449, 0.117), torso(side * 0.146, 0.511, 0.099), 0.097, 0.092, side * 0.31)
            2 -> line(ink, torso(side * 0.142, 0.429, 0.109), torso(side * 0.178, 0.426, 0.040), 0.088, 0.079, side * 0.20)
            3 -> line(ink, torso(side * 0.182, 0.416, 0.048), torso(side * 0.174, 0.415, -0.090), 0.020, 0.023)
            else -> line(Material.PURPLE_TERRACOTTA, torso(side * 0.040, 0.423, 0.179),
                torso(side * 0.122, 0.455, 0.169), 0.010, 0.013, side * 0.14)
        }

        private fun hipPanel(row: Int): AuroraMiniaturePart = box(ink, hip(0.0, -0.222 + 0.036 * row, 0.124),
            0.072 + 0.064 * row, 0.039, 0.061, -0.12)

        private fun panel(m: Material, a: P, b: P, normal: P, width: Double, depth: Double): AuroraMiniaturePart {
            val x = b.vec().sub(a.vec())
            val length = x.length()
            x.div(length)
            val z = normal.vec()
            z.fma(-z.dot(x), x).normalize()
            val y = Vector3f(z).cross(x)
            val q = Quaternionf().setFromNormalized(Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z))
            val c = a.at(b, 0.5)
            return AuroraMiniaturePart(m, AuroraOffset(c.x * fade, c.y * fade, c.z * fade), q,
                Vector3f(((length + 0.004) * fade).toFloat(), (width * fade).toFloat(), (depth * fade).toFloat()))
        }

        // Both relaxed elbows aim down. The independently articulated wrists stay forward of the hips.
        private fun poseArm(support: Boolean): Arm {
            val shoulder = torso(if (support) 0.220 else -0.215, 0.561, if (support) 0.013 else -0.027)
            val wrist = if (support) fromStage(P(0.240, -0.047, 0.247)
                .at(P(0.221, -0.056, 0.297), gather).at(P(0.218, -0.041, 0.305), send))
                else fromStage(P(-0.100, 0.011, 0.335).at(P(-0.154, 0.055, 0.399), gather)
                    .at(P(-0.234, 0.127, 0.428), send))
            val pole = if (support) fromStage(P(0.365, 0.107, 0.200)) else fromStage(P(-0.389, 0.098, 0.255))
            val upper = if (support) 0.300 else 0.311
            val fore = if (support) 0.302 else 0.311
            val toward = wrist.vec().sub(shoulder.vec())
            val dist = toward.length().toDouble()
            toward.normalize()
            val base = ((upper * upper - fore * fore + dist * dist) / (2 * dist)).coerceIn(-upper, upper)
            val elbow = toward.mul(base.toFloat(), Vector3f()).add(shoulder.vec())
            val bend = pole.vec().sub(shoulder.vec())
            bend.fma(-bend.dot(toward), toward).normalize()
            elbow.fma(sqrt((upper * upper - base * base).coerceAtLeast(0.0)).toFloat(), bend)
            val fingers = if (support) -2.33 + 0.09 * gather else -0.22 + 0.28 * gather + 0.10 * send
            return Arm(shoulder, P(elbow.x.toDouble(), elbow.y.toDouble(), elbow.z.toDouble()), wrist, fingers)
        }
        private val offeringArm = poseArm(false)
        private val restingArm = poseArm(true)
        private fun arm(support: Boolean) = if (support) restingArm else offeringArm

        private fun fingerStep(start: P, support: Boolean, angle: Double, length: Double, depth: Double = 0.0): P {
            val delta = P(cos(angle) * length, sin(angle) * length, depth + length * if (support) 0.14 else 0.20)
            val local = fromStage(delta)
            return start.offset(local.x, local.y, local.z)
        }

        private fun arms(support: Boolean, i: Int): AuroraMiniaturePart {
            val a = arm(support)
            val mid = a.elbow.at(a.wrist, 0.50)
            val palm = fingerStep(a.wrist, support, a.fingers, 0.046)
            val fingers = fingerStep(palm, support, a.fingers + 0.12, 0.051, 0.007)
            return when (i) {
                0, 1 -> flank(skin, a.shoulder, a.elbow.at(a.shoulder, 0.12), 0.119, 0.085, if (i == 0) -1 else 1, 0.095)
                2 -> line(skin, a.elbow.at(a.wrist, 0.13), mid, 0.086, 0.082, 0.045)
                3 -> line(skin, mid, a.wrist, 0.068, 0.067, 0.07)
                4 -> line(skin, a.wrist, palm, 0.057, 0.054, -0.11)
                5 -> line(skin, palm, fingers, 0.048, 0.039, -0.16)
                6 -> line(skin, fingers, fingerStep(fingers, support, a.fingers - 0.28, 0.047, 0.007), 0.018, 0.020)
                7 -> line(skin, palm, fingerStep(palm, support, a.fingers - 0.93, 0.047, 0.017), 0.020, 0.024)
                else -> line(oldGold, a.elbow.at(a.wrist, 0.78), a.elbow.at(a.wrist, 0.85), 0.077, 0.080)
            }
        }

        private fun limbFinish(i: Int): AuroraMiniaturePart {
            val back = i >= 4
            val profile = if (back) backLeg else frontLeg
            return when (i%4) {
                0, 1 -> skinStrip(profile, 4, if (i%4 == 0) -1 else 1)
                2 -> line(skin, profile.knots[1].at(profile.knots[2], 0.20).offset(0.0, 0.0, 0.029),
                    profile.knots[2].at(profile.knots[3], 0.73).offset(0.0, 0.0, 0.016), 0.101, 0.114, 0.07)
                else -> {
                    val ankle = profile.knots.last()
                    val toe = if (back) ankle.offset(-0.073, -0.113, 0.090) else ankle.offset(-0.102, -0.104, 0.109)
                    line(ink, toe.offset(-0.012, -0.018, 0.004), toe.at(ankle, 0.35).offset(0.008, -0.017, -0.006),
                        0.040, 0.102, 0.07)
                }
            }
        }

        private fun handFinish(i: Int): AuroraMiniaturePart {
            val support = i >= 4
            val arm = arm(support)
            val s = if (support) -1 else 1
            val palm = fingerStep(arm.wrist, support, arm.fingers, 0.048)
            return when (i % 4) {
                0, 1 -> {
                    val j = i % 4
                    val start = fingerStep(palm, support, arm.fingers + 0.11, 0.037 - 0.003 * j, 0.014 + 0.012 * j)
                    val tip = fingerStep(start, support, arm.fingers - 0.21 - 0.32 * j, 0.059 - 0.010 * j, 0.012 + 0.008 * j)
                    line(skin, start, tip, 0.016, 0.018, 0.04, 0.14)
                }
                2 -> line(skin, arm.elbow.at(arm.shoulder, 0.14), arm.elbow.at(arm.wrist, 0.16), 0.082, 0.089, 0.16, 0.075)
                else -> line(skin, arm.shoulder.at(arm.elbow, 0.03).offset(0.0, 0.021, -0.007),
                    arm.shoulder.at(arm.elbow, 0.26), 0.110, 0.115, -s * 0.34, 0.22)
            }
        }

        private fun fabricDetail(i: Int): AuroraMiniaturePart = when (i) {
            0, 1 -> {
                val side = if (i == 0) -1 else 1
                box(ink, hip(side * 0.230, -0.060, 0.003), 0.037, 0.028, 0.035, side * 0.54 - 0.12)
            }
            2 -> hipPanel(3)
            3, 4 -> {
                val side = if (i == 3) -1 else 1
                line(ink, hip(side * 0.032, -0.227, 0.123), hip(side * 0.145, -0.107, 0.123),
                    0.037, 0.060, side * 0.032, 0.02)
            }
            5 -> box(ink, hip(0.0, -0.163, -0.113), 0.214, 0.112, 0.064, -0.12)
            else -> {
                val side = if (i == 6) -1 else 1
                panel(ink, hip(side * 0.057, -0.205, -0.128), hip(side * 0.148, -0.115, -0.113),
                    P(side * 0.46, 0.085, -0.89), 0.073, 0.043)
            }
        }

        private fun portraitFinish(i: Int): AuroraMiniaturePart {
            val s = if (i % 2 == 0) -1 else 1
            if (i < 2) {
                val open = if (s < 0) 1.0 else 1 - storyBeat(t, 0.342, 0.396) * (1 - storyBeat(t, 0.568, 0.642))
                return headBox(Material.BLACK_TERRACOTTA, P(s * 0.068 - 0.010, 0.191 - 0.012 * open, 0.157),
                    0.013, 0.027 * open, 0.007, show = if (open == 0.0) 0.0 else 1.0)
            }
            if (i < 4) return headBox(hair, P(s * 0.108, 0.208, -0.092), 0.106, 0.137, 0.137, -s * 0.24, s * 0.51)
            return when (i) {
                4 -> line(hair, head(P(-0.109, 0.296, -0.057)), head(P(-0.052, 0.366, -0.064)), 0.071, 0.104, -0.23)
                5 -> headLine(hair, P(0.112, 0.246, 0.138), P(0.146, 0.248, 0.113), 0.022, 0.026, 0.27)
                else -> headBox(hair, P(0.080, 0.207, -0.160), 0.125, 0.185, 0.097, 0.14, 0.42)
            }
        }

        private fun portrait(i: Int): AuroraMiniaturePart {
            fun hb(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
                r: Double = 0.0, yaw: Double = 0.0) = headBox(m, P(x, y, z), w, h, d, r, yaw)
            fun hs(m: Material, a: P, b: P, w: Double, d: Double) = line(m, head(a), head(b), w, d)
            return when (i) {
                0 -> hb(skin, 0.0, 0.221, 0.036, 0.224, 0.132, 0.188)
                1 -> hb(skin, -0.002, 0.132, 0.054, 0.146, 0.065, 0.150, -0.012)
                2, 3 -> {
                    val side = if (i == 2) -1 else 1
                    hb(skin, side * 0.084, 0.192, 0.043, 0.109, 0.132, 0.137, -side * 0.14, side * 0.39)
                }
                4 -> hb(hair, 0.0, 0.241, -0.083, 0.220, 0.238, 0.195, 0.04)
                5 -> hb(hair, -0.003, 0.328, -0.031, 0.198, 0.076, 0.198, -0.07)
                6 -> hs(hair, P(-0.143, 0.267, 0.082), P(-0.045, 0.355, 0.057), 0.080, 0.116)
                7 -> headLine(hairLight, P(-0.037, 0.367, 0.068), P(0.046, 0.337, 0.121), 0.068, 0.044, -0.11)
                8 -> headLine(hair, P(0.039, 0.342, 0.119), P(0.076, 0.276, 0.149), 0.073, 0.048, 0.13)
                9 -> hs(hairLight, P(-0.041, 0.353, 0.090), P(-0.115, 0.279, 0.129), 0.054, 0.052)
                10 -> hs(hair, P(-0.144, 0.255, 0.089), P(-0.161, 0.116, 0.111), 0.067, 0.096)
                11 -> hs(hairLight, P(0.142, 0.263, 0.079), P(0.163, 0.125, 0.098), 0.061, 0.078)
                12 -> hs(oldGold, P(0.075, 0.313, -0.050), P(0.198, 0.416, -0.071), 0.014, 0.021)
                13 -> hb(gold, 0.119, 0.350, 0.027, 0.055, 0.055, 0.038, PI / 4)
                in 14..25 -> eye(i - 14)
                26 -> hb(skin, -0.004, 0.140, 0.143, 0.026, 0.045, 0.036, -0.13, -0.16)
                27 -> hs(Material.PINK_TERRACOTTA, P(-0.028, 0.114, 0.133), P(-0.004, 0.109, 0.139), 0.009, 0.010)
                28 -> hs(Material.PINK_TERRACOTTA, P(-0.004, 0.109, 0.139), P(0.027, 0.119, 0.132), 0.009, 0.010)
                29, 30, 31, 32 -> {
                    val s = if (i < 31) -1 else 1
                    val end = P(s * (0.159 + 0.008 * breeze), 0.006, 0.121)
                    if ((i - 29) % 2 == 0) hs(oldGold, P(s * 0.153, 0.141, 0.106), end, 0.012, 0.015)
                    else headBox(gold, end, 0.034, 0.050, 0.026, PI / 4)
                }
                else -> {
                    val s = if (i == 33) -1 else 1
                    hb(Material.MAGENTA_TERRACOTTA, s * 0.074, 0.235, 0.134, 0.050, 0.006, 0.009, s * 0.054)
                }
            }
        }

        private fun eye(i: Int): AuroraMiniaturePart {
            val s = if (i < 6) -1 else 1
            val wink = if (s > 0) storyBeat(t, 0.342, 0.396) * (1 - storyBeat(t, 0.568, 0.642)) else 0.0
            val open = 1 - wink
            val x = s * 0.068
            val on = if (open > 0.0) 1.0 else 0.0
            return when (i % 6) {
                0 -> headBox(pearl, P(x, 0.191 - 0.010 * open, 0.135), 0.084, 0.031 * open, 0.016, s * 0.07, show = on)
                1 -> headBox(Material.BROWN_TERRACOTTA, P(x - 0.007, 0.195 - 0.018 * open, 0.147),
                    0.038, 0.032 * open, 0.013, show = on)
                2 -> headBox(oldGold, P(x - 0.006, 0.194 - 0.029 * open, 0.154), 0.025, 0.014 * open, 0.010, show = on)
                3 -> headBox(Material.BLACK_TERRACOTTA, P(x - s * 0.013, 0.194, 0.158), 0.061, 0.009, 0.014, s * 0.13)
                4 -> headBox(Material.BLACK_TERRACOTTA, P(x + s * 0.034, 0.199, 0.155), 0.042, 0.008, 0.014,
                    s * (0.19 + 0.26 * wink))
                else -> headBox(pearl, P(x - 0.015, 0.197 - 0.010 * open, 0.161), 0.013, 0.013 * open, 0.009, show = on)
            }
        }

        private fun faceVolume(i: Int): AuroraMiniaturePart {
            fun h(m: Material, x: Double, y: Double, z: Double, w: Double, height: Double, d: Double,
                r: Double = 0.0, yaw: Double = 0.0) = headBox(m, P(x, y, z), w, height, d, r, yaw)
            fun l(m: Material, a: P, b: P, w: Double, d: Double, twist: Double = 0.0) =
                line(m, head(a), head(b), w, d, twist, 0.10)
            return when (i) {
                0, 1 -> {
                    val side = if (i == 0) -1 else 1
                    headLine(skin, P(side * 0.013, 0.081, 0.062), P(side * 0.106, 0.204, 0.059),
                        0.070, 0.130, side * 0.025)
                }
                2 -> h(skin, 0.0, 0.098, 0.057, 0.119, 0.037, 0.143, -0.004)
                3 -> h(skin, -0.002, 0.077, 0.058, 0.086, 0.033, 0.137, -0.003)
                4 -> h(skin, -0.003, 0.061, 0.063, 0.077, 0.021, 0.112, -0.025)
                5 -> h(hair, 0.0, 0.229, -0.129, 0.258, 0.146, 0.111, 0.06)
                6 -> h(hair, -0.014, 0.364, -0.034, 0.136, 0.041, 0.167, -0.057)
                7, 8 -> {
                    val side = if (i == 7) -1 else 1
                    h(hair, side * 0.102, 0.271, -0.085, 0.113, 0.163, 0.135, -side * 0.24, side * 0.51)
                }
                9 -> l(hairLight, P(-0.115, 0.282, 0.128), P(-0.146, 0.210, 0.143), 0.045, 0.050)
                10 -> l(hair, P(-0.159, 0.133, 0.106), P(-0.126, 0.068, 0.130), 0.043, 0.054)
                11 -> l(hair, P(-0.132, 0.078, 0.129), P(-0.110, 0.061, 0.126), 0.019, 0.035)
                12 -> l(hairLight, P(0.160, 0.140, 0.105), P(0.133, 0.055, 0.127), 0.043, 0.047)
                13 -> l(hairLight, P(0.134, 0.059, 0.125), P(0.148, 0.019, 0.135), 0.022, 0.028)
                14 -> headLine(hair, P(0.072, 0.284, 0.145), P(0.117, 0.242, 0.137), 0.048, 0.038, 0.14)
                15 -> l(hairLight, P(-0.115, 0.327, -0.019), P(-0.064, 0.370, -0.039), 0.045, 0.070)
                16 -> l(hairLight, P(-0.066, 0.370, -0.041), P(-0.007, 0.379, -0.025), 0.037, 0.056)
                17 -> h(Material.PINK_PETALS, 0.136, 0.358, 0.064, 0.104, 0.103, 0.34, -0.08, 0.22)
                    .copy(generatedSprite = true)
                18 -> h(hair, 0.096, 0.314, -0.146, 0.127, 0.107, 0.126, 0.60, 0.5)
                19 -> l(hairLight, P(0.073, 0.343, -0.169), P(0.137, 0.329, -0.173), 0.032, 0.059)
                else -> h(Material.CHERRY_PLANKS, -0.002, 0.105, 0.138, 0.031, 0.007, 0.009)
            }
        }

        private fun expression(i: Int): AuroraMiniaturePart {
            if (i < 6) {
                val side = if (i < 3) -1 else 1
                val blush = 0.92 + 0.08 * gather
                return when (i % 3) {
                    0 -> headBox(Material.PINK_WOOL, P(side * 0.085, 0.145, 0.130),
                        0.053 * blush, 0.017, 0.015, side * 0.04, side * 0.30)
                    1 -> headLine(hair, P(side * 0.073, 0.138, 0.143), P(side * 0.080, 0.152, 0.143), 0.007, 0.008)
                    else -> headLine(hair, P(side * 0.091, 0.139, 0.140), P(side * 0.098, 0.153, 0.140), 0.007, 0.008)
                }
            }
            return when (i) {
                6 -> headLine(hair, P(-0.074, 0.353, 0.094), P(-0.032, 0.322, 0.129), 0.057, 0.044, -0.12)
                7 -> headLine(hair, P(-0.032, 0.326, 0.131), P(-0.021, 0.275, 0.151), 0.040, 0.034, -0.16)
                8 -> headLine(hair, P(-0.020, 0.276, 0.150), P(-0.033, 0.249, 0.154), 0.020, 0.022, 0.15)
                9 -> headLine(hairLight, P(0.018, 0.356, 0.093), P(0.080, 0.327, 0.118), 0.032, 0.040, 0.14)
                10 -> headLine(hair, P(0.142, 0.218, 0.051), P(0.197, 0.170, 0.023), 0.067, 0.084, -0.17)
                else -> headLine(hair, P(0.198, 0.174, 0.024), P(0.190, 0.126, 0.042), 0.041, 0.060, 0.32)
            }
        }

        private fun tresses(i: Int): AuroraMiniaturePart = when (i) {
            in 0..6 -> lock(0, i, 12)
            in 7..12 -> lock(1, i - 7, 11)
            in 13..16 -> lock(2, i - 13, 7)
            else -> lock(3, i - 17, 7)
        }

        private fun lock(which: Int, j: Int, n: Int): AuroraMiniaturePart {
            val u = j.toDouble() / n
            val v = (j + 1.0) / n
            val q = (u + v) / 2
            val side = if (which % 2 == 0) -1 else 1
            fun point(k: Double): P {
                val wave = k * k * (0.047 * sin(2 * PI * t - 1.8 * k + which * 0.53) + 0.038 * send)
                return head(hairCurve(locks[which], k).offset(side * wave, wave * 0.53,
                    k * k * 0.048 * sin(2 * PI * t + which * 0.78)))
            }
            val broad = which < 2 || which >= 4
            val width = (if (broad) 0.136 else 0.063) * (1 - 0.974 * q * q)
            val material = when (which) {
                0, 1 -> hair
                2, 3 -> hairLight
                else -> Material.STRIPPED_CHERRY_WOOD
            }
            return line(material, point(u), point(v), width, (if (broad) 0.114 else 0.047) * (1 - 0.78 * q),
                side * (0.19 + 0.51 * sin(1.6 * PI * q)), 0.16)
        }

        private fun ties(i: Int): AuroraMiniaturePart {
            val pin = hip(-0.237, -0.157, 0.072)
            return when (i) {
                0 -> box(gold, pin, 0.045, 0.054, 0.028, PI / 4)
                1 -> box(Material.DIAMOND_BLOCK, pin.offset(0.001, 0.0, 0.014), 0.031, 0.038, 0.018, PI / 4)
                in 2..5 -> {
                    val side = if (i < 4) 1 else -1
                    val tip = i%2
                    val knot = hip(side * 0.231, -0.060, 0.012)
                    val end = if (i == 4) pin else knot.offset(side * (0.035 - 0.020 * tip), -0.044 - 0.032 * tip, 0.025 - 0.037 * tip)
                    line(if (i == 4) oldGold else ink, knot, end,
                        0.011, 0.013, side * 0.16)
                }
                6, 7 -> {
                    val side = if (i == 6) -1 else 1
                    line(oldGold, torso(side * 0.054, 0.643, 0.050), torso(side * 0.057, 0.645, -0.055), 0.012, 0.017)
                }
                8, 9 -> {
                    val a = arm(i == 9)
                    line(skin, a.shoulder.at(a.elbow, 0.035).offset(0.0, 0.012, 0.018),
                        a.shoulder.at(a.elbow, 0.19).offset(0.0, 0.004, 0.017), 0.103, 0.098, 0.32)
                }
                else -> {
                    val a = if (i == 10) frontLeg.knots.last() else backLeg.knots.last()
                    line(ink, a.offset(0.019, -0.035, -0.040), a.offset(0.018, 0.011, -0.040), 0.049, 0.066, 0.10)
                }
            }
        }

        private fun heart(i: Int): AuroraMiniaturePart {
            val hand = arm(false)
            val local = fingerStep(hand.wrist, false, hand.fingers, 0.100)
            val stage = sceneTurn.transform(local.vec())
            val palm = P(stage.x.toDouble() - 0.010, stage.y.toDouble() + 0.160, stage.z.toDouble() + 0.055)
            val flight = storyBeat(t, 0.535, 0.862)
            val arc = sin(PI * flight)
            val p = palm.at(P(-1.020, 1.129, 0.398), flight).offset(-0.173 * arc, 0.110 * arc, 0.021 * arc)
            val s = storyBeat(t, 0.301, 0.431) * (1 - storyBeat(t, 0.786, 0.895))
            val pixel = 0.045 * s
            val row = heartRows[i]
            return box(if (i == 6) Material.CHERRY_PLANKS else heartColor, p.offset(row[0] * pixel, row[1] * pixel),
                row[2] * pixel, row[3] * pixel, (if (i == 6) 0.066 else 0.041 + 0.002 * i) * s)
        }
    }

    private val heartRows = listOf(doubleArrayOf(-2.0, 2.60, 2.25, 1.26), doubleArrayOf(2.0, 2.60, 2.25, 1.26),
        doubleArrayOf(0.0, 1.02, 8.05, 2.03), doubleArrayOf(0.0, -0.65, 6.0, 1.37), doubleArrayOf(0.0, -1.84, 4.0, 1.13),
        doubleArrayOf(0.0, -2.86, 1.95, 1.00), doubleArrayOf(-2.0, 1.68, 1.12, 0.76))
}
