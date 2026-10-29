package ru.arc.aurora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Detached local-space geometry. X is width, Y is up, and Z is depth before the fixed world pose. */
object AuroraSkyShapes {
    // An open walk over all twelve cube edges. Three repeated edges connect the eight odd vertices;
    // consecutive samples never teleport to a disconnected edge.
    private val cubeWalk = intArrayOf(6, 7, 5, 4, 6, 2, 3, 2, 0, 1, 5, 4, 0, 1, 3, 7)

    // Euler circuit of the elongated octahedron: every one of its twelve edges is drawn once.
    private val crystalVertices = listOf(
        AuroraOffset(0.0, 1.0, 0.0), AuroraOffset(0.0, -1.0, 0.0),
        AuroraOffset(0.55, 0.0, 0.0), AuroraOffset(0.0, 0.0, 0.55),
        AuroraOffset(-0.55, 0.0, 0.0), AuroraOffset(0.0, 0.0, -0.55),
    )
    private val crystalPath = intArrayOf(0, 2, 3, 0, 4, 3, 1, 2, 5, 4, 1, 5, 0).map(crystalVertices::get)
    private val starPath = (0..10).map { index ->
        val angle = PI / 2.0 + index * PI / 5.0
        val radius = if (index % 2 == 0) 1.0 else 0.43
        AuroraOffset(cos(angle) * radius, sin(angle) * radius, 0.0)
    }

    /** A complete pose for diagnostics; ordinary biome geometry lives in AuroraGeometry. */
    fun offsets(shape: AuroraShape, size: Double, points: Int, ageTicks: Long, periodTicks: Int = 400): List<AuroraOffset> =
        offsets(shape, size, points, ageTicks, periodTicks, AuroraSkyOptions())

    fun offsets(shape: AuroraShape, size: Double, points: Int, ageTicks: Long, periodTicks: Int,
        options: AuroraSkyOptions): List<AuroraOffset> {
        validate(size, points, periodTicks)
        if (shape.isTrack) return trackOffsets(shape, size, points, options)
        val phase = Math.floorMod(ageTicks, periodTicks.toLong()).toDouble() * 2.0 * PI / periodTicks
        return List(points) { sample(shape, size, points, it, phase, options) }
    }

    /**
     * One finite, ordered moving-head trajectory, prepared asynchronously once per scene.
     * Main releases one point at a time; native particles retain their own gravity, lifetime and fade.
     * The motion clock follows stroke progress, so skipped worker refreshes cannot jump its head.
     */
    fun stroke(
        shape: AuroraShape, size: Double, points: Int, updateTicks: Int, periodTicks: Int,
        phaseTicks: Int, roll: Double, reverse: Boolean, tracers: Int = 1,
    ): List<AuroraOffset> = stroke(shape, size, points, updateTicks, periodTicks, phaseTicks, roll, reverse, tracers, AuroraSkyOptions())

    fun stroke(
        shape: AuroraShape, size: Double, points: Int, updateTicks: Int, periodTicks: Int,
        phaseTicks: Int, roll: Double, reverse: Boolean, tracers: Int, options: AuroraSkyOptions,
    ): List<AuroraOffset> {
        validate(size, points, periodTicks)
        require(updateTicks in 1..200 && roll.isFinite() && tracers in 1..4)
        if (shape.isTrack) return trackOffsets(shape, size, points, options).map { point ->
            // Keep branch birth and inward winding ordered; reverse only mirrors the world pose.
            rotate(if (reverse) point.copy(x = -point.x) else point, 0.0, 0.0, roll)
        }
        if (shape == AuroraShape.METEOR) {
            val phase = phaseTicks * 2.0 * PI / periodTicks
            return List(points) { index ->
                val point = meteorPoint(size, points, index, phase, tracers, options)
                // Reverse spatial travel only: flight always precedes breakup, never reassembly.
                rotate(if (reverse) point.copy(x = -point.x) else point, 0.0, 0.0, roll)
            }
        }
        val steps = (points + tracers - 1) / tracers
        // Each tracer advances through one contiguous section; interleaving keeps partial budgets fair.
        val order = (0 until steps).flatMap { step -> (0 until tracers).map { it * steps + step }.filter { it < points } }
        return order.mapIndexed { emitted, index ->
            val step = emitted / tracers
            val progress = if (steps == 1) 0.0 else step.toDouble() / (steps - 1)
            val phase = (phaseTicks.toLong() + step.toLong() * updateTicks) * 2.0 * PI / periodTicks
            val point = sample(shape, size, points, if (reverse) points - 1 - index else index, phase, options)
            val posed = rotate(point, 0.0, 0.0, roll)
            AuroraOffset(posed.x + size * options.strokeDrift * progress, posed.y + size * options.strokeLift * sin(progress * PI), posed.z)
        }
    }

