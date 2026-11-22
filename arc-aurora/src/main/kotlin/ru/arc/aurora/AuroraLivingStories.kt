package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Three sky stories built from fixed ItemDisplay slots in the viewer-facing XY plane. */
internal object AuroraLivingStories : AuroraStoryParts {
    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.ENDERMAN_SKY_THEFT, AuroraShape.CLOUD_FOX, AuroraShape.GHAST_PORTAL -> 24
        else -> error("Not a living sky story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (shape) {
        AuroraShape.ENDERMAN_SKY_THEFT -> enderman(index, t)
        AuroraShape.CLOUD_FOX -> fox(index, t)
        AuroraShape.GHAST_PORTAL -> ghast(index, t)
        else -> error("Not a living sky story: $shape")
    }

    private fun mix(a: Double, b: Double, weight: Double) = a + (b - a) * weight

    private fun between(a: AuroraOffset, b: AuroraOffset, weight: Double) =
        AuroraOffset(mix(a.x, b.x, weight), mix(a.y, b.y, weight), mix(a.z, b.z, weight))

    /** A cuboid along the segment. Neighbouring parts share the actual animated joint. */
    private fun limb(material: Material, a: AuroraOffset, b: AuroraOffset, width: Double, visible: Double): AuroraMiniaturePart {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        val xy = hypot(dx, dy)
        return storyPart(material, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            width * visible, (hypot(xy, dz) + width * 0.16) * visible, width * visible,
            roll = atan2(-dx, dy), pitch = atan2(dz, xy))
    }

    private val stars = listOf(
        AuroraOffset(-0.32, 0.27, 0.066), AuroraOffset(0.20, 0.20, 0.102),
        AuroraOffset(-0.04, -0.05, 0.046), AuroraOffset(-0.26, -0.29, 0.071),
        AuroraOffset(0.30, -0.26, 0.083),
    )

