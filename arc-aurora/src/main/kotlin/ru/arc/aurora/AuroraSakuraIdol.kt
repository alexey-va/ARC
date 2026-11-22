package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A rose-haired soloist turns from her upturned palm as a heart separates from her fingertips. */
internal object AuroraSakuraIdol : AuroraStoryParts {
    const val PART_COUNT = 154

    private val skin = Material.WHITE_TERRACOTTA
    private val ink = Material.BLUE_TERRACOTTA
    private val shadow = Material.GRAY_TERRACOTTA
    private val gold = Material.GOLD_BLOCK
    private val oldGold = Material.YELLOW_TERRACOTTA
    private val pearl = Material.SMOOTH_QUARTZ
    private val hair = Material.STRIPPED_CHERRY_WOOD
    private val hairLight = Material.CHERRY_PLANKS
    private val hairDark = Material.PINK_CONCRETE

    override fun partCount(shape: AuroraShape) = PART_COUNT
    override fun part(shape: AuroraShape, index: Int, t: Double) = Pose(t).part(index)

    private data class P(val x: Double, val y: Double, val z: Double = 0.0) {
        fun offset(dx: Double, dy: Double, dz: Double = 0.0) = P(x + dx, y + dy, z + dz)
        fun at(b: P, u: Double) = offset((b.x - x) * u, (b.y - y) * u, (b.z - z) * u)
        fun along(angle: Double, length: Double) = offset(cos(angle) * length, sin(angle) * length)
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

    private class Pose(val t: Double) {
        private val fade = storyBeat(t, 0.0, 0.065) * (1 - storyBeat(t, 0.932, 1.0))
        private val gather = storyBeat(t, 0.076, 0.323) * (1 - storyBeat(t, 0.735, 0.927))
        private val send = storyBeat(t, 0.447, 0.601) * (1 - storyBeat(t, 0.761, 0.910))
        private val breeze = sin(2 * PI * t)
        private val hipX = 0.062 + 0.032 * gather - 0.027 * send
        private val lean = 0.160 + 0.073 * gather + 0.052 * send
        private val headRoll = -0.033 + 0.078 * gather - 0.094 * send
        private val headYaw = -0.26 - 0.12 * gather + 0.13 * send

        private fun torso(x: Double, y: Double, z: Double = 0.0) =
            P(hipX - 0.070 + x * cos(lean) - (y - 0.04) * sin(lean),
                0.04 + x * sin(lean) + (y - 0.04) * cos(lean), z)
        private fun hip(x: Double, y: Double, z: Double = 0.0) =
            P(hipX + x * cos(-0.12) - y * sin(-0.12), x * sin(-0.12) + y * cos(-0.12), z)

        private fun head(p: P): P {
            val root = torso(0.0, 0.698, 0.010)
            val x = p.x * cos(headYaw) + p.z * sin(headYaw)
            return root.offset(x * cos(headRoll) - p.y * sin(headRoll), x * sin(headRoll) + p.y * cos(headRoll),
                p.z * cos(headYaw) - p.x * sin(headYaw))
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
            val width = maxOf(top, bottom) * 0.52
            val da = side * (top - width) / 2
            val db = side * (bottom - width) / 2
            return line(m, a.offset(nx * da, ny * da, 0.0015 * side), b.offset(nx * db, ny * db, 0.0015 * side), width, d)
        }

        private fun headBox(m: Material, p: P, w: Double, h: Double, d: Double, roll: Double = 0.0,
            yaw: Double = 0.0, show: Double = 1.0): AuroraMiniaturePart {
            val q = Quaternionf().rotateZ(headRoll.toFloat()).rotateY(headYaw.toFloat())
                .rotateZ(roll.toFloat()).rotateY(yaw.toFloat())
            val world = head(p)
            val scale = (show * fade).toFloat()
            return AuroraMiniaturePart(m, AuroraOffset(world.x * fade, world.y * fade, world.z * fade),
                q, Vector3f(w.toFloat(), h.toFloat(), d.toFloat()).mul(scale))
        }

        fun part(i: Int): AuroraMiniaturePart = when (i) {
            in 0..25 -> leg(i >= 13, i % 13)
            in 26..60 -> body(i - 26)
            in 61..78 -> arms(i >= 70, (i - 61) % 9)
            in 79..113 -> portrait(i - 79)
            in 114..134 -> tresses(i - 114)
            in 135..146 -> silkTrain(i - 135)
            else -> heart(i - 147)
        }

