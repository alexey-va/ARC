package ru.arc.aurora

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Opacity controls emission density; native particles retain their own lifetime and fade. */
data class AuroraCelestialPoint(val offset: AuroraOffset, val opacity: Double, val paletteSlot: Int)

/**
 * Finite animations in local block coordinates: X is width, Y is up and Z is depth.
 * The worker prepares at most 33 poses with stable sample identities and palette slots.
 * A shared size-one animation can be interpolated, then given each scene's immutable world pose.
 */
object AuroraCelestialShapes {
    const val MAX_FRAMES = 33

    fun frames(
        shape: AuroraShape, size: Double, points: Int, frameCount: Int = MAX_FRAMES,
        roll: Double = 0.0, reverse: Boolean = false,
    ): List<List<AuroraCelestialPoint>> {
        require(size.isFinite() && size in 0.1..32.0) { "Celestial size must be finite and in 0.1..32.0" }
        require(points in 1..192) { "Celestial points must be in 1..192" }
        require(frameCount in 2..MAX_FRAMES) { "Celestial frames must be in 2..$MAX_FRAMES" }
        require(roll.isFinite()) { "Celestial roll must be finite" }
        require(shape.isCelestial) { "Not a celestial shape: $shape" }
        val cosine = cos(roll)
        val sine = sin(roll)
        return List(frameCount) { frame ->
            val progress = frame.toDouble() / (frameCount - 1)
            val pose = when (shape) {
                AuroraShape.JELLYFISH -> jellyfish(points, progress)
                AuroraShape.MANTA_RAY -> manta(points, progress)
                AuroraShape.LIGHT_KNOT -> lightKnot(points, progress)
                AuroraShape.REVERSE_LIGHTNING -> reverseLightning(points, progress)
                AuroraShape.SKY_CORAL -> coral(points, progress)
                AuroraShape.MOON_FAN -> moonFan(points, progress)
                else -> error("Not a celestial shape: $shape")
            }
            val envelope = smooth(progress / 0.12) * smooth((1.0 - progress) / 0.16)
            pose.map { sample ->
                val point = sample.offset
                val x = if (reverse) -point.x else point.x
                sample.copy(offset = AuroraOffset((x * cosine - point.y * sine) * size,
                    (x * sine + point.y * cosine) * size, point.z * size),
                    opacity = (sample.opacity * envelope).coerceIn(0.0, 1.0))
            }
        }
    }

    /** One bounded lookup; scene time never replays a queue of missed cosmetic poses. */
    fun pointAt(frames: List<List<AuroraCelestialPoint>>, progress: Double, index: Int): AuroraCelestialPoint {
        require(progress.isFinite()) { "Celestial progress must be finite" }
        val position = progress.coerceIn(0.0, 1.0) * (frames.size - 1)
        val before = minOf(position.toInt(), frames.lastIndex - 1)
        val fraction = position - before
        val a = frames[before][index]
        val b = frames[before + 1][index]
        if (fraction == 0.0) return a
        if (fraction == 1.0) return b
        return AuroraCelestialPoint(AuroraOffset(
            a.offset.x + (b.offset.x - a.offset.x) * fraction,
            a.offset.y + (b.offset.y - a.offset.y) * fraction,
            a.offset.z + (b.offset.z - a.offset.z) * fraction),
            a.opacity + (b.opacity - a.opacity) * fraction, a.paletteSlot)
    }

