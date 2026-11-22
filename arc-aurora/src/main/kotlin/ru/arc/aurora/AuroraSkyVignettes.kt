package ru.arc.aurora

import ru.arc.aurora.AuroraCelestialShapes.mix
import ru.arc.aurora.AuroraCelestialShapes.smooth
import ru.arc.aurora.AuroraCelestialShapes.strokes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun skyPoint(x: Double, y: Double, z: Double = 0.0) = AuroraOffset(x, y, z)

private fun AuroraOffset.rolled(angle: Double) = AuroraOffset(
    x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle), z)

private fun beat(progress: Double, from: Double, to: Double) = smooth((progress - from) / (to - from))

/** The level falls towards a pinched throat; the receiving heap rises, then the glass turns over. */
internal fun skyHourglass(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val drained = beat(progress, 0.08, 0.58)
    val angle = PI * beat(progress, 0.54, 0.95)
    val settle = beat(progress, 0.8, 0.96)
    val upperY = 0.64 * (1.0 - drained)
    val heapBase = -0.77 + 0.21 * settle
    val heapHeight = 0.41 * drained * (1.0 - settle)
    fun glassWidth(y: Double) = 0.05 + 0.54 * sin(abs(y) / 0.81 * PI / 2.0)
    return strokes(points, doubleArrayOf(2.25, 2.25, 1.45, 1.45, 1.3, 1.5, 0.63)) { part, u ->
        val across = 2.0 * u - 1.0
        val (point, opacity) = when (part) {
            0, 1 -> {
                val y = across * 0.81
                skyPoint((if (part == 0) -1.0 else 1.0) * glassWidth(y), y) to 1.0
            }
            2, 3 -> skyPoint(across * 0.69, if (part == 2) 0.81 else -0.81, -0.04) to 1.0
            4 -> skyPoint(across * glassWidth(upperY) * 0.94,
                upperY - (1.0 - abs(across)) * upperY * 0.26, 0.04) to (1.0 - drained)
            5 -> {
                val summit = 1.0 - abs(across + sin(angle) * 0.38).coerceAtMost(1.0)
                skyPoint(across * 0.54 * sqrt(drained), heapBase + heapHeight * summit, 0.04) to drained
            }
            else -> skyPoint(0.0, u * (heapBase + heapHeight), 0.04) to
                (beat(progress, 0.07, 0.15) * (1.0 - beat(progress, 0.51, 0.59)))
        }
        AuroraCelestialPoint(point.rolled(angle), opacity, if (part < 4) 0 else 1)
    }
}

/** Three windward seeds leave at different times and turn their parachutes upright in flight. */
internal fun dandelionGust(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val gust = beat(progress, 0.14, 0.39) * (1.0 - beat(progress, 0.67, 0.93))
    fun stem(u: Double) = skyPoint(-0.63 + 0.25 * u + gust * 0.22 * u * u,
        -0.88 + 1.19 * u - gust * 0.08 * u * u, 0.10 * u * u)
    val head = stem(1.0)
    val leaf = listOf(stem(0.22), skyPoint(-0.83, -0.50), skyPoint(-1.01, -0.30),
        skyPoint(-0.75, -0.35), stem(0.39))
    val weights = DoubleArray(18) { part -> when (part) {
        0 -> 2.3
        1 -> 1.35
        else -> if (part % 2 == 0) 0.88 else 1.0
    } }
    return strokes(points, weights) { part, u ->
        if (part < 2) return@strokes AuroraCelestialPoint(if (part == 0) stem(u) else polyline(leaf, u), 1.0, 0)
        val seed = (part - 2) / 2
        val angle = seed * PI / 4.0 + 0.13
        val free = when (seed) {
            0 -> beat(progress, 0.26, 0.81)
            1 -> beat(progress, 0.42, 0.92)
            7 -> beat(progress, 0.58, 0.98)
            else -> 0.0
        }
        val dx = cos(angle)
        val dy = sin(angle)
        val reach = when (seed) { 0 -> 1.17; 1 -> 0.87; else -> 0.71 }
        val lift = when (seed) { 0 -> 0.25; 1 -> 0.36; else -> 0.18 }
        val root = skyPoint(head.x + free * (0.32 * dx + reach),
            head.y + free * (0.32 * dy + lift) + 0.11 * sin(PI * free),
            head.z + free * 0.15 * sin(angle + 0.7))
        val shaftX = 0.46 * dx * (1.0 - free) - 0.045 * free
        val shaftY = 0.46 * dy * (1.0 - free) + 0.24 * free
        val shaftZ = 0.08 * sin(angle * 2.0) * (1.0 - free)
        val cap = skyPoint(root.x + shaftX, root.y + shaftY, root.z + shaftZ)
        val point = if (part % 2 == 0) mix(root, cap, u) else {
            val across = 2.0 * u - 1.0
            val depth = abs(across) * (0.12 - free * 0.36)
            skyPoint(cap.x - shaftY * across * 0.36 + shaftX * depth,
                cap.y + shaftX * across * 0.36 + shaftY * depth, cap.z)
        }
        AuroraCelestialPoint(point, 1.0, 1)
    }
}