        // One toe carries the weight. The other ankle crosses behind it, with its heel lifted.
        private fun leg(back: Boolean, i: Int): AuroraMiniaturePart {
            val a = if (back) hip(-0.143, -0.145, -0.084) else hip(0.137, -0.146, 0.043)
            val knee = if (back) P(-0.161 - 0.032 * send, -0.689 + 0.038 * send, -0.129)
                else P(0.063 + 0.020 * gather, -0.715, 0.086)
            val ankle = if (back) P(0.052 + 0.042 * send, -1.137 + 0.055 * send, -0.097)
                else P(-0.108, -1.231, 0.102)
            val toe = if (back) ankle.offset(-0.073, -0.113, 0.090) else ankle.offset(-0.102, -0.104, 0.109)
            val mid = a.at(knee, 0.48)
            val calf = knee.at(ankle, 0.34).offset(if (back) 0.010 else -0.008, 0.0, -0.011)
            return when (i) {
                0, 1 -> flank(skin, a, mid, 0.237, 0.184, if (i == 0) -1 else 1, 0.177)
                2, 3 -> flank(skin, mid, knee, 0.184, 0.131, if (i == 2) -1 else 1, 0.137)
                4, 5 -> flank(skin, knee, calf, 0.121, 0.137, if (i == 4) -1 else 1, 0.115)
                6, 7 -> flank(skin, calf, ankle, 0.137, 0.076, if (i == 6) -1 else 1, 0.087)
                8 -> line(skin, ankle.offset(0.012, 0.005, -0.017), toe, 0.066, 0.102)
                9 -> line(shadow, ankle.offset(0.033, -0.005, -0.061), ankle.offset(0.038, -0.111, -0.052), 0.020, 0.029)
                10 -> line(ink, toe.offset(-0.018, -0.006), toe.at(ankle, 0.26), 0.049, 0.111)
                11 -> line(ink, ankle.offset(-0.032, 0.044, 0.018), toe.offset(0.034, 0.022, 0.040), 0.018, 0.030)
                else -> line(oldGold, ankle.offset(0.029, 0.046, 0.020), ankle.offset(-0.035, -0.019, 0.057), 0.014, 0.025)
            }
        }

        private fun body(i: Int): AuroraMiniaturePart = when (i) {
            0 -> box(skin, hip(0.0, -0.113, -0.013), 0.379, 0.147, 0.224, -0.12)
            in 1..4 -> {
                val side = if (i % 2 == 1) -1 else 1
                val widest = hip(side * 0.219, -0.109, -0.008)
                val end = if (i <= 2) hip(side * 0.080, 0.072, -0.004) else hip(side * 0.177, -0.202, -0.013)
                line(skin, end, widest, if (i <= 2) 0.086 else 0.096, 0.201, join = 0.04)
            }
            5, 6 -> flank(skin, torso(0.0, 0.319, -0.005), torso(0.0, 0.059, 0.0), 0.332, 0.237, if (i == 5) -1 else 1, 0.174)
            7, 8 -> flank(skin, torso(0.0, 0.074, 0.0), hip(0.0, -0.104, -0.003), 0.236, 0.373, if (i == 7) -1 else 1, 0.177)
            9 -> box(ink, hip(0.0, -0.145, -0.005), 0.280, 0.091, 0.275, -0.12)
            10, 11 -> {
                val side = if (i == 10) -1 else 1
                line(ink, hip(side * 0.091, -0.150, -0.004), hip(side * 0.019, -0.224, -0.004), 0.105, 0.276, join = 0.05)
            }
            12, 13 -> {
                val side = if (i == 12) -1 else 1
                line(ink, hip(side * 0.122, -0.133, 0.146), hip(side * 0.253, -0.074, 0.113), 0.027, 0.035)
            }
            14, 15 -> {
                val side = if (i == 14) -1 else 1
                line(ink, hip(side * 0.118, -0.141, -0.151), hip(side * 0.251, -0.074, -0.109), 0.030, 0.034)
            }
            16 -> box(skin, torso(0.0, 0.441, -0.027), 0.361, 0.275, 0.201, lean)
            17, 18 -> {
                val side = if (i == 17) -1 else 1
                line(skin, torso(side * 0.040, 0.609, -0.008), torso(side * 0.226, 0.568, -0.006), 0.120, 0.165)
            }
            19 -> box(skin, torso(0.0, 0.669, -0.003), 0.093, 0.210, 0.113, lean)
            in 20..29 -> cup(if (i < 25) -1 else 1, (i - 20) % 5)
            30, 31 -> {
                val side = if (i == 30) -1 else 1
                line(oldGold, torso(side * 0.168, 0.514, 0.116), torso(side * 0.043, 0.654, 0.058), 0.017, 0.022)
            }
            32, 33 -> {
                val side = if (i == 32) -1 else 1
                line(ink, hip(side * 0.253, -0.074, 0.113), hip(side * 0.251, -0.074, -0.109), 0.028, 0.035)
            }
            else -> box(gold, torso(0.0, 0.414, 0.214), 0.042, 0.042, 0.021, PI / 4 + lean)
        }

