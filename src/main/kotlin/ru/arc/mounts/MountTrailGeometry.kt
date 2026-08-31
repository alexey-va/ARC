package ru.arc.mounts

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class MountTrailBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite)) {
            "Mount trail bounds must be finite"
        }
        require(maxX >= minX && maxY >= minY && maxZ >= minZ) { "Mount trail bounds are inverted" }
    }
}

internal fun mountTrailOrigin(
    bounds: MountTrailBounds,
    yawDegrees: Float,
    motionDirection: MotionVector,
    trail: MountTrailDefinition,
): MotionVector {
    val forward =
        motionDirection.normalizedHorizontal().takeUnless { it == MotionVector.ZERO }
            ?: run {
                val yaw = Math.toRadians(yawDegrees.toDouble().takeIf(Double::isFinite) ?: 0.0)
                MotionVector(-sin(yaw), 0.0, cos(yaw))
            }
    val width = bounds.maxX - bounds.minX
    val depth = bounds.maxZ - bounds.minZ
    val halfBodyAlongForward = abs(forward.x) * width / 2.0 + abs(forward.z) * depth / 2.0
    val rearDistance = halfBodyAlongForward + trail.backOffset
    return MotionVector(
        x = (bounds.minX + bounds.maxX) / 2.0 - forward.x * rearDistance,
        y = bounds.minY + (bounds.maxY - bounds.minY) * trail.heightRatio,
        z = (bounds.minZ + bounds.maxZ) / 2.0 - forward.z * rearDistance,
    )
}
