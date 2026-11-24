package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal object AuroraCloudFox : AuroraStoryParts {
    override fun partCount(shape: AuroraShape) = 27

    private fun mix(a: Double, b: Double, weight: Double) = a + (b - a) * weight

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
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
        val crouch = load * (1.0 - storyBeat(dive, 0.0, 0.17))
        val squat = if (emerging) 1.0 - 0.15 * landed else 1.0 - 0.22 * crouch
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
        if (index == 6) return piece(Material.ORANGE_WOOL, -0.024, 0.002, 0.0, 0.399, 0.194, 0.199, turn = 0.045)
        if (index == 7) return piece(Material.SMOOTH_QUARTZ, 0.130, -0.021, 0.004,
            0.128, 0.161, 0.188, turn = -0.14)

        if (index in 15..18) {
            val rear = index < 17
            val near = index % 2 == 1
            val hip = (if (rear) -0.143 else 0.116) + if (near) 0.014 else -0.018
            val angle = (if (rear) -1.08 else 1.06) * arc + if (near) 0.12 else -0.22
            val length = if (near) 0.132 else 0.117
            return piece(Material.BLACK_TERRACOTTA, hip + sin(angle) * length * 0.5,
                -0.066 - cos(angle) * length * 0.5, if (near) 0.073 else -0.070,
                0.056, length, 0.057, angle)
        }
        if (index == 19 || index == 20 || index == 24) {
            val wag = beat(4.13, 4.62) * (1.0 - hide)
            val idle = beat(10.05, 10.90)
            val angle = 0.08 - 0.20 * crouch + 0.22 * arc + 0.34 * wag * sin((seconds - 4.43) * 6.6) +
                0.12 * idle * sin((seconds - 10.05) * 3.5)
            val brush = angle - 0.55 + 0.09 * arc + 0.13 * wag * sin((seconds - 4.60) * 6.6)
            val tip = brush - 0.32 - 0.10 * idle * sin((seconds - 10.0) * 3.4)
            val rootX = -0.160
            val rootY = 0.032
            val jointX = rootX - 0.144 * cos(angle)
            val jointY = rootY - 0.144 * sin(angle)
            if (index == 24) return piece(Material.ORANGE_WOOL, (rootX + jointX) * 0.5,
                (rootY + jointY) * 0.5, -0.010, 0.180, 0.129, 0.126, angle)
            val tipX = jointX - 0.185 * cos(brush)
            val tipY = jointY - 0.185 * sin(brush)
            if (index == 19) return piece(Material.ORANGE_WOOL, (jointX + tipX) * 0.5,
                (jointY + tipY) * 0.5, -0.010, 0.219, 0.163, 0.155, brush)
            // An inverted stair narrows the white brush to a second, smaller step.
            return piece(Material.QUARTZ_STAIRS, tipX - 0.055 * cos(tip), tipY - 0.055 * sin(tip),
                -0.010, 0.150, 0.125, 0.125, tip + PI)
        }

        val headYaw = 0.73 + 0.22 * load - 0.35 * beat(9.95, 10.72)
        val headX = 0.192
        val headY = 0.090 + 0.013 * arc + 0.008 * beat(10.02, 10.65)
        val headZ = 0.014
        val headTilt = -0.10 * (beat(0.48, 0.82) - beat(0.92, 1.29)) +
            0.13 * (beat(10.03, 10.69) - 0.55 * beat(11.12, 11.60))
        fun headPoint(side: Double, up: Double, forward: Double): AuroraOffset {
            val across = side * cos(headYaw) + forward * sin(headYaw)
            return AuroraOffset(headX + across * cos(headTilt) - up * sin(headTilt), headY + across * sin(headTilt) + up * cos(headTilt),
                headZ - side * sin(headYaw) + forward * cos(headYaw))
        }
        fun face(material: Material, side: Double, up: Double, forward: Double, w: Double, h: Double, d: Double,
            tilt: Double = 0.0): AuroraMiniaturePart {
            val p = headPoint(side, up, forward)
            val part = piece(material, p.x, p.y, p.z, w, h, d, turn = headTilt, yaw = headYaw)
            if (tilt != 0.0) part.rotation.rotateZ(tilt.toFloat())
            return part
        }
        if (index == 11 || index == 12 || index == 22 || index == 23) {
            val side = if (index == 11 || index == 22) -1.0 else 1.0
            val inner = index >= 22
            val listen = if (side < 0.0) beat(0.48, 0.77) - beat(0.82, 1.14)
                else beat(10.37, 10.67) - beat(10.79, 11.08)
            val tilt = PI / 4 - side * (0.12 + 0.24 * listen + 0.12 * arc)
            return if (inner) face(Material.WHITE_TERRACOTTA, side * 0.084, 0.111, -0.012, 0.072, 0.068, 0.014, tilt)
            else face(Material.BLACK_TERRACOTTA, side * 0.086, 0.122, -0.049, 0.098, 0.099, 0.065, tilt)
        }
        if (index == 21) {
            // The native berry sprite's stem ends in its lower corner. Rotate it around the bite.
            val turn = PI - 0.16 + 0.26 * arc + 0.15 * landed
            val stem = 0.073
            val bite = headPoint(0.0, -0.068, 0.205)
            return piece(Material.SWEET_BERRIES, bite.x - stem * (cos(turn) + sin(turn)),
                bite.y - stem * (sin(turn) - cos(turn)), bite.z + 0.018,
                0.180, 0.180, 0.14, turn, visible = if (emerging) 1.0 else 0.0)
        }
        return when (index) {
            8 -> face(Material.ORANGE_CONCRETE, 0.0, 0.003, 0.0, 0.256, 0.181, 0.218)
            9 -> face(Material.SMOOTH_QUARTZ, 0.0, -0.051, 0.146, 0.130, 0.074, 0.144)
            10 -> face(Material.BLACK_CONCRETE, 0.0, -0.032, 0.216, 0.064, 0.040, 0.032)
            13, 14 -> {
                val blink = beat(10.15, 10.31) - beat(10.37, 10.55)
                face(Material.BLACK_CONCRETE, if (index == 13) -0.079 else 0.079, 0.015, 0.113,
                    0.046, 0.039 - 0.028 * blink, 0.016)
            }
            else -> {
                val side = if (index == 25) -1.0 else 1.0
                face(Material.SMOOTH_QUARTZ, side * 0.083, -0.047, 0.096,
                    0.104, 0.056, 0.074, side * 0.32)
            }
        }
    }
}