    /** Small coherent travel for held contours; computed off-thread, including its full envelope. */
    fun heldMotion(size: Double, durationTicks: Int, periodTicks: Int): List<AuroraOffset> =
        heldMotion(size, durationTicks, periodTicks, AuroraSkyOptions())

    fun heldMotion(size: Double, durationTicks: Int, periodTicks: Int,
        options: AuroraSkyOptions): List<AuroraOffset> {
        validate(size, 1, periodTicks)
        require(durationTicks in 40..1200)
        return List(durationTicks) { age ->
            AuroraOffset(sin(age.toDouble() / durationTicks * PI) * size * options.heldDrift,
                sin(age * 2.0 * PI / periodTicks) * size * options.heldLift, 0.0)
        }
    }

    private fun validate(size: Double, points: Int, periodTicks: Int) {
        require(size.isFinite() && size in 0.1..32.0) { "Sky shape size must be finite and in 0.1..32.0" }
        require(points in 1..192) { "Sky shape points must be in 1..192" }
        require(periodTicks in 40..2400) { "Sky motion period must be in 40..2400 ticks" }
    }

    private fun sample(shape: AuroraShape, size: Double, points: Int, index: Int, phase: Double,
        options: AuroraSkyOptions): AuroraOffset {
        val progress = if (points == 1) 0.5 else index.toDouble() / (points - 1)
        val along = progress * 2.0 - 1.0
        return when (shape) {
            AuroraShape.POINT -> AuroraOffset(0.0, 0.0, 0.0)
            AuroraShape.LINE -> AuroraOffset(along * size, 0.0, 0.0)
            AuroraShape.WAVE -> AuroraOffset(
                along * size,
                (sin(along * PI * options.waveCycles + phase) * options.waveAmplitude + along * options.waveSlope) * size,
                sin(along * PI * options.waveCycles / 2.0 + phase) * size * options.waveDepth,
            )
            AuroraShape.HELIX -> {
                val turn = progress * 2.0 * PI * options.helixTurns + phase
                AuroraOffset(along * size, sin(turn) * size * options.helixRadius, cos(turn) * size * options.helixRadius)
            }
            AuroraShape.ARC -> {
                val turn = radians(options.arcSweepDegrees) * (progress - 0.5)
                AuroraOffset(cos(turn) * size, sin(turn) * size, sin(progress * PI) * size * 0.12)
            }
            AuroraShape.CRYSTAL, AuroraShape.STAR -> outlinePoint(shape, size, progress)
            AuroraShape.METEOR -> meteorPoint(size, points, index, phase, 4, options)
            AuroraShape.TRACK_SPIRAL, AuroraShape.TRACK_PAIR, AuroraShape.TRACK_SCATTER ->
                error("Tracks are sampled as complete ordered paths above")
            AuroraShape.RING, AuroraShape.CIRCLE -> {
                val angle = progress * 2.0 * PI * if (shape == AuroraShape.RING) 1.0 else 2.0
                val radius = if (shape == AuroraShape.RING) size else sqrt(progress) * size
                rotate(AuroraOffset(cos(angle) * radius, sin(angle) * radius, 0.0),
                    radians(20.0 + sin(phase) * 8.0), radians(sin(phase) * 10.0), radians(cos(phase) * 8.0))
            }
            AuroraShape.CUBE -> {
                val position = progress * (cubeWalk.size - 1)
                val edge = minOf(position.toInt(), cubeWalk.size - 2)
                val from = cubeWalk[edge]
                val to = cubeWalk[edge + 1]
                val fraction = position - edge
                fun axis(bit: Int): Double {
                    val a = if (from and bit == 0) -size else size
                    val b = if (to and bit == 0) -size else size
                    return a + (b - a) * fraction
                }
                rotate(AuroraOffset(axis(1), axis(2), axis(4)), radians(22.0), phase + radians(35.0), radians(10.0))
            }
            AuroraShape.SPHERE -> {
                val height = 1.0 - 2.0 * progress
                val radius = sqrt(1.0 - height * height) * size
                val angle = progress * 6.0 * PI + phase
                AuroraOffset(cos(angle) * radius, height * size, sin(angle) * radius)
            }
        }
    }

