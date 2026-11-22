package ru.arc.aurora

import ru.arc.aurora.AuroraCelestialShapes.mix
import ru.arc.aurora.AuroraCelestialShapes.smooth
import ru.arc.aurora.AuroraCelestialShapes.strokes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private val hareLandings = doubleArrayOf(-0.97, -0.49, -0.01, 0.88)
private val hareBeats = doubleArrayOf(0.075, 0.25, 0.425, 0.68)

/** Four fixed groups of prints: the two long hind feet land ahead of the smaller staggered forefeet. */
internal fun invisibleHare(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val shakeAge = ((progress - hareBeats.last()) / 0.26).coerceIn(0.0, 1.0)
    val shake = 0.085 * sin(PI * 4.0 * shakeAge) * (1.0 - shakeAge) * (1.0 - shakeAge)
    fun bough(u: Double) = AuroraOffset(0.83 + 0.42 * u,
        0.35 + 0.36 * u + shake * (1.0 - u), 0.14 - 0.31 * u)
    val weights = DoubleArray(27) { stroke -> when (stroke) {
        in 0..15 -> if (stroke % 4 < 2) 1.25 else 0.75
        in 16..19 -> 0.78
        20, 25, 26 -> 1.12
        else -> 0.54
    } }
    return strokes(points, weights) { stroke, u ->
        when (stroke) {
            in 0..15 -> {
                val landing = stroke / 4
                val foot = stroke % 4
                val hind = foot < 2
                val side = if (foot % 2 == 0) -1.0 else 1.0
                val toe = cos(2.0 * PI * u)
                val localX = if (hind) 0.065 + toe * 0.10 else -0.11 + side * 0.053 + toe * 0.042
                val localZ = if (hind) side * (0.12 + 0.02 * toe) else side * 0.039
                val point = AuroraOffset(hareLandings[landing] + localX, 0.022,
                    (landing % 2) * 0.072 + localZ + sin(2.0 * PI * u) * if (hind) 0.048 else 0.035)
                val age = progress - hareBeats[landing]
                AuroraCelestialPoint(point, smooth(age / 0.022) * (1.0 - 0.57 * smooth((age - 0.37) / 0.28)), 0)
            }
            in 16..19 -> {
                val landing = stroke - 16
                val age = ((progress - hareBeats[landing]) / 0.14).coerceIn(0.0, 1.0)
                val direction = 2.0 * PI * u + landing * 0.61
                val spread = 0.055 + age * (0.12 + u * 0.11)
                val point = AuroraOffset(hareLandings[landing] + 0.035 + spread * cos(direction),
                    0.026 + 4.0 * age * (1.0 - age) * (0.08 + 0.13 * u),
                    (landing % 2) * 0.072 + spread * sin(direction))
                AuroraCelestialPoint(point, smooth(age / 0.15) * smooth((1.0 - age) / 0.4), 1)
            }
            20, 25 -> {
                val point = bough(u)
                val snow = stroke == 25
                AuroraCelestialPoint(point.copy(y = point.y + if (snow) 0.025 else 0.0),
                    if (snow) 0.76 * (1.0 - shakeAge * 0.68) else 0.82, if (snow) 1 else 0)
            }
            in 21..24 -> {
                val twig = stroke - 21
                val root = bough(if (twig < 2) 0.43 else 0.75)
                val side = if (twig % 2 == 0) -1.0 else 1.0
                AuroraCelestialPoint(root.copy(x = root.x - u * 0.17, y = root.y - 0.11 * u,
                    z = root.z + u * side * 0.19), 0.74, 0)
            }
            else -> {
                val age = ((progress - 0.695 - u * 0.025) / 0.23).coerceIn(0.0, 1.0)
                val root = bough(0.09 + u * 0.59)
                val point = AuroraOffset(root.x - 0.19 * age * (0.45 + u),
                    (1.0 - age * age) * root.y + 0.028 + 0.035 * sin(PI * age),
                    root.z + sin(u * 5.4) * age * 0.22)
                AuroraCelestialPoint(point, smooth(age / 0.09) * smooth((1.0 - age) / 0.24), 1)
            }
        }
    }
}

private fun polyline(vertices: List<AuroraOffset>, along: Double): AuroraOffset {
    val position = along * vertices.lastIndex
    val edge = position.toInt().coerceAtMost(vertices.lastIndex - 1)
    return mix(vertices[edge], vertices[edge + 1], position - edge)
}