    private fun enderman(index: Int, t: Double): AuroraMiniaturePart {
        fun beat(start: Double, end: Double) = storyBeat(t, start / 13.0, end / 13.0)
        val seconds = t * 13.0
        val reveal = beat(0.75, 2.08)
        val reach = beat(2.12, 4.15)
        val pull = beat(4.62, 6.85)
        val tuck = beat(6.50, 7.90)
        val run = beat(9.05, 12.18)
        val fade = 1.0 - beat(11.90, 12.45)
        val stride = sin((seconds - 9.03) * 9.2) * (beat(9.05, 9.39) - beat(11.88, 12.28))
        val body = reveal * fade
        val x = -0.30 + 0.12 * reach - 0.17 * pull - 0.42 * run
        val hip = -0.15 - 0.03 * reach + 0.045 * abs(stride)
        val lean = 0.045 * reach - 0.095 * pull - 0.035 * run
        val shoulder = hip + 0.46
        val headX = x + lean

        if (index in 0..4) {
            val yaw = 0.18 * reach - 0.38 * run
            val roll = 0.10 * (beat(1.54, 2.26) - beat(4.15, 4.69)) + 0.07 * run
            val headY = shoulder + 0.20
            if (index == 0) return storyPart(Material.BLACK_WOOL, headX, headY, 0.0,
                0.34 * body, 0.32 * body, 0.30 * body, roll, yaw)
            val inset = index >= 3
            val side = if (index == 1 || index == 3) -1.0 else 1.0
            val lateral = side * 0.099
            val depth = if (inset) 0.166 else 0.157
            val horizontal = lateral * cos(yaw) + depth * sin(yaw)
            val eye = beat(0.02, 0.30) * fade * (1.0 - 0.85 * (beat(0.72, 0.81) - beat(0.84, 0.96)))
            return storyPart(if (inset) Material.PINK_CONCRETE else Material.MAGENTA_CONCRETE,
                headX + horizontal * cos(roll), headY + horizontal * sin(roll),
                depth * cos(yaw) - lateral * sin(yaw), (if (inset) 0.043 else 0.117) * eye,
                0.046 * eye, 0.014 * eye, roll, yaw)
        }
        if (index == 5) return limb(Material.BLACK_WOOL, AuroraOffset(headX, shoulder, 0.0),
            AuroraOffset(x, hip, 0.0), 0.21, body)

        val tileX = mix(0.65, x + 0.37, pull)
        val tileY = mix(0.26, hip + 0.12, pull) + 0.091 * sin(PI * pull)
        val tileZ = mix(-0.12, 0.125, pull)
        val size = mix(0.55, 0.49, tuck)
        val tileRoll = 0.15 * sin(PI * pull) - 0.065 * tuck - 0.028 * stride
        fun tilePoint(u: Double, v: Double, z: Double) = AuroraOffset(
            tileX + size * (u * cos(tileRoll) - v * sin(tileRoll)),
            tileY + size * (u * sin(tileRoll) + v * cos(tileRoll)), tileZ + z)

        if (index in 6..9) {
            val far = index < 8
            val start = AuroraOffset(headX + if (far) -0.116 else 0.113, shoulder - 0.023, if (far) -0.04 else 0.075)
            val rest = AuroraOffset(x + if (far) -0.16 - 0.23 * stride else 0.155, hip - 0.16, start.z + 0.02)
            val grip = if (far) tilePoint(-0.44, 0.43, 0.047) else tilePoint(-0.45, -0.37, 0.050)
            val hand = between(between(rest, grip, reach), if (far) rest else tilePoint(0.24, 0.30, 0.058), tuck)
            val restElbow = between(start, rest, 0.52)
            val gripElbow = if (far) AuroraOffset((start.x + grip.x) * 0.5, shoulder - 0.025, -0.017)
                else AuroraOffset((start.x + grip.x) * 0.5, hip + 0.018, 0.12)
            val elbow = between(between(restElbow, gripElbow, reach),
                if (far) restElbow else AuroraOffset(x + 0.16, shoulder - 0.14, 0.19), tuck)
            return if (index % 2 == 0) limb(Material.BLACK_WOOL, start, elbow, 0.075, body)
            else limb(Material.BLACK_WOOL, elbow, hand, 0.072, body)
        }
        if (index in 10..13) {
            val side = if (index < 12) -1.0 else 1.0
            val legX = x + side * 0.078
            val z = side * 0.038
            val knee = AuroraOffset(legX + side * stride * 0.12, hip - 0.29 + 0.04 * abs(stride), z)
            val foot = AuroraOffset(legX + side * stride * 0.27, hip - 0.61 + 0.115 * (side * stride).coerceAtLeast(0.0), z)
            return if (index % 2 == 0) limb(Material.BLACK_WOOL, AuroraOffset(legX, hip, z), knee, 0.083, body)
            else limb(Material.BLACK_WOOL, knee, foot, 0.079, body)
        }
        val tile = beat(2.50, 3.83) * fade
        if (index == 14) return storyPart(Material.OBSIDIAN, tileX, tileY, tileZ, size * tile, size * tile, 0.065 * tile, tileRoll)
        if (index in 15..19) {
            val star = stars[index - 15]
            val p = tilePoint(star.x, star.y, 0.041 * tile)
            val width = star.z * (0.93 + 0.07 * sin(seconds * 2.1 + index)) * tile
            return storyPart(if (index == 16) Material.OCHRE_FROGLIGHT else Material.PEARLESCENT_FROGLIGHT,
                p.x, p.y, p.z, width, width, 0.021 * tile, tileRoll + PI / 4)
        }
        // Two opposing corners remain in the original patch of sky as the square moves.
        val corner = beat(3.92, 4.80) * (1.0 - beat(6.80, 8.50))
        val sign = if (index < 22) -1.0 else 1.0
        val horizontal = index % 2 == 0
        val cx = 0.65 + sign * (if (horizontal) 0.218 else 0.279)
        val cy = 0.26 - sign * (if (horizontal) 0.279 else 0.218)
        return storyPart(Material.PEARLESCENT_FROGLIGHT, cx, cy, -0.16,
            (if (horizontal) 0.135 else 0.026) * corner, (if (horizontal) 0.026 else 0.135) * corner, 0.024 * corner)
    }