    private fun jellyfish(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val breath = sin(progress * 4.0 * PI - PI / 2.0)
        val width = 0.68 + breath * 0.09
        val height = 0.38 - breath * 0.07
        val depth = 0.25 + breath * 0.025
        val lift = progress * 0.22 + smooth((progress - 0.76) / 0.24) * 0.3
        val drift = sin(progress * PI) * 0.1
        return strokes(points, doubleArrayOf(2.7, 2.0, 1.1, 1.4, 1.4, 1.4, 1.4)) { stroke, along ->
            val position: AuroraOffset
            val opacity: Double
            when (stroke) {
                0 -> {
                    val angle = along * 2.0 * PI
                    position = AuroraOffset(cos(angle) * width, 0.1, sin(angle) * depth)
                    opacity = 1.0
                }
                1, 2 -> {
                    val angle = along * PI
                    position = if (stroke == 1) AuroraOffset(cos(angle) * width, 0.1 + sin(angle) * height, 0.0)
                        else AuroraOffset(0.0, 0.1 + sin(angle) * height, cos(angle) * depth)
                    opacity = 1.0
                }
                else -> {
                    // Spread four roots across the bell: a radial arrangement hides two head-on.
                    val lateral = (stroke - 3) * 0.52 - 0.78
                    val angle = (stroke - 3) * 1.1
                    val length = 0.98 + (1.0 - abs(lateral)) * 0.28
                    val wave = sin(along * 2.0 * PI - progress * 5.0 * PI + angle)
                    position = AuroraOffset(lateral * width * (1.0 - along * 0.18) + wave * along * 0.1,
                        0.1 - along * length + breath * 0.035 * along,
                        sqrt(1.0 - lateral * lateral) * depth * if (stroke % 2 == 0) 1.0 else -1.0)
                    val reveal = smooth((progress - 0.04 - along * 0.16) / 0.12)
                    val pulse = (1.0 - abs(along - (progress - 0.3) / 0.32) / 0.2).coerceIn(0.0, 1.0)
                    opacity = reveal * (0.72 + pulse * 0.28)
                }
            }
            AuroraCelestialPoint(position.copy(x = position.x + drift, y = position.y + lift),
                opacity, if (stroke < 3) 0 else 1)
        }
    }

    private fun manta(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val flap = sin(progress * 4.0 * PI - PI / 2.0) * 0.28
        val bank = sin(progress * 2.0 * PI + 0.4) * 0.2
        return strokes(points, doubleArrayOf(3.0, 3.0, 1.5, 1.0, 1.0)) { stroke, along ->
            val raw = when (stroke) {
                0, 1 -> {
                    val sign = if (stroke == 0) -1.0 else 1.0
                    if (along < 0.48) {
                        val u = along / 0.48
                        AuroraOffset(sign * u * 1.08, 0.38 - u * 0.3 + sin(u * PI) * 0.1, sin(u * PI) * 0.08)
                    } else {
                        val u = (along - 0.48) / 0.52
                        AuroraOffset(sign * (1.0 - u) * 1.08, 0.08 - u * 0.28 - sin(u * PI) * 0.28, -sin(u * PI) * 0.06)
                    }
                }
                2 -> AuroraOffset(sin(along * 4.0 - progress * 3.0 * PI) * along * 0.075,
                    0.38 - along * 1.43, sin(along * 3.0 + progress * 4.0) * along * 0.04)
                else -> {
                    val sign = if (stroke == 3) -1.0 else 1.0
                    AuroraOffset(sign * along * 0.81, 0.05 - along * 0.1, sin(along * PI) * 0.05)
                }
            }
            val reach = abs(raw.x) / 1.08
            val wingY = raw.y + reach * reach * flap
            val position = AuroraOffset(raw.x * cos(bank) - wingY * sin(bank) + (progress - 0.5) * 0.55,
                raw.x * sin(bank) + wingY * cos(bank) + sin(progress * PI) * 0.08,
                raw.z + raw.x * sin(progress * PI) * 0.15)
            val opacity = smooth((progress - along * 0.04) / 0.13) * smooth((1.0 - progress - reach * 0.08) / 0.13)
            AuroraCelestialPoint(position, opacity, if (stroke < 2) 0 else 1)
        }
    }