private val paperBow = AuroraOffset(0.31, 0.18, 0.0)
private val paperStern = AuroraOffset(-0.29, 0.13, 0.0)
private val paperPeak = AuroraOffset(-0.055, 0.435, 0.015)
private val paperWaterline = AuroraOffset(0.20, 0.025, 0.0)
private val paperFolds = listOf(
    listOf(paperStern, AuroraOffset(-0.01, 0.085, 0.145), paperBow),
    listOf(paperStern, AuroraOffset(-0.01, 0.085, -0.145), paperBow),
    listOf(paperStern, AuroraOffset(-0.13, 0.015, 0.0), paperWaterline),
    listOf(AuroraOffset(-0.19, 0.1, 0.069), paperPeak, AuroraOffset(0.15, 0.111, 0.088)),
    listOf(AuroraOffset(-0.19, 0.1, -0.069), paperPeak, AuroraOffset(0.15, 0.111, -0.088)),
    listOf(paperWaterline, paperBow),
)

/** The second bow pivots on the notched leaf; the stern continues downstream first after release. */
internal fun paperFlotilla(points: Int, progress: Double): List<AuroraCelestialPoint> {
    // The leading boat clears the leaf before the second boat pivots into its lane.
    val firstX = -0.55 + 1.62 * sin(0.5 * PI * (progress / 0.61).coerceAtMost(1.0))
    val firstOpacity = 1.0 - smooth((progress - 0.515) / 0.105)
    val launch = ((progress - 0.155) / 0.265).coerceIn(0.0, 1.0)
    val secondOpacity = smooth((progress - 0.16) / 0.055)
    val heading = PI * smooth((progress - 0.42) / 0.31)
    val bowX = 0.20 - 0.76 * (1.0 - launch) + 0.50 * smooth((progress - 0.73) / 0.27)
    val secondBob = 0.02 + 0.009 * sin(PI * 5.0 * progress + 1.2)
    val notchAngle = -0.41 * PI
    val centre = AuroraOffset(0.20 - 0.198 * cos(notchAngle), paperWaterline.y + secondBob,
        0.19 - 0.173 * sin(notchAngle))
    val leafNod = 0.07 * sin(heading * 2.0)
    return strokes(points, doubleArrayOf(1.55, 1.55, 1.25, 1.3, 1.3, 0.48, 1.55, 1.55, 1.25, 1.3, 1.3, 0.48,
        1.7, 0.58, 0.50)) { stroke, u ->
        if (stroke < 12) {
            val second = stroke >= 6
            val local = polyline(paperFolds[stroke % 6], u)
            val yaw = if (second) heading else 0.09 * sin(PI * 3.0 * progress)
            val x = local.x - if (second) paperWaterline.x else 0.0
            val bob = if (second) secondBob else 0.02 + 0.009 * sin(PI * 5.0 * progress)
            val point = AuroraOffset((if (second) bowX else firstX) + x * cos(yaw) - local.z * sin(yaw),
                local.y + bob, (if (second) 0.19 else -0.515) + x * sin(yaw) + local.z * cos(yaw))
            AuroraCelestialPoint(point, if (second) secondOpacity else firstOpacity, 0)
        } else {
            val angle = notchAngle + u * PI * 1.82
            val point = when (stroke) {
                12 -> centre.copy(x = centre.x + 0.198 * cos(angle), z = centre.z + 0.173 * sin(angle))
                13 -> { // Both sides of the cut share the V's pointed inner vertex.
                    val edge = if (u < 0.5) notchAngle else 1.41 * PI
                    val radius = abs(2.0 * u - 1.0)
                    centre.copy(x = centre.x + 0.198 * radius * cos(edge), z = centre.z + 0.173 * radius * sin(edge))
                }
                else -> centre.copy(x = centre.x + 0.085 * u, z = centre.z + 0.12 * u)
            }
            AuroraCelestialPoint(point.copy(y = point.y + (point.z - 0.19) * leafNod), 0.9, 1)
        }
    }
}

// The left elbow sits exactly at the stopping twig. The small cactus is part of the particle scene.
private val cactus = listOf(
    listOf(AuroraOffset(1.035, 0.012, 0.0), AuroraOffset(1.035, 0.42, 0.0), AuroraOffset(1.035, 0.71, 0.0),
        AuroraOffset(1.06, 0.765, 0.0), AuroraOffset(1.12, 0.765, 0.0), AuroraOffset(1.15, 0.71, 0.0),
        AuroraOffset(1.15, 0.42, 0.0), AuroraOffset(1.15, 0.012, 0.0)),
    listOf(AuroraOffset(1.035, 0.275, 0.0), AuroraOffset(0.925, 0.275, 0.0),
        AuroraOffset(0.918, 0.32, 0.0), AuroraOffset(0.918, 0.49, 0.0),
        AuroraOffset(0.976, 0.49, 0.0), AuroraOffset(0.976, 0.34, 0.0), AuroraOffset(1.035, 0.34, 0.0)),
    listOf(AuroraOffset(1.15, 0.425, 0.0), AuroraOffset(1.25, 0.425, 0.14),
        AuroraOffset(1.25, 0.59, 0.14), AuroraOffset(1.202, 0.59, 0.08),
        AuroraOffset(1.202, 0.48, 0.08), AuroraOffset(1.15, 0.48, 0.0)),
)