private val kingfisherBack = listOf(skyPoint(-0.60, 0.01), skyPoint(-0.29, 0.16), skyPoint(-0.12, 0.23),
    skyPoint(0.04, 0.37), skyPoint(0.23, 0.34), skyPoint(0.32, 0.22))
private val kingfisherBelly = listOf(skyPoint(-0.60, 0.01), skyPoint(-0.36, -0.08), skyPoint(-0.17, -0.19),
    skyPoint(0.05, -0.16), skyPoint(0.25, -0.01), skyPoint(0.32, 0.12))
private val beakTip = skyPoint(0.78, 0.18)
private val fishContour = listOf(skyPoint(-0.13, 0.0), skyPoint(-0.23, 0.09), skyPoint(-0.22, -0.10),
    skyPoint(-0.13, 0.0), skyPoint(0.03, -0.075), skyPoint(0.18, 0.0), skyPoint(0.01, 0.085), skyPoint(-0.13, 0.0))

/** The bird tucks its leading wing, catches the swimming fish at the tip of its bill and climbs. */
internal fun kingfisherDive(points: Int, progress: Double): List<AuroraCelestialPoint> {
    fun place(point: AuroraOffset, time: Double): AuroraOffset {
        val dive = beat(time, 0.19, 0.55)
        val climb = beat(time, 0.55, 0.90)
        val tilted = point.rolled(0.04 - 1.23 * dive + 1.56 * climb)
        return skyPoint(tilted.x - 0.45 + 0.54 * dive + 0.45 * climb,
            tilted.y + 0.39 - 0.68 * dive + 0.48 * climb, tilted.z + 0.09 * dive)
    }
    val tuck = beat(progress, 0.18, 0.43) * (1.0 - beat(progress, 0.58, 0.79))
    val wingTip = skyPoint(-0.52 - 0.14 * tuck, 0.79 - 0.68 * tuck, 0.16 - 0.13 * tuck)
    val wing = listOf(skyPoint(0.02, 0.15), skyPoint(-0.20 - 0.11 * tuck, 0.48 - 0.33 * tuck, 0.09),
        wingTip, skyPoint(-0.50, 0.31 - 0.26 * tuck, 0.09), skyPoint(-0.33, 0.0))
    val caught = place(beakTip, 0.55)
    val approach = 1.0 - beat(progress, 0.11, 0.55)
    val fish = if (progress < 0.55) caught.copy(x = caught.x - 0.46 * approach,
        y = caught.y - 0.05 * sin(PI * approach)) else place(beakTip, progress)
    return strokes(points, doubleArrayOf(1.9, 1.85, 0.68, 0.56, 2.7, 1.45)) { part, u ->
        val point = when (part) {
            0 -> place(polyline(kingfisherBack, u), progress)
            1 -> place(polyline(kingfisherBelly, u), progress)
            2, 3 -> place(mix(if (part == 2) kingfisherBack.last() else kingfisherBelly.last(), beakTip, u), progress)
            4 -> place(polyline(wing, u), progress)
            else -> {
                val fin = polyline(fishContour, u).rolled(-0.70 * beat(progress, 0.55, 0.75))
                skyPoint(fish.x + fin.x, fish.y + fin.y, fish.z + 0.03)
            }
        }
        AuroraCelestialPoint(point, 1.0, when (part) { 0, 4 -> 0; 5 -> 2; else -> 1 })
    }
}

private val prismLeft = skyPoint(-0.58, -0.45)
private val prismPeak = skyPoint(-0.13, 0.70)
private val prismRight = skyPoint(0.38, -0.45)
private val prismFar = listOf(prismPeak, skyPoint(0.02, 0.76, 0.23), skyPoint(0.53, -0.39, 0.23), prismRight)
private val prismIn = mix(prismLeft, prismPeak, 0.57)
private val prismOut = mix(prismPeak, prismRight, 0.51)
private val spectrum = listOf(skyPoint(1.20, 0.64), skyPoint(1.23, 0.03), skyPoint(1.17, -0.59))