    /** Arc-length sampling keeps adjacent contour dots evenly spaced, including across corners. */
    internal fun outlinePoint(shape: AuroraShape, size: Double, progress: Double): AuroraOffset {
        val path = when (shape) {
            AuroraShape.CRYSTAL -> crystalPath
            AuroraShape.STAR -> starPath
            else -> error("Not a custom outline: $shape")
        }
        val lengths = path.zipWithNext { a, b ->
            sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z))
        }
        var remaining = progress.coerceIn(0.0, 1.0) * lengths.sum()
        for (edge in lengths.indices) {
            if (remaining <= lengths[edge] || edge == lengths.lastIndex) {
                val fraction = (remaining / lengths[edge]).coerceIn(0.0, 1.0)
                val a = path[edge]
                val b = path[edge + 1]
                return AuroraOffset((a.x + (b.x - a.x) * fraction) * size,
                    (a.y + (b.y - a.y) * fraction) * size,
                    (a.z + (b.z - a.z) * fraction) * size)
            }
            remaining -= lengths[edge]
        }
        error("Empty custom outline")
    }

    /** Ordered flight, followed by simultaneous short fragment heads; no old trajectory is refreshed. */
    internal fun meteorPoint(size: Double, points: Int, index: Int, phase: Double, fragments: Int,
        options: AuroraSkyOptions = AuroraSkyOptions()): AuroraOffset {
        val flightPoints = minOf(points, maxOf(fragments, (points * options.meteorSplit).toInt() / fragments * fragments))
        if (index < flightPoints) {
            val progress = if (flightPoints == 1) 0.0 else index.toDouble() / (flightPoints - 1)
            return AuroraOffset((progress * 2.0 - 1.0) * size,
                (0.25 - progress * 0.45) * size, sin(progress * PI) * size * 0.04)
        }
        val indexAfterSplit = index - flightPoints
        val branch = indexAfterSplit % fragments
        val steps = (points - flightPoints + fragments - 1) / fragments
        val progress = (indexAfterSplit / fragments + 1).toDouble() / steps
        val angle = branch * 2.0 * PI / fragments + phase
        val reach = progress * options.meteorSpread * size
        return AuroraOffset(size + reach * 0.45, -size * 0.2 + reach * (sin(angle) * 0.8 - 0.2),
            cos(angle) * reach * 0.8)
    }

    private fun radians(degrees: Double) = degrees * PI / 180.0

    /** Artistic bubble-chamber tracks. Every branch has its own continuous, arc-length paced head. */
    internal fun trackOffsets(shape: AuroraShape, size: Double, points: Int,
        options: AuroraSkyOptions): List<AuroraOffset> {
        fun spiral(t: Double): AuroraOffset {
            val radius = 1.0 - (1.0 - options.trackTightening) * t
            val angle = t * 2.0 * PI * options.trackTurns
            return AuroraOffset(-radius * cos(angle), radius * sin(angle), 0.0)
        }
        fun arc(t: Double, heading: Double, bend: Double, length: Double): AuroraOffset =
            if (t == 0.0) AuroraOffset(0.0, 0.0, 0.0) else AuroraOffset((sin(heading + bend * t) - sin(heading)) * length / bend,
                (cos(heading) - cos(heading + bend * t)) * length / bend, 0.0)
        fun interleave(paths: List<List<AuroraOffset>>): List<AuroraOffset> =
            (0 until paths.maxOf { it.size }).flatMap { step -> paths.mapNotNull { it.getOrNull(step) } }
        val heading = radians(options.trackBranchAngleDegrees)
        val bend = radians(options.trackBendDegrees)
        val path = when (shape) {
            AuroraShape.TRACK_SPIRAL -> sampleCurve(points, ::spiral)
            AuroraShape.TRACK_PAIR -> interleave((0 until 2).map { branch ->
                val sign = if (branch == 0) 1.0 else -1.0
                sampleCurve((points + 1 - branch) / 2) { arc(it, heading * sign, bend * sign, 1.0) }
            })
            AuroraShape.TRACK_SCATTER -> {
                val incoming = (points * options.trackSplit).toInt().coerceIn(1, points)
                val stem = if (incoming == 1) listOf(AuroraOffset(0.0, 0.0, 0.0))
                    else sampleCurve(incoming) { AuroraOffset(it - 1.0, 0.0, 0.0) }
                val branches = (0 until 3).map { branch ->
                    val count = (points - incoming + 2 - branch) / 3
                    // The vertex was just emitted by the stem; start each branch one step beyond it.
                    sampleCurve(count + 1) { t -> when (branch) {
                        0 -> arc(t, -heading, -bend * 0.25, 0.95)
                        1 -> arc(t, heading, bend * 0.9, 0.85)
                        else -> {
                            val curl = spiral(t)
                            rotate(AuroraOffset((curl.x + 1.0) * 0.38, curl.y * 0.38, 0.0),
                                0.0, 0.0, heading - PI / 2.0)
                        }
                    } }.drop(1)
                }
                stem + interleave(branches)
            }
            else -> error("Not a chamber track: $shape")
        }
        return path.map { AuroraOffset(it.x * size, it.y * size, it.z * size) }
    }

    /** Fixed 512-chord lookup stays bounded on the worker; equal time advances equal path distance. */
    private fun sampleCurve(points: Int, curve: (Double) -> AuroraOffset): List<AuroraOffset> {
        if (points == 0) return emptyList()
        if (points == 1) return listOf(curve(0.0))
        val lookup = List(513) { curve(it / 512.0) }
        val lengths = DoubleArray(lookup.size)
        for (i in 1 until lookup.size) {
            val a = lookup[i - 1]; val b = lookup[i]
            lengths[i] = lengths[i - 1] + sqrt((a.x - b.x) * (a.x - b.x) +
                (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
        }
        var edge = 1
        return List(points) { index ->
            val distance = lengths.last() * index / (points - 1)
            while (edge < lengths.lastIndex && lengths[edge] < distance) edge++
            val a = lookup[edge - 1]; val b = lookup[edge]
            val fraction = (distance - lengths[edge - 1]) / (lengths[edge] - lengths[edge - 1])
            AuroraOffset(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction,
                a.z + (b.z - a.z) * fraction)
        }
    }

    /** Rigid X, then Y, then Z rotation. */
    private fun rotate(point: AuroraOffset, x: Double, y: Double, z: Double): AuroraOffset {
        val afterX = AuroraOffset(point.x, point.y * cos(x) - point.z * sin(x), point.y * sin(x) + point.z * cos(x))
        val afterY = AuroraOffset(afterX.x * cos(y) + afterX.z * sin(y), afterX.y, -afterX.x * sin(y) + afterX.z * cos(y))
        return AuroraOffset(afterY.x * cos(z) - afterY.y * sin(z), afterY.x * sin(z) + afterY.y * cos(z), afterY.z)
    }
}