// Each dry stem has roots and tips in different parts of the tangle, with short irregular side forks.
private val tumbleStems = listOf(
    listOf(AuroraOffset(-0.19, -0.08, -0.04), AuroraOffset(-0.12, 0.034, 0.01), AuroraOffset(-0.02, 0.145, 0.055),
        AuroraOffset(0.096, 0.128, 0.035), AuroraOffset(0.244, 0.044, 0.0)),
    listOf(AuroraOffset(-0.18, 0.123, 0.005), AuroraOffset(-0.134, -0.012, -0.062), AuroraOffset(-0.055, -0.16, -0.096),
        AuroraOffset(0.061, -0.215, -0.025), AuroraOffset(0.171, -0.116, 0.075)),
    listOf(AuroraOffset(-0.064, 0.205, -0.098), AuroraOffset(0.066, 0.18, -0.073), AuroraOffset(0.145, 0.092, -0.03),
        AuroraOffset(0.174, -0.04, 0.024), AuroraOffset(0.133, -0.17, 0.061)),
    listOf(AuroraOffset(-0.127, -0.18, 0.023), AuroraOffset(-0.189, -0.04, 0.065), AuroraOffset(-0.19, 0.091, 0.07),
        AuroraOffset(-0.10, 0.186, 0.008), AuroraOffset(-0.032, 0.235, -0.035)),
    listOf(AuroraOffset(0.144, 0.127, 0.076), AuroraOffset(0.022, 0.12, 0.14), AuroraOffset(-0.11, 0.062, 0.149),
        AuroraOffset(-0.18, -0.029, 0.055), AuroraOffset(-0.222, -0.056, -0.004)),
    listOf(AuroraOffset(-0.05, -0.209, -0.05), AuroraOffset(0.027, -0.116, -0.13), AuroraOffset(0.135, 0.006, -0.073),
        AuroraOffset(0.186, 0.115, 0.033), AuroraOffset(0.161, 0.178, 0.077)),
    listOf(AuroraOffset(0.201, -0.108, 0.06), AuroraOffset(0.139, -0.122, 0.14), AuroraOffset(0.009, -0.099, 0.167),
        AuroraOffset(-0.109, -0.04, 0.112), AuroraOffset(-0.153, 0.051, 0.082)),
)

internal fun tumbleweed(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val rolling = (progress / 0.71).coerceAtMost(1.0)
    val remaining = 1.74 * (1.0 - rolling)
    val shakeAge = ((progress - 0.71) / 0.27).coerceIn(0.0, 1.0)
    val angle = remaining / 0.24 - 0.26 * sin(shakeAge * 5.0 * PI) * exp(-3.8 * shakeAge)
    val sine = sin(angle)
    val cosine = cos(angle)
    val hop = ((progress - 0.25) / 0.29).coerceIn(0.0, 1.0)
    val tip = tumbleStems.first().last()
    val centreX = if (progress < 0.71) 0.918 - tip.x - remaining else 0.918 - tip.x * cosine + tip.y * sine
    val centreY = if (progress < 0.71) 0.32 - tip.y + 1.22 * hop * (1.0 - hop)
        else 0.32 - tip.x * sine - tip.y * cosine
    return strokes(points, DoubleArray(19) { stroke -> when (stroke) {
        in 0..6 -> 1.40
        in 7..13 -> 0.62
        14 -> 2.2
        15, 16 -> 1.25
        else -> 0.85
    } }) { stroke, u ->
        if (stroke >= 17) {
            val side = if (stroke == 17) -1.0 else 1.0
            AuroraCelestialPoint(AuroraOffset(-0.70 + 0.56 * u, 0.015 + 0.095 * sin(PI * u),
                0.19 * side * sin(PI * u) + 0.08 * (u - 0.5)), 0.58, 0)
        } else if (stroke >= 14) {
            AuroraCelestialPoint(polyline(cactus[stroke - 14], u), 0.92, 1)
        } else {
            val stem = stroke % 7
            val point = if (stroke < 7) polyline(tumbleStems[stem], u) else {
                val fork = polyline(tumbleStems[stem], 0.49 + 0.34 * u)
                val direction = 0.18 + stem * (2.0 * PI / 7.0) + if (stem % 2 == 0) 0.95 else -0.95
                fork.copy(x = fork.x + 0.075 * u * cos(direction), y = fork.y + 0.075 * u * sin(direction),
                    z = fork.z + (if (stem % 2 == 0) 0.065 else -0.065) * u)
            }
            AuroraCelestialPoint(AuroraOffset(centreX + point.x * cosine - point.y * sine,
                (centreY + point.x * sine + point.y * cosine).coerceAtLeast(0.012), point.z - tip.z), 1.0, 0)
        }
    }
}