/** One incident beam crosses both glass faces before its three outgoing rays spread apart. */
internal fun stellarPrism(points: Int, progress: Double): List<AuroraCelestialPoint> =
    strokes(points, doubleArrayOf(1.35, 1.35, 1.15, 1.20, 1.45, 0.55, 1.5, 1.3, 1.5)) { part, u ->
        val point = when (part) {
            0 -> mix(prismLeft, prismPeak, u)
            1 -> mix(prismPeak, prismRight, u)
            2 -> mix(prismRight, prismLeft, u)
            3 -> polyline(prismFar, u)
            4 -> mix(skyPoint(-1.23, -0.26), prismIn, u)
            5 -> mix(prismIn, prismOut, u)
            else -> mix(prismOut, spectrum[part - 6], u)
        }
        val arrival = when (part) { 4 -> 0.12 + u * 0.16; 5 -> 0.28 + u * 0.09; else -> 0.37 + u * 0.24 }
        val end = when (part) { 4 -> 0.65 + u * 0.12; 5 -> 0.77 + u * 0.035; else -> 0.805 + u * 0.10 }
        val opacity = if (part < 4) 1.0 else beat(progress, arrival, arrival + 0.055) *
            (1.0 - beat(progress, end, end + 0.065))
        AuroraCelestialPoint(point, opacity, when (part) { 6 -> 1; 8 -> 2; else -> 0 })
    }

private val craterWest = skyPoint(-0.26, 0.09)
private val craterEast = skyPoint(0.23, 0.10)
private val volcanoWest = listOf(skyPoint(-1.18, -0.63), skyPoint(-0.85, -0.52), skyPoint(-0.64, -0.34),
    skyPoint(-0.50, -0.28), skyPoint(-0.39, -0.03), craterWest)
private val volcanoEast = listOf(craterEast, skyPoint(0.33, 0.05), skyPoint(0.45, -0.17),
    skyPoint(0.67, -0.31), skyPoint(0.77, -0.45), skyPoint(1.19, -0.61))
private val lavaFlow = listOf(skyPoint(-0.04, 0.07), skyPoint(-0.11, -0.06, 0.05),
    skyPoint(0.09, -0.20, 0.09), skyPoint(0.03, -0.33, 0.12), skyPoint(0.26, -0.43, 0.15),
    skyPoint(0.24, -0.54, 0.17), skyPoint(0.56, -0.66, 0.21))

/** Three staggered ballistic jets fall back to the flanks while lava runs down the front face. */
internal fun emberVolcano(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val vent = beat(progress, 0.15, 0.32) * (1.0 - beat(progress, 0.53, 0.75))
    return strokes(points, doubleArrayOf(1.9, 1.9, 0.75, 1.60, 0.65, 1.15, 1.15, 1.15)) { part, u ->
        when (part) {
            0, 1 -> AuroraCelestialPoint(polyline(if (part == 0) volcanoWest else volcanoEast, u), 1.0, 0)
            2 -> {
                val rim = mix(craterWest, craterEast, u)
                AuroraCelestialPoint(rim.copy(y = rim.y - sin(PI * u) * 0.06), 0.60 + vent * 0.40, 1)
            }
            3 -> AuroraCelestialPoint(polyline(lavaFlow, u), beat(progress, 0.33 + 0.38 * u, 0.41 + 0.38 * u), 1)
            4 -> AuroraCelestialPoint(skyPoint((u - 0.5) * 0.24,
                0.08 + (1.0 - abs(u * 2.0 - 1.0)) * 0.50 * vent), vent, 1)
            else -> {
                val jet = part - 5
                val launch = 0.18 + jet * 0.078
                val age = (progress - launch) / 0.42 - (1.0 - u) * 0.30
                val flight = age.coerceIn(0.0, 1.0)
                val reach = when (jet) { 0 -> -0.79; 1 -> 0.32; else -> 0.90 }
                val height = if (jet == 1) 1.14 else 0.76
                val point = skyPoint(reach * flight, 0.10 + 4.0 * height * flight * (1.0 - flight) - 0.48 * flight * flight,
                    (jet - 1) * 0.12 * flight)
                AuroraCelestialPoint(point, beat(age, 0.0, 0.10) * (1.0 - beat(age, 0.89, 1.0)), 2)
            }
        }
    }
}

private val shellFracture = listOf(skyPoint(-0.57, -0.12), skyPoint(-0.38, 0.015), skyPoint(-0.20, -0.19),
    skyPoint(0.015, 0.03), skyPoint(0.20, -0.19), skyPoint(0.38, -0.025), skyPoint(0.57, -0.12))
private val starEdge = listOf(skyPoint(0.0, 0.43), skyPoint(0.074, 0.080), skyPoint(0.30, 0.0),
    skyPoint(0.074, -0.080), skyPoint(0.0, -0.36), skyPoint(-0.074, -0.080), skyPoint(-0.30, 0.0),
    skyPoint(-0.074, 0.080), skyPoint(0.0, 0.43))

