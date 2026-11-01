package ru.arc.aurora

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin

/** Detached display pose, shared by the packet renderer and the source-driven preview. */
data class AuroraLanternPose(
    val translation: AuroraOffset,
    val rotationX: Float, val rotationY: Float, val rotationZ: Float, val rotationW: Float,
    val scale: Float,
)

/** A small world-anchored procession; size four gives nine blocks of rise and three of wind. */
object AuroraLanternMotion {
    const val MAX_LANTERNS = 6
    private val starts = listOf(
        AuroraOffset(-0.68, 0.0, -0.08),
        AuroraOffset(0.04, -0.20, 0.32),
        AuroraOffset(0.70, 0.12, -0.32),
        AuroraOffset(-0.38, 0.22, 0.86),
        AuroraOffset(0.48, -0.10, 1.04),
        AuroraOffset(-0.82, 0.35, -0.82),
    )

    fun frames(points: Int): List<List<AuroraCelestialPoint>> {
        require(points in 1..MAX_LANTERNS) { "Lantern count must be in 1..$MAX_LANTERNS" }
        return List(AuroraCelestialShapes.MAX_FRAMES) { frame ->
            val progress = frame.toDouble() / (AuroraCelestialShapes.MAX_FRAMES - 1)
            List(points) { index ->
                val flight = ((progress - delay(index)) / (1.0 - delay(index))).coerceIn(0.0, 1.0)
                val phase = index * 1.7
                val start = starts[index]
                AuroraCelestialPoint(AuroraOffset(
                    start.x + flight * 0.75 + sin(flight * 2.0 * PI) * 0.035,
                    start.y + flight * 2.25 + sin(flight * 4.0 * PI) * 0.025,
                    start.z + (sin(flight * 2.0 * PI + phase) - sin(phase)) * 0.035,
                ), visibility(index, progress), 0)
            }
        }
    }

    /** Item displays have no texture-alpha fade: birth and departure use a smooth size envelope. */
    fun visibility(index: Int, progress: Double): Double {
        require(index in 0 until MAX_LANTERNS && progress.isFinite())
        return smooth((progress - delay(index)) / 0.06) * smooth((1.0 - progress) / 0.16)
    }

    fun displayPose(index: Int, progress: Double, yawRadians: Double, scale: Double, pivot: AuroraOffset): AuroraLanternPose {
        require(index in 0 until MAX_LANTERNS && progress.isFinite() && yawRadians.isFinite())
        require(scale.isFinite() && scale > 0.0 && pivot.x.isFinite() && pivot.y.isFinite() && pivot.z.isFinite())
        val phase = progress.coerceIn(0.0, 1.0) * 8.0 * PI + index * 1.7
        val yaw = yawRadians + (index - 1) * 0.28 + sin(phase * 0.43) * 0.08
        val rotation = Quaternionf().rotateY(yaw.toFloat())
            .rotateZ((sin(phase) * Math.toRadians(3.5)).toFloat())
            .rotateX((sin(phase * 0.79 + 0.8) * Math.toRadians(2.0)).toFloat())
        val visibleScale = (scale * visibility(index, progress)).toFloat()
        // Item NONE already centers model coordinates by 0.5. Rotate around the remaining model pivot.
        val translation = rotation.transform(Vector3f(pivot.x.toFloat(), pivot.y.toFloat(), pivot.z.toFloat()))
            .mul(-visibleScale)
        return AuroraLanternPose(AuroraOffset(translation.x.toDouble(), translation.y.toDouble(), translation.z.toDouble()),
            rotation.x, rotation.y, rotation.z, rotation.w, visibleScale)
    }

    private fun delay(index: Int) = index * 0.055
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
}
