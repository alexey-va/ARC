package ru.arc.aurora

import ru.arc.aurora.AuroraCelestialShapes.mix
import ru.arc.aurora.AuroraCelestialShapes.smooth
import ru.arc.aurora.AuroraCelestialShapes.strokes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** The opening closes behind the needle. Its return passes dip behind the three visible stitches. */
internal fun skyStitch(points: Int, progress: Double): List<AuroraCelestialPoint> {
    fun phase(time: Double) = ((time - 0.16) / 0.62).coerceIn(0.0, 1.0) * 3.0
    val phase = phase(progress)
    val opened = smooth(progress / 0.15)
    fun edge(u: Double, side: Double): AuroraOffset {
        val x = (u - 0.5) * 2.08
        val taper = sin(PI * u).coerceAtLeast(0.0)
        val closed = smooth((phase / 3.0 - u - 0.045) / 0.16)
        val gap = taper * opened * (0.35 - 0.255 * closed)
        return AuroraOffset(x, 0.18 * x + 0.045 * sin(2.0 * PI * u) + side * gap,
            0.17 * sin(2.0 * PI * u) + side * 0.13 * taper)
    }
    fun stitch(index: Int, u: Double): AuroraOffset {
        val start = 0.11 + 0.29 * index
        val point = mix(edge(start, -1.0), edge(start + 0.16, 1.0), u)
        return point.copy(z = point.z + 0.08 * sin(PI * u))
    }
    val lastEye = stitch(2, 1.0)
    fun needleAt(time: Double): AuroraOffset {
        val travel = phase(time)
        val index = travel.toInt().coerceAtMost(2)
        val local = travel - index
        if (local <= 0.62) return stitch(index, local / 0.62)
        if (index < 2) {
            val back = (local - 0.62) / 0.38
            val point = mix(stitch(index, 1.0), stitch(index + 1, 0.0), back)
            return point.copy(z = point.z - 0.18 * sin(PI * back))
        }
        val exit = smooth((time - 0.705) / 0.255)
        return lastEye.copy(x = lastEye.x + 0.51 * exit, y = lastEye.y + 0.48 * exit,
            z = lastEye.z + 0.16 * sin(PI * exit))
    }
    val needle = needleAt(progress)
    val before = needleAt(progress - 0.008)
    val after = needleAt(progress + 0.008)
    val dx = after.x - before.x
    val dy = after.y - before.y
    val dz = after.z - before.z
    val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.002)
    return strokes(points, doubleArrayOf(2.1, 2.1, 0.72, 0.72, 0.72, 0.4, 1.0)) { stroke, u ->
        when (stroke) {
            0, 1 -> AuroraCelestialPoint(edge(u, if (stroke == 0) -1.0 else 1.0), 0.84, 0)
            2, 3, 4 -> {
                val index = stroke - 2
                AuroraCelestialPoint(stitch(index, u), smooth((phase - index - 0.62 * u) / 0.07), 1)
            }
            5 -> {
                val shaft = (u - 0.65) * 0.12 / length
                AuroraCelestialPoint(AuroraOffset(needle.x + shaft * dx, needle.y + shaft * dy,
                    needle.z + shaft * dz), smooth((progress - 0.1) / 0.06), 1)
            }
            else -> {
                val free = smooth((progress - 0.705) / 0.255)
                val point = mix(lastEye, needle, u)
                AuroraCelestialPoint(point.copy(y = point.y - 0.18 * free * sin(PI * u)), free, 1)
            }
        }
    }
}

/** One fold travels across the hem. Violet shows only where an upright strip turns over. */
internal fun auroraCurtain(points: Int, progress: Double): List<AuroraCelestialPoint> {
    val foldAt = -0.23 + 1.46 * smooth((progress - 0.08) / 0.81)
    fun strip(u: Double, height: Double, violet: Boolean): AuroraCelestialPoint {
        val distance = (u - foldAt) / 0.14
        val fold = ((exp(-distance * distance * 0.85) - 0.06) / 0.94).coerceAtLeast(0.0)
        val twist = distance * fold
        val length = 0.74 + 0.21 * sin(u * 3.2 * PI + 0.25)
        val lean = 0.15 * sin(u * 1.7 * PI + 0.2)
        val x = (u - 0.5) * 2.22 + 0.22 * twist + height * (0.06 + 0.12 * twist) +
            height * height * lean + 0.06 * sin(PI * height) * cos(2.0 * PI * u)
        val lower = -0.37 + 0.14 * sin(u * 2.3 * PI) + 0.32 * fold
        val depth = 0.24 * sin(u * 1.7 * PI - 0.4) + 0.28 * fold
        val shade = if (violet) fold else 1.0 - 0.84 * fold
        val inside = if (violet) 0.10 * height * fold else 0.0
        val point = AuroraOffset(x - inside, lower + length * height, depth + height * (0.11 + twist * 0.14) + inside)
        return AuroraCelestialPoint(point, shade * (1.0 - height * 0.73), if (violet) 1 else 0)
    }
    // Two coincident colour samples keep their identities during interpolation and exchange visibility.
    return strokes(points, DoubleArray(16) { if (it < 2) 3.6 else 1.0 }) { stroke, along ->
        val violet = stroke % 2 == 1
        if (stroke < 2) strip(along, 0.0, violet)
        else strip((stroke / 2 - 1) / 6.0, along, violet)
    }
}