/** A running crack frees the top shell; it hinges away from the emerging four-point star. */
internal fun hatchingStar(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val open = beat(progress, 0.40, 0.77)
    val lift = beat(progress, 0.48, 0.86)
    fun shell(point: AuroraOffset, lid: Boolean): AuroraOffset {
        if (!lid) return point.copy(y = point.y - 0.085 * sin(open * PI) - 0.06 * open)
        val hinge = shellFracture.first()
        val turned = skyPoint(point.x - hinge.x, point.y - hinge.y, point.z).rolled(1.10 * open)
        return skyPoint(hinge.x + turned.x - 0.16 * open, hinge.y + turned.y + 0.10 * open, turned.z - 0.12 * open)
    }
    return strokes(points, doubleArrayOf(2.45, 2.30, 1.42, 1.42, 1.50, 0.67, 0.67, 0.67, 0.67)) { part, u ->
        when (part) {
            0, 1 -> {
                val angle = (u + part) * PI
                val y = sin(angle)
                val point = skyPoint(0.57 * cos(angle) * (1.0 - 0.19 * y), -0.12 + 0.76 * y)
                AuroraCelestialPoint(shell(point, part == 0), 1.0, 0)
            }
            2, 3 -> AuroraCelestialPoint(shell(polyline(shellFracture, u), part == 2),
                beat(progress, 0.40, 0.51), 0)
            4 -> AuroraCelestialPoint(shell(polyline(shellFracture, u), false),
                beat(progress, 0.14 + 0.25 * u, 0.21 + 0.25 * u) * (1.0 - beat(progress, 0.44, 0.58)), 2)
            else -> {
                val ray = (part - 5) * 2
                val contour = if (u <= 0.5) mix(starEdge[ray], starEdge[ray + 1], u * 2.0)
                    else mix(starEdge[ray + 1], starEdge[ray + 2], u * 2.0 - 1.0)
                val scale = 0.17 + 0.83 * lift
                AuroraCelestialPoint(skyPoint(0.27 * lift + contour.x * scale,
                    -0.13 + 0.83 * lift + contour.y * scale, 0.045 + 0.09 * lift), beat(progress, 0.46, 0.58), 1)
            }
        }
    }
}

private val pentagramTips = List(5) { vertex ->
    val angle = -PI / 2.0 + vertex * 2.0 * PI / 5.0
    skyPoint(cos(angle), sin(angle))
}

/** A single five-chord stroke closes at the downward tip. The five seals then ignite around the rim. */
internal fun infernalPentagram(points: Int, progress: Double): List<AuroraCelestialPoint> {
    // Interleave both halves of each long chord and all four quadrants for a partial particle sweep.
    val weights = DoubleArray(24) { when (it) { in 0..9 -> 6.0; in 10..13 -> 7.0; else -> 4.0 } }
    return strokes(points, weights) { part, u ->
        when {
            part < 10 -> {
                val chord = part % 5
                val along = if (part < 5) u * 0.48 else 0.52 + u * 0.48
                val position = (chord + along) / 5.0
                val point = mix(pentagramTips[chord * 2 % 5], pentagramTips[(chord * 2 + 2) % 5], along)
                val arrival = 0.030 + 0.43 * position
                val extinction = 0.85 + 0.10 * (1.0 - position)
                AuroraCelestialPoint(point, beat(progress, arrival, arrival + 0.024) *
                    (1.0 - beat(progress, extinction, extinction + 0.045)), 0)
            }
            part < 14 -> {
                val turn = (part - 10 + 0.05 + 0.90 * u) / 4.0
                val angle = turn * 2.0 * PI - PI / 2.0
                val fromBottom = 2.0 * minOf(turn, 1.0 - turn)
                val ignition = 0.018 + 0.255 * fromBottom
                val extinction = 0.84 + 0.10 * (1.0 - fromBottom)
                val ember = 0.79 + 0.11 * cos(3.0 * angle - progress * 2.0 * PI)
                AuroraCelestialPoint(skyPoint(1.15 * cos(angle), 1.15 * sin(angle), -0.035),
                    ember * beat(progress, ignition, ignition + 0.064) *
                        (1.0 - beat(progress, extinction, extinction + 0.055)), 1)
            }
            else -> {
                val vertex = (part - 14) % 5
                val tip = pentagramTips[vertex]
                val start = 0.475 + 0.078 * vertex
                val ignition = start + 0.008 * u
                val flare = beat(progress, ignition, ignition + 0.018) *
                    (1.0 - beat(progress, start + 0.063, start + 0.091))
                val tangent = (if (part < 19) -1.0 else 1.0) * 0.135 * sin(PI * u)
                val radius = 1.0 + 0.285 * u
                AuroraCelestialPoint(skyPoint(tip.x * radius - tip.y * tangent,
                    tip.y * radius + tip.x * tangent, 0.04), flare, 2)
            }
        }
    }
}
