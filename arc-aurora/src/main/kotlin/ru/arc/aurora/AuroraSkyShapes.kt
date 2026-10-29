package ru.arc.aurora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Detached local-space geometry. X is visible width, Y is up, and +Z faces the creation viewer. */
object AuroraSkyShapes {
    // An open walk over all twelve cube edges. Three repeated edges connect the eight odd vertices;
    // consecutive samples never teleport to a disconnected edge.
    private val cubeWalk = intArrayOf(6, 7, 5, 4, 6, 2, 3, 2, 0, 1, 5, 4, 0, 1, 3, 7)

    /** A complete pose for diagnostics; ordinary biome geometry lives in AuroraGeometry. */
    fun offsets(shape: AuroraShape, size: Double, points: Int, ageTicks: Long, periodTicks: Int = 400): List<AuroraOffset> =
        offsets(shape, size, points, ageTicks, periodTicks, AuroraSkyOptions())

    fun offsets(shape: AuroraShape, size: Double, points: Int, ageTicks: Long, periodTicks: Int,
        options: AuroraSkyOptions): List<AuroraOffset> {
        validate(size, points, periodTicks)
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

    private fun radians(degrees: Double) = degrees * PI / 180.0

    /** Rigid X, then Y, then Z rotation. */
    private fun rotate(point: AuroraOffset, x: Double, y: Double, z: Double): AuroraOffset {
        val afterX = AuroraOffset(point.x, point.y * cos(x) - point.z * sin(x), point.y * sin(x) + point.z * cos(x))
        val afterY = AuroraOffset(afterX.x * cos(y) + afterX.z * sin(y), afterX.y, -afterX.x * sin(y) + afterX.z * cos(y))
        return AuroraOffset(afterY.x * cos(z) - afterY.y * sin(z), afterY.x * sin(z) + afterY.y * cos(z), afterY.z)
    }
}