    private fun fox(index: Int, t: Double): AuroraMiniaturePart {
        fun beat(start: Double, end: Double) = storyBeat(t, start / 12.0, end / 12.0)
        val seconds = t * 12.0
        val impact = beat(3.60, 3.80) - beat(3.81, 4.35)
        val pop = beat(7.90, 8.07) - beat(8.08, 8.52)
        val cloudY = -0.49 - 0.037 * impact + 0.029 * pop
        when (index) {
            0 -> return storyPart(Material.WHITE_WOOL, 0.32, cloudY, 0.17, 1.18, 0.16, 0.36)
            1 -> return storyPart(Material.SNOW_BLOCK, 0.09, cloudY + 0.073, 0.13, 0.57, 0.14, 0.36)
            2 -> return storyPart(Material.SNOW_BLOCK, 0.50, cloudY + 0.125, 0.14, 0.54, 0.15, 0.31)
            3 -> return storyPart(Material.WHITE_WOOL, 0.32, cloudY - 0.076, 0.12, 0.75, 0.16, 0.31)
            4 -> return storyPart(Material.SNOW_BLOCK, -0.67, -0.446, -0.01, 0.38, 0.12, 0.30)
            5 -> return storyPart(Material.WHITE_WOOL, -0.72, -0.502, -0.018, 0.53, 0.13, 0.27)
        }

        val emerging = seconds >= 7.70
        val dive = ((seconds - 2.04) / 1.85).coerceIn(0.0, 1.0)
        val spring = ((seconds - 7.70) / 2.10).coerceIn(0.0, 1.0)
        val load = beat(1.08, 2.01)
        val plunge = beat(3.85, 4.28)
        val hide = beat(7.14, 7.67)
        val landed = beat(9.70, 9.89) - beat(9.89, 10.25)
        val arc = sin(PI * if (emerging) spring else dive)
        val x = if (emerging) mix(0.25, 0.68, storyBeat(spring, 0.0, 1.0))
            else mix(-0.65 - 0.038 * load, 0.12, dive) + 0.035 * plunge
        val y = if (emerging) mix(-0.90, -0.16, spring) + 0.65 * arc - 0.031 * landed
            else mix(-0.205 - 0.043 * load, -0.24, dive) + 0.70 * arc - 0.41 * plunge - 0.43 * hide
        val roll = if (emerging) mix(0.98, 0.0, storyBeat(spring, 0.27, 0.91))
            else 0.30 * sin(PI * (dive * 1.45).coerceAtMost(1.0)) - 1.54 * storyBeat(dive, 0.32, 0.96)
        val squat = if (emerging) 1.0 - 0.15 * landed else 1.0 - 0.22 * load * (1.0 - storyBeat(dive, 0.0, 0.17))
        val burrowing = seconds in 3.31..8.65

        fun piece(material: Material, lx: Double, ly: Double, lz: Double, w: Double, h: Double, d: Double,
            turn: Double = 0.0, yaw: Double = 0.0, visible: Double = 1.0): AuroraMiniaturePart {
            val px = x + lx * cos(roll) - ly * squat * sin(roll)
            val py = y + lx * sin(roll) + ly * squat * cos(roll)
            val faceWidth = abs(cos(yaw)) * w + abs(sin(yaw)) * d
            val halfHeight = (abs(sin(roll + turn)) * faceWidth + abs(cos(roll + turn)) * h * squat) * 0.5
            // Fade each part behind the cloud's solid front before it can reappear underneath.
            val above = if (burrowing) storyBeat(py + halfHeight, cloudY + 0.05, cloudY + 0.125) else 1.0
            val scale = above * visible
            return storyPart(material, px, py.coerceAtLeast(-0.99), lz, w * scale, h * squat * scale,
                d * scale, roll + turn, yaw)
        }
        if (index == 6) return piece(Material.ORANGE_WOOL, -0.013, 0.0, 0.0, 0.411, 0.201, 0.204)
        if (index == 7) return piece(Material.WHITE_TERRACOTTA, 0.129, -0.032, 0.012, 0.128, 0.162, 0.210)

        if (index in 15..18) {
            val rear = index < 17
            val near = index % 2 == 1
            val hip = if (rear) -0.142 else 0.122
            val angle = (if (rear) -1.05 else 1.12) * arc + if (near) 0.06 else -0.12
            val length = if (near) 0.119 else 0.112
            return piece(Material.BLACK_TERRACOTTA, hip + sin(angle) * length * 0.5,
                -0.074 - cos(angle) * length * 0.5, if (near) 0.070 else -0.071,
                0.056, length, 0.054, angle)
        }
        if (index in 19..20) {
            val wag = beat(4.13, 4.62) * (1.0 - hide) * (0.39 * sin((seconds - 4.43) * 6.6) +
                0.08 * sin((seconds - 4.48) * 11.2))
            val angle = if (emerging) -0.20 - 0.30 * arc - 0.085 * sin(seconds * 4) * beat(10.10, 10.85)
                else -0.15 - 0.22 * load + 0.16 * arc + wag
            val tip = index == 20
            val offset = if (tip) 0.344 else 0.150
            return piece(if (tip) Material.SNOW_BLOCK else Material.ORANGE_WOOL,
                -0.174 - offset * cos(angle), 0.027 - offset * sin(angle), -0.006,
                if (tip) 0.112 else 0.300, if (tip) 0.136 else 0.160, if (tip) 0.128 else 0.146, angle)
        }

        val headYaw = 0.85
        fun face(material: Material, side: Double, up: Double, forward: Double, w: Double, h: Double, d: Double) =
            piece(material, 0.193 + side * cos(headYaw) + forward * sin(headYaw), 0.092 + up,
                0.015 - side * sin(headYaw) + forward * cos(headYaw), w, h, d, yaw = headYaw)
        return when (index) {
            8 -> face(Material.ORANGE_CONCRETE, 0.0, 0.0, 0.0, 0.245, 0.204, 0.230)
            9 -> face(Material.WHITE_TERRACOTTA, 0.0, -0.059, 0.141, 0.146, 0.076, 0.118)
            10 -> face(Material.BLACK_CONCRETE, 0.0, -0.041, 0.203, 0.070, 0.038, 0.020)
            11, 12 -> face(Material.ORANGE_TERRACOTTA, if (index == 11) -0.079 else 0.079, 0.132, -0.054,
                0.073, 0.091, 0.061)
            13, 14 -> face(Material.BLACK_CONCRETE, if (index == 13) -0.080 else 0.080, 0.005, 0.119,
                0.041, 0.036, 0.014)
            21 -> piece(Material.SWEET_BERRIES, 0.193 + 0.207 * sin(headYaw) + 0.070,
                0.029, 0.015 + 0.207 * cos(headYaw) + 0.028, 0.202, 0.202, 0.16,
                turn = -0.78, visible = if (emerging) 1.0 else 0.0)
            else -> face(Material.WHITE_TERRACOTTA, if (index == 22) -0.079 else 0.079, 0.143, -0.018,
                0.042, 0.046, 0.014)
        }
    }