    /** Two persistent ribbons braid in depth, tighten, then unwind; not the old one-head TRACK_KNOT. */
    private fun lightKnot(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val tied = smooth((progress - 0.08) / 0.3) * (1.0 - smooth((progress - 0.61) / 0.28))
        val departure = smooth((progress - 0.74) / 0.26)
        return strokes(points, doubleArrayOf(1.0, 1.0)) { stroke, along ->
            val sign = if (stroke == 0) -1.0 else 1.0
            val bow = sin(along * PI)
            val radius = 0.3 - tied * bow * 0.14
            val turn = (along - 0.5) * 4.0 * PI * tied
            val point = AuroraOffset((along * 2.0 - 1.0) * (1.0 - tied * bow * 0.42) + sign * departure * 0.3,
                sign * (cos(turn) * radius + departure * (0.16 + along * 0.18)),
                sign * sin(turn) * radius)
            AuroraCelestialPoint(point, smooth((progress - along * 0.05) / 0.12), stroke)
        }
    }

    /** Four tips flow into two forks, then one junction. Only one sample survives as the escaping drop. */
    private fun reverseLightning(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val left = AuroraOffset(-0.27, 0.23, -0.05)
        val right = AuroraOffset(0.27, 0.23, 0.05)
        val junction = AuroraOffset(0.0, -0.16, 0.0)
        val tips = listOf(AuroraOffset(-1.02, 0.58, -0.13), AuroraOffset(-0.48, 1.0, 0.1),
            AuroraOffset(0.48, 1.0, -0.1), AuroraOffset(1.02, 0.58, 0.13))
        val branches = strokes(points - 1, doubleArrayOf(1.3, 1.3, 1.3, 1.3, 0.9, 0.9)) { stroke, along ->
            val start = if (stroke < 4) tips[stroke] else if (stroke == 4) left else right
            val end = if (stroke < 4) { if (stroke < 2) left else right } else junction
            val sign = if (stroke % 2 == 0) -1.0 else 1.0
            val vertices = listOf(start,
                mix(start, end, 0.3).let { it.copy(x = it.x + sign * 0.08, y = it.y + 0.025) },
                mix(start, end, 0.58).let { it.copy(x = it.x - sign * 0.055, y = it.y - 0.035) },
                mix(start, end, 0.79).let { it.copy(x = it.x + sign * 0.025) }, end)
            val position = along * (vertices.size - 1)
            val edge = minOf(position.toInt(), vertices.size - 2)
            val point = mix(vertices[edge], vertices[edge + 1], position - edge)
            val startAt = if (stroke < 4) 0.0 else 0.27
            val travel = if (stroke < 4) 0.38 else 0.28
            val front = (progress - startAt) / travel
            val opacity = smooth((front - along) / 0.12) * smooth((along - front + 0.42) / 0.16)
            AuroraCelestialPoint(point, opacity, 0)
        }
        val escape = smooth((progress - 0.54) / 0.4)
        val drop = AuroraCelestialPoint(AuroraOffset(junction.x + escape * escape * 0.9,
            junction.y + escape * 1.55, sin(escape * PI) * 0.18), smooth((progress - 0.53) / 0.075), 1)
        return branches + drop
    }

    private data class CoralBranch(val from: AuroraOffset, val to: AuroraOffset, val level: Int, val sign: Double)