        // Three overlapping silk panels carry the volume around the ribcage. Their differing
        // tangents give the neckline and outer edges a curved silhouette when the figure turns.
        private fun cup(side: Int, i: Int): AuroraMiniaturePart = when (i) {
            0 -> box(ink, torso(side * 0.127, 0.438, 0.134), 0.179, 0.147, 0.210,
                lean + side * 0.255, side * 0.32, -0.22)
            1 -> box(ink, torso(side * 0.193, 0.446, 0.097), 0.105, 0.113, 0.166,
                lean - side * 0.18, side * 0.60, -0.29)
            2 -> box(ink, torso(side * 0.065, 0.432, 0.124), 0.102, 0.108, 0.181,
                lean + side * 0.52, side * 0.11, -0.36)
            3 -> line(ink, torso(side * 0.108, 0.361, -0.091), torso(side * 0.204, 0.393, 0.060), 0.048, 0.065)
            else -> line(Material.LIGHT_BLUE_TERRACOTTA, torso(side * 0.068, 0.415, 0.271),
                torso(side * 0.170, 0.465, 0.243), 0.017, 0.016)
        }

        // The two-link silhouette keeps the elbow bent as the wrist presents the heart.
        private fun arm(support: Boolean): Arm {
            val shoulder = torso(if (support) 0.220 else -0.215, 0.561, if (support) 0.013 else -0.027)
            val rest = if (support) P(-0.200, 0.310, 0.285) else P(-0.455, 0.474, 0.201)
            val held = if (support) P(-0.270, 0.477, 0.305) else P(-0.685, 0.727, 0.235)
            val release = if (support) P(-0.160, 0.345, 0.284) else P(-0.733, 0.825, 0.267)
            val wrist = rest.at(held, gather).at(release, send)
            val dx = wrist.x - shoulder.x
            val dy = wrist.y - shoulder.y
            val dist = hypot(dx, dy)
            val upper = if (support) 0.305 else 0.312
            val fore = if (support) 0.307 else 0.308
            val along = ((upper * upper - fore * fore + dist * dist) / (2 * dist)).coerceIn(-upper, upper)
            val bend = sqrt((upper * upper - along * along).coerceAtLeast(0.0))
            val elbow = P(shoulder.x + (dx * along - dy * bend) / dist,
                shoulder.y + (dy * along + dx * bend) / dist, if (support) 0.243 else 0.101)
            val fingers = if (support) 2.28 + 0.14 * gather + 0.26 * send else 1.99 - 0.53 * gather + 0.83 * send
            return Arm(shoulder, elbow, wrist, fingers)
        }

        private fun arms(support: Boolean, i: Int): AuroraMiniaturePart {
            val a = arm(support)
            val mid = a.elbow.at(a.wrist, 0.50)
            val palm = a.wrist.along(a.fingers, 0.043)
            val fingers = palm.along(a.fingers - 0.14, 0.052)
            return when (i) {
                0, 1 -> flank(skin, a.shoulder, a.elbow, 0.119, 0.084, if (i == 0) -1 else 1, 0.095)
                2 -> line(skin, a.elbow, mid, 0.091, 0.084, 0.06)
                3 -> line(skin, mid, a.wrist, 0.068, 0.067, 0.07)
                4 -> line(skin, a.wrist, palm, 0.067, 0.060)
                5 -> line(skin, palm, fingers, 0.054, 0.044)
                6 -> line(skin, fingers, fingers.along(a.fingers + 0.34, 0.035), 0.029, 0.032)
                7 -> line(skin, palm, palm.along(a.fingers + if (support) -0.89 else 0.91, 0.052), 0.026, 0.032)
                else -> line(oldGold, a.elbow.at(a.wrist, 0.78), a.elbow.at(a.wrist, 0.85), 0.077, 0.080)
            }
        }

