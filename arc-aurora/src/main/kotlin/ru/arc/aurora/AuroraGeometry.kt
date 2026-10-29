package ru.arc.aurora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class AuroraOffset(val x: Double, val y: Double, val z: Double)

/** Deterministic samples in local block coordinates; no platform state or random generator is read. */
object AuroraGeometry {
    /**
     * `size` is the radius/half-extent, not a diameter. POINT repeats the origin. LINE spans X.
     * CUBE distributes samples along its twelve wireframe edges. RING is the XZ circumference;
     * CIRCLE fills its disk with a sunflower sequence. SPHERE samples its surface with a Fibonacci
     * lattice. WAVE spans X, with a Y sine amplitude of size/4 and a 40-tick period (Z stays zero).
     * Static shapes ignore age. Every coordinate stays within [-size, size]. A single LINE sample
     * is centered; a single WAVE sample still moves vertically. Returned offsets are immutable.
     */
    fun offsets(shape: AuroraShape, size: Double, points: Int, ageTicks: Long): List<AuroraOffset> {
        require(size.isFinite() && size in 0.1..8.0) { "Shape size must be finite and in 0.1..8.0" }
        require(points in 1..24) { "Shape points must be in 1..24" }
        val phase = Math.floorMod(ageTicks, 40L) * PI / 20.0
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        return List(points) { index ->
            val along = if (points == 1) 0.0 else index.toDouble() / (points - 1) * 2.0 - 1.0
            val angle = index * goldenAngle
            when (shape) {
                AuroraShape.POINT -> AuroraOffset(0.0, 0.0, 0.0)
                AuroraShape.LINE -> AuroraOffset(along * size, 0.0, 0.0)
                AuroraShape.CUBE -> {
                    val position = (index + 0.5) * 12.0 / points
                    val edge = position.toInt()
                    val moving = (position - edge) * 2.0 * size - size
                    val first = if (edge and 1 == 0) -size else size
                    val second = if (edge and 2 == 0) -size else size
                    when (edge / 4) {
                        0 -> AuroraOffset(moving, first, second)
                        1 -> AuroraOffset(first, moving, second)
                        else -> AuroraOffset(first, second, moving)
                    }
                }
                AuroraShape.RING -> {
                    val ringAngle = index * 2.0 * PI / points
                    AuroraOffset(cos(ringAngle) * size, 0.0, sin(ringAngle) * size)
                }
                AuroraShape.CIRCLE -> {
                    val radius = sqrt((index + 0.5) / points) * size
                    AuroraOffset(cos(angle) * radius, 0.0, sin(angle) * radius)
                }
                AuroraShape.SPHERE -> {
                    val height = 1.0 - 2.0 * (index + 0.5) / points
                    val radius = sqrt(1.0 - height * height)
                    AuroraOffset(cos(angle) * radius * size, height * size, sin(angle) * radius * size)
                }
                AuroraShape.WAVE -> AuroraOffset(along * size, sin(along * PI + phase) * size / 4.0, 0.0)
                AuroraShape.HELIX -> AuroraOffset(along * size, sin(along * 2.0 * PI + phase) * size * 0.3,
                    cos(along * 2.0 * PI + phase) * size * 0.3)
                AuroraShape.ARC -> AuroraOffset(cos(along * PI * 2.0 / 3.0) * size,
                    sin(along * PI * 2.0 / 3.0) * size, 0.0)
                AuroraShape.CRYSTAL, AuroraShape.STAR -> AuroraSkyShapes.outlinePoint(shape, size, (along + 1.0) / 2.0)
                // Ordinary previews keep their configured extent; the complete timed meteor is a sky stroke.
                AuroraShape.METEOR -> AuroraSkyShapes.meteorPoint(size * 0.75, points, index, 0.0, 4)
            }
        }
    }

}