    private fun coral(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val branches = ArrayList<CoralBranch>(15)
        fun branch(from: AuroraOffset, direction: AuroraOffset, length: Double, level: Int, sign: Double) {
            val fold = smooth((progress - 0.72 + level * 0.025) / 0.26)
            val bend = unit(AuroraOffset(direction.x * (1.0 - fold * 0.35),
                direction.y + fold * 0.35, direction.z * (1.0 - fold * 0.35)))
            val reach = length * (1.0 - fold * 0.76)
            val to = AuroraOffset(from.x + bend.x * reach, from.y + bend.y * reach, from.z + bend.z * reach)
            branches += CoralBranch(from, to, level, sign)
            if (level < 3) {
                // Successive X, Z and diagonal split planes keep the crown broad from every azimuth.
                val plane = when (level) { 0 -> 0.0; 1 -> PI / 2.0; else -> PI / 4.0 }
                val dot = cos(plane) * bend.x + sin(plane) * bend.z
                val side = unit(AuroraOffset(cos(plane) - dot * bend.x, -dot * bend.y, sin(plane) - dot * bend.z))
                val angle = 0.68 * (1.0 - fold * 0.18)
                for (child in listOf(-1.0, 1.0)) {
                    val heading = AuroraOffset(bend.x * cos(angle) + side.x * sin(angle) * child,
                        bend.y * cos(angle) + side.y * sin(angle) * child,
                        bend.z * cos(angle) + side.z * sin(angle) * child)
                    branch(to, heading, length * 0.85, level + 1, child)
                }
            }
        }
        branch(AuroraOffset(0.0, -0.74, 0.0), AuroraOffset(0.0, 1.0, 0.0), 0.55, 0, 1.0)
        val weights = DoubleArray(branches.size) { 1.0 / (1.0 + branches[it].level * 0.35) }
        return strokes(points, weights) { stroke, along ->
            val branch = branches[stroke]
            val point = mix(branch.from, branch.to, along)
            val curvature = sin(along * PI) * branch.sign * 0.045
            val front = (progress - branch.level * 0.105) / 0.22
            val opacity = smooth((front - along) / 0.16) * smooth((1.0 - progress - branch.level * 0.025) / 0.14)
            AuroraCelestialPoint(point.copy(x = point.x + curvature,
                z = point.z + sin(along * PI) * sin(progress * 2.0 * PI + stroke) * 0.025), opacity,
                if (branch.level == 3) 1 else 0)
        }
    }

    private fun moonFan(points: Int, progress: Double): List<AuroraCelestialPoint> {
        val opened = smooth(progress / 0.25) * (1.0 - smooth((progress - 0.74) / 0.26))
        val turn = smooth((progress - 0.24) / 0.52) * PI
        return strokes(points, doubleArrayOf(0.8, 0.9, 1.0, 1.1, 1.2)) { stroke, along ->
            val radius = 0.7 + (stroke - 2) * 0.12 * opened
            val angle = (along - 0.5) * PI * 0.92
            val spread = (stroke - 2) * 0.2 * opened
            val x = sin(angle) * radius
            val y = 0.62 + cos(angle) * radius
            val fanX = x * cos(spread) - y * sin(spread)
            val fanY = x * sin(spread) + y * cos(spread) - 0.62
            val point = AuroraOffset(fanX * cos(turn), fanY, fanX * sin(turn))
            val opacity = smooth((progress - stroke * 0.016) / 0.14) * smooth((1.0 - progress - along * 0.045) / 0.12)
            AuroraCelestialPoint(point, opacity, stroke % 2)
        }
    }

    private fun mix(a: AuroraOffset, b: AuroraOffset, fraction: Double) = AuroraOffset(
        a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction, a.z + (b.z - a.z) * fraction)

    private fun unit(point: AuroraOffset): AuroraOffset {
        val length = sqrt(point.x * point.x + point.y * point.y + point.z * point.z)
        return AuroraOffset(point.x / length, point.y / length, point.z / length)
    }

    /** Weighted, interleaved strokes retain every part under a partial refresh budget. */
    private fun strokes(points: Int, weights: DoubleArray,
        sample: (stroke: Int, along: Double) -> AuroraCelestialPoint): List<AuroraCelestialPoint> {
        val total = weights.sum()
        val counts = IntArray(weights.size) { floor(points * weights[it] / total).toInt() }
        val remainderOrder = weights.indices.sortedByDescending { points * weights[it] / total - counts[it] }
        repeat(points - counts.sum()) { counts[remainderOrder[it]]++ }
        return buildList(points) {
            repeat(counts.max()) { step ->
                counts.indices.forEach { stroke ->
                    if (step < counts[stroke]) {
                        add(sample(stroke, if (counts[stroke] == 1) 0.5 else step.toDouble() / (counts[stroke] - 1)))
                    }
                }
            }
        }
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
}