        private fun portrait(i: Int): AuroraMiniaturePart {
            fun hb(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
                r: Double = 0.0, yaw: Double = 0.0) = headBox(m, P(x, y, z), w, h, d, r, yaw)
            fun hs(m: Material, a: P, b: P, w: Double, d: Double) = line(m, head(a), head(b), w, d)
            return when (i) {
                0 -> hb(skin, 0.0, 0.197, 0.024, 0.203, 0.224, 0.208)
                1 -> hb(skin, -0.004, 0.087, 0.030, 0.144, 0.084, 0.177)
                2, 3 -> {
                    val s = if (i == 2) -1 else 1
                    hb(skin, s * 0.103, 0.176, 0.015, 0.088, 0.159, 0.170, -s * 0.24, s * 0.37)
                }
                4 -> hb(hair, 0.004, 0.231, -0.071, 0.295, 0.282, 0.206)
                5 -> hb(hair, 0.003, 0.331, -0.008, 0.236, 0.106, 0.249, -0.06)
                6 -> hs(hair, P(-0.133, 0.291, 0.09), P(-0.046, 0.369, 0.052), 0.093, 0.173)
                7 -> hs(hairLight, P(-0.045, 0.360, 0.113), P(0.072, 0.263, 0.156), 0.073, 0.052)
                8 -> hs(hair, P(0.040, 0.365, 0.076), P(0.150, 0.244, 0.130), 0.083, 0.075)
                9 -> hs(hairLight, P(-0.065, 0.352, 0.111), P(-0.145, 0.244, 0.149), 0.057, 0.052)
                10 -> hs(hair, P(-0.149, 0.258, 0.084), P(-0.187, 0.022, 0.062), 0.059, 0.100)
                11 -> hs(hairLight, P(0.153, 0.269, 0.059), P(0.155, -0.006, 0.116), 0.055, 0.104)
                12 -> hs(oldGold, P(0.064, 0.325, -0.060), P(0.185, 0.480, -0.055), 0.017, 0.024)
                13 -> hb(gold, 0.103, 0.375, 0.045, 0.057, 0.057, 0.044, PI / 4)
                in 14..25 -> eye(i - 14)
                26 -> hb(skin, -0.004, 0.140, 0.143, 0.031, 0.044, 0.047, -0.13, -0.16)
                27 -> hs(Material.PINK_TERRACOTTA, P(-0.036, 0.111, 0.130), P(-0.004, 0.104, 0.139), 0.012, 0.015)
                28 -> hs(Material.PINK_TERRACOTTA, P(-0.004, 0.104, 0.139), P(0.030, 0.115, 0.131), 0.012, 0.015)
                29, 30, 31, 32 -> {
                    val s = if (i < 31) -1 else 1
                    val end = P(s * (0.159 + 0.008 * breeze), 0.006, 0.121)
                    if ((i - 29) % 2 == 0) hs(oldGold, P(s * 0.153, 0.141, 0.106), end, 0.012, 0.015)
                    else headBox(gold, end, 0.034, 0.050, 0.026, PI / 4)
                }
                else -> {
                    val s = if (i == 33) -1 else 1
                    hb(Material.MAGENTA_TERRACOTTA, s * 0.072, 0.227, 0.136, 0.059, 0.007, 0.009, -s * 0.085)
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
                0 -> headBox(pearl, P(x, 0.192 - 0.014 * open, 0.135), 0.086, 0.039 * open, 0.016, s * 0.07, show = on)
                1 -> headBox(Material.BROWN_TERRACOTTA, P(x - 0.007, 0.195 - 0.018 * open, 0.147),
                    0.043, 0.043 * open, 0.013, show = on)
                2 -> headBox(oldGold, P(x - 0.006, 0.194 - 0.029 * open, 0.154), 0.025, 0.017 * open, 0.010, show = on)
                3 -> headBox(Material.BLACK_TERRACOTTA, P(x - s * 0.013, 0.194, 0.158), 0.061, 0.011, 0.014, s * 0.13)
                4 -> headBox(Material.BLACK_TERRACOTTA, P(x + s * 0.034, 0.199, 0.155), 0.042, 0.010, 0.014,
                    s * (0.19 + 0.26 * wink))
                else -> headBox(pearl, P(x - 0.015, 0.197 - 0.010 * open, 0.161), 0.013, 0.013 * open, 0.009, show = on)
            }
        }

        // Broad rose locks share their roots with the fringe. The finer locks drift later and clear the face.
        private fun tresses(i: Int): AuroraMiniaturePart {
            val lock = when (i) { in 0..6 -> 0; in 7..12 -> 1; in 13..16 -> 2; else -> 3 }
            val start = when (lock) { 0 -> 0; 1 -> 7; 2 -> 13; else -> 17 }
            val n = when (lock) { 0 -> 7; 1 -> 6; else -> 4 }
            val j = i - start
            fun step(k: Int) = 1.0 - (1.0 - k.toDouble() / n).pow(1.28)
            val u = step(j)
            val v = step(j + 1)
            fun point(q: Double): P {
                val w = q * q * (0.045 * sin(2 * PI * t - 1.8 * q) + 0.120 * send)
                return head(when (lock) {
                    0 -> curve(P(-0.097, 0.307, -0.097), P(-0.08, -0.07, -0.134), P(-0.963, 0.009, -0.162),
                        P(-0.801, -0.557, -0.141), q).offset(-w, 0.65 * w)
                    1 -> curve(P(0.104, 0.284, -0.125), P(0.154, -0.115, -0.153), P(0.674, -0.517, -0.173),
                        P(0.315, -0.715, -0.123), q).offset(w, 0.50 * w)
                    2 -> curve(P(-0.128, 0.245, -0.010), P(-0.235, -0.071, -0.038), P(-0.711, 0.040, -0.069),
                        P(-1.009, -0.297, -0.071), q).offset(-w, w)
                    else -> curve(P(0.128, 0.208, -0.035), P(0.206, -0.202, -0.060), P(0.478, -0.352, -0.087),
                        P(0.526, -0.547, -0.051), q).offset(w, -0.50 * w)
                })
            }
            val main = lock <= 1
            val width = (if (main) 0.150 else 0.074) * (1 - 0.85 * v * v)
            val colour = if (main) hair else hairLight
            return line(colour, point(u), point(v), width, (if (main) 0.142 else 0.055) * (1 - 0.65 * v), join = 0.28)
        }

        private fun silkTrain(i: Int): AuroraMiniaturePart {
            val pin = hip(0.238, -0.072, 0.091)
            if (i < 3) return when (i) {
                0 -> box(gold, pin, 0.114, 0.113, 0.065, PI / 4)
                1 -> box(Material.DIAMOND_BLOCK, pin.offset(0.0, 0.0, 0.037), 0.070, 0.067, 0.018, PI / 4)
                else -> line(gold, pin.offset(0.0, -0.035, 0.005), pin.offset(0.026, -0.155, 0.017), 0.017, 0.022)
            }
            val edge = i >= 9
            val j = if (edge) i - 8 else i - 3
            val u = j / 6.0
            val v = (j + 1) / 6.0
            fun width(q: Double) = 0.108 + 0.032 * sin(PI * q) - 0.074 * q * q
            fun sample(q: Double): P {
                val wind = q * q * (0.098 * sin(2 * PI * t - q * 1.7) + 0.156 * send)
                val p = curve(pin.offset(0.0, -0.025, -0.110), P(0.391, -0.276, -0.152),
                    P(0.807, -0.654, -0.209), P(0.512, -1.176, -0.133), q).offset(wind, wind * 0.59)
                return if (edge) p.offset(-width(q) * 0.30, 0.0, 0.026) else p
            }
            return if (edge) line(oldGold, sample(u), sample(v), 0.012, 0.012)
            else line(ink, sample(u), sample(v), width((u + v) / 2), 0.035, join = 0.34)
        }

        private fun heart(i: Int): AuroraMiniaturePart {
            val hand = arm(false)
            val palm = hand.wrist.along(hand.fingers, 0.120).offset(-0.087, 0.125, 0.002)
            val flight = storyBeat(t, 0.535, 0.862)
            val p = palm.at(P(-1.123, 1.213, 0.305), flight).offset(0.0, 0.106 * sin(PI * flight))
            val s = storyBeat(t, 0.301, 0.431) * (1 - storyBeat(t, 0.786, 0.895))
            val pixel = 0.045 * s
            val row = heartRows[i]
            return box(if (i == 6) hairLight else hairDark, p.offset(row[0]*pixel, row[1]*pixel),
                row[2]*pixel, row[3]*pixel, (if (i == 6) 0.066 else 0.041+0.002 * i) * s)
        }
    }

    private val heartRows = listOf(doubleArrayOf(-2.0, 2.60, 2.25, 1.26), doubleArrayOf(2.0, 2.60, 2.25, 1.26),
        doubleArrayOf(0.0, 1.02, 8.05, 2.03), doubleArrayOf(0.0, -0.65, 6.0, 1.37), doubleArrayOf(0.0, -1.84, 4.0, 1.13),
        doubleArrayOf(0.0, -2.86, 1.95, 1.00), doubleArrayOf(-2.0, 1.68, 1.12, 0.76))
}
