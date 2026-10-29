package ru.arc.aurora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detached local-space sky figures. X is the visible width, Y is up, and +Z faces the viewer.
 * The engine owns each stable world anchor, its facing, its age and its cyclic sample cursor.
 * A frame always contains the entire figure in a stable index order; a budgeted pass must resume
 * at its cursor instead of repeatedly emitting the beginning of this list.
 */
object AuroraSkyShapes {
    /**
     * [size] is the unposed radius/half-extent. WAVE is a broad, gently travelling sine ribbon;
     * RING/CIRCLE face the viewer with a shallow, slowly changing tilt. CUBE is a slowly rotating
     * twelve-edge wireframe. These poses deliberately avoid turning a ring edge-on to its initial
     * viewer. Native sprites supply their own lifetime/fade; this function never reads world state.
     */
    fun offsets(
        shape: AuroraShape,
        size: Double,
        points: Int,
        ageTicks: Long,
        periodTicks: Int = 400,
    ): List<AuroraOffset> {
        require(size.isFinite() && size in 0.1..32.0) { "Sky shape size must be finite and in 0.1..32.0" }
        require(points in 1..192) { "Sky shape points must be in 1..192" }
        require(periodTicks in 40..2400) { "Sky motion period must be in 40..2400 ticks" }
        val phase = Math.floorMod(ageTicks, periodTicks.toLong()).toDouble() * 2.0 * PI / periodTicks
        val goldenAngle = PI * (3.0 - sqrt(5.0))

        return List(points) { index ->
            val along = if (points == 1) 0.0 else index.toDouble() / (points - 1) * 2.0 - 1.0
            when (shape) {
                AuroraShape.POINT -> AuroraOffset(0.0, 0.0, 0.0)
                AuroraShape.LINE -> rotate(AuroraOffset(along * size, 0.0, 0.0), 0.0, 0.0, radians(10.0))
                AuroraShape.WAVE -> AuroraOffset(
                    along * size,
                    (sin(along * PI + phase) * 0.22 + along * 0.07) * size,
                    sin(along * PI / 2.0 + phase) * size * 0.05,
                )
                AuroraShape.RING, AuroraShape.CIRCLE -> {
                    val angle = if (shape == AuroraShape.RING) index * 2.0 * PI / points else index * goldenAngle
                    val radius = if (shape == AuroraShape.RING) size else sqrt((index + 0.5) / points) * size
                    rotate(
                        AuroraOffset(cos(angle) * radius, sin(angle) * radius, 0.0),
                        radians(20.0 + sin(phase) * 8.0),
                        radians(sin(phase) * 10.0),
                        radians(cos(phase) * 8.0),
                    )
                }
                AuroraShape.CUBE -> {
                    // Equal arclength allocation across all twelve edges, including sparse frames.
                    val position = (index + 0.5) * 12.0 / points
                    val edge = position.toInt()
                    val moving = (position - edge) * 2.0 * size - size
                    val first = if (edge and 1 == 0) -size else size
                    val second = if (edge and 2 == 0) -size else size
                    val point = when (edge / 4) {
                        0 -> AuroraOffset(moving, first, second)
                        1 -> AuroraOffset(first, moving, second)
                        else -> AuroraOffset(first, second, moving)
                    }
                    rotate(point, radians(22.0), phase + radians(35.0), radians(10.0))
                }
                AuroraShape.SPHERE -> {
                    val height = 1.0 - 2.0 * (index + 0.5) / points
                    val radius = sqrt(1.0 - height * height) * size
                    val angle = index * goldenAngle + phase
                    AuroraOffset(cos(angle) * radius, height * size, sin(angle) * radius)
                }
            }
        }
    }

    private fun radians(degrees: Double) = degrees * PI / 180.0

    /** Rigid X, then Y, then Z rotation: lengths and edge structure are preserved. */
    private fun rotate(point: AuroraOffset, x: Double, y: Double, z: Double): AuroraOffset {
        val afterX = AuroraOffset(point.x, point.y * cos(x) - point.z * sin(x), point.y * sin(x) + point.z * cos(x))
        val afterY = AuroraOffset(afterX.x * cos(y) + afterX.z * sin(y), afterX.y, -afterX.x * sin(y) + afterX.z * cos(y))
        return AuroraOffset(afterY.x * cos(z) - afterY.y * sin(z), afterY.x * sin(z) + afterY.y * cos(z), afterY.z)
    }
}