    private fun ghast(index: Int, t: Double): AuroraMiniaturePart {
        fun beat(start: Double, end: Double) = storyBeat(t, start / 11.8, end / 11.8)
        val seconds = t * 11.8
        val gateX = 0.60
        when (index) {
            0, 1 -> return storyPart(Material.OBSIDIAN, gateX + if (index == 0) -0.233 else 0.233,
                0.01, -0.12, 0.114, 1.36, 0.145)
            2, 3 -> return storyPart(Material.OBSIDIAN, gateX, if (index == 2) 0.633 else -0.613,
                -0.12, 0.58, 0.114, 0.145)
            4 -> return storyPart(Material.PURPLE_CONCRETE, gateX, 0.01, -0.141, 0.355, 1.142, 0.033)
            22, 23 -> {
                val drift = (t * 1.65 + if (index == 22) 0.12 else 0.65) % 1.0
                val glimmer = sin(PI * drift)
                return storyPart(Material.AMETHYST_BLOCK, gateX + if (index == 22) -0.078 else 0.085,
                    -0.48 + 0.97 * drift, -0.114, 0.079 * glimmer, 0.032 * glimmer, 0.014 * glimmer, 0.31)
            }
        }
        val approach = beat(0.74, 3.06)
        val recoil = beat(3.02, 3.59)
        val turn = beat(3.57, 5.20)
        val squeeze = beat(4.84, 6.82)
        val bump = beat(2.88, 3.07) - beat(3.07, 3.47)
        val wriggle = beat(6.75, 7.22) - beat(7.96, 8.38)
        val through = beat(7.70, 9.35)
        val x = -0.68 + 0.75 * approach - 0.20 * recoil + 0.73 * beat(5.02, 7.48) + 0.024 * wriggle * sin(seconds * 15)
        val y = 0.13 + 0.031 * sin(seconds * 1.75) + 0.022 * turn + 0.015 * wriggle * sin(seconds * 11)
        val z = 0.17 - 0.199 * squeeze - 0.346 * through
        val yaw = 0.21 + 1.06 * turn - 0.10 * through + 0.025 * sin(seconds * 1.33)
        val width = 0.554 * (1.0 - 0.594 * squeeze - 0.13 * bump)
        val height = 0.501 + 0.098 * squeeze + 0.028 * bump - 0.063 * through
        val visible = 1.0 - beat(8.64, 9.28)
        fun point(side: Double, up: Double, forward: Double) = AuroraOffset(
            x + cos(yaw) * side + sin(yaw) * forward, y + up,
            z - sin(yaw) * side + cos(yaw) * forward)

        if (index == 5) return storyPart(Material.WHITE_WOOL, x, y, z,
            width * visible, height * visible, width * visible, yaw = yaw)
        if (index in 6..10) {
            val side = when (index) { 6, 9 -> -0.225; 7, 10 -> 0.225; else -> 0.0 }
            val tear = index >= 9
            val up = when { tear -> -0.070; index == 8 -> -0.265; else -> 0.055 }
            val p = point(side * width, up * height, width * 0.5 + if (tear) 0.005 else 0.011)
            val w = when { tear -> 0.060; index == 8 -> 0.17; else -> 0.225 } * width
            val h = (if (tear) 0.22 else 0.065) * height
            return storyPart(if (tear) Material.LIGHT_GRAY_TERRACOTTA else Material.GRAY_CONCRETE,
                p.x, p.y, p.z, w * visible, h * visible, 0.014 * visible, yaw = yaw)
        }
        if (index in 11..18) {
            val cell = index - 10
            val root = point((cell % 3 - 1) * width * 0.32, -height * 0.48, (cell / 3 - 1) * width * 0.32)
            val phase = seconds * 2.85 + cell * 1.75
            val length = 0.192 + ((cell * 11) % 21) * 0.0044
            val foot = AuroraOffset(root.x + 0.038 * sin(phase) - 0.027 * squeeze, root.y - length,
                root.z + 0.032 * cos(phase * 0.87))
            return limb(Material.WHITE_WOOL, root, foot, 0.048 * (1.0 - 0.38 * squeeze), visible)
        }

        // This ninth tentacle stays connected to the plane after the body goes behind the opaque field.
        val pin = beat(7.02, 8.52)
        val flick = beat(10.51, 11.14)
        val root = point(-width * 0.32, -height * 0.48, -width * 0.32)
        val anchor = between(root, AuroraOffset(gateX - 0.058, -0.134, -0.096), pin)
        val wave = sin((seconds - 8.42) * 5.8)
        fun joint(step: Int): AuroraOffset {
            if (step == 0) return anchor
            val rest = AuroraOffset(root.x + 0.013 * step * sin(seconds * 2.8), root.y - step * 0.076,
                root.z + 0.009 * step)
            val hooked = when (step) {
                1 -> AuroraOffset(gateX - 0.103, -0.211 - 0.015 * wave, -0.022)
                2 -> AuroraOffset(gateX - 0.245, -0.249 - 0.033 * wave, 0.112)
                else -> AuroraOffset(gateX - 0.427 - 0.023 * wave, -0.328 - 0.053 * wave, 0.19)
            }
            val p = between(between(rest, hooked, pin), anchor, flick)
            return AuroraOffset(p.x, p.y + step * 0.025 * sin(PI * flick), p.z)
        }
        val step = index - 19
        return limb(Material.WHITE_WOOL, joint(step), joint(step + 1), 0.053 + 0.008 * pin, 1.0 - flick)
    }
}
