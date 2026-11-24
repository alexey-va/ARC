package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A section of the moving canopy. Local +Z points inward and the suckers face the ground. */
internal object AuroraEndlessTentacles : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private const val LONG_SEGMENTS = 15
    private const val SHORT_SEGMENTS = 11
    private const val ROOT_SEGMENTS = 2
    private val longCups = intArrayOf(1, 2, 4, 5, 7, 9)
    private val shortCups = intArrayOf(1, 3, 5, 7)

    override fun partCount(shape: AuroraShape) = LONG_SEGMENTS + SHORT_SEGMENTS + longCups.size + shortCups.size + 2 * ROOT_SEGMENTS

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val split = ROOT_SEGMENTS + LONG_SEGMENTS + longCups.size
        val long = index < split
        val slot = (if (long) index else index - split) - ROOT_SEGMENTS
        val segments = if (long) LONG_SEGMENTS else SHORT_SEGMENTS
        val cup = slot - segments
        val segment = if (cup < 0) slot else (if (long) longCups else shortCups)[cup]
        val phase = TURN * (t % 1.0) + if (long) 0.0 else 2.42
        val stretch = 0.5 - 0.5 * cos(phase)
        val a = joint(long, segment, phase, stretch)
        val b = joint(long, segment + 1, phase, stretch)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dz)
        val u = (segment + 0.5) / segments
        val baseWidth = if (long) 0.180 else 0.135
        val girth = baseWidth * when (segment) {
            -2 -> 0.22
            -1 -> 0.64
            else -> 1.0 - 0.56 * u - 0.38 * u * u
        }
        val roll = (if (long) 0.12 else -0.18) + 0.32 * sin(phase - 4.1 * u)
        val facing = Quaternionf().rotationY(atan2(dx, dz).toFloat())
            .rotateX((-atan2(dy, flat)).toFloat()).rotateZ(roll.toFloat())
        val midX = (a.x + b.x) * 0.5
        val midY = (a.y + b.y) * 0.5
        val midZ = (a.z + b.z) * 0.5
        if (cup < 0) {
            return AuroraMiniaturePart(if (long) Material.STRIPPED_CRIMSON_HYPHAE else Material.BLUE_TERRACOTTA,
                AuroraOffset(midX, midY, midZ), facing,
                Vector3f(girth.toFloat(), (girth * 0.87).toFloat(), (hypot(flat, dy) + girth * 0.36).toFloat()))
        }

        // Its mauve concentric ridges form a rounded sucker without a custom pack.
        val side = if (cup % 2 == 0) -0.18 else 0.18
        val offset = facing.transform(Vector3f((girth * side).toFloat(), (-girth * 0.458).toFloat(), 0f))
        facing.rotateX((PI / 2).toFloat()).rotateZ((side * 1.8).toFloat())
        return AuroraMiniaturePart(Material.POPPED_CHORUS_FRUIT, AuroraOffset(midX + offset.x, midY + offset.y, midZ + offset.z),
            facing, Vector3f((girth * 0.90).toFloat(), (girth * 0.78).toFloat(), (girth * 1.25).toFloat()), generatedSprite = true)
    }

    private fun joint(long: Boolean, index: Int, phase: Double, stretch: Double): AuroraOffset {
        val stem = if (long) 9 else 6
        val side = if (long) 1.0 else -1.0
        val rootX = if (long) -0.21 else 0.29
        val rootY = if (long) -0.085 else 0.045
        val rootZ = if (long) -0.16 else -0.060
        if (index < 0) {
            // Narrow threads drift out under the rim, away from the inward-moving tips.
            val out = -index.toDouble() / ROOT_SEGMENTS
            return AuroraOffset(rootX - side * out * (0.33 + 0.20 * sin(phase + 1.7 * out)),
                rootY - out * (0.16 + 0.08 * cos(phase - out)),
                rootZ - out * (0.47 + 0.12 * sin(phase)))
        }
        val bendX = rootX + side * (0.025 + 0.17 * sin(phase + 0.47))
        val bendY = (if (long) 0.30 else 0.25) + 0.105 * sin(phase - 0.57)
        val bendZ = if (long) 1.21 + 0.59 * stretch else 0.80 + 0.40 * stretch
        if (index <= stem) {
            val u = index.toDouble() / stem
            val blend = u * u * (3.0 - 2.0 * u)
            val arch = sin(PI * u)
            val bow = arch * arch
            return AuroraOffset(rootX + (bendX - rootX) * blend + side * bow * 0.30 * sin(4.15 * u - phase),
                rootY + (bendY - rootY) * blend + bow * (0.12 + 0.13 * sin(phase - 3.8 * u)),
                rootZ + (bendZ - rootZ) * u)
        }
        // Continue along the stem tangent, then fold the last links back toward the wrist.
        val u = (index - stem).toDouble() / ((if (long) LONG_SEGMENTS else SHORT_SEGMENTS) - stem)
        val sweep = if (long) 4.65 - 2.35 * stretch else 4.32 - 1.65 * stretch
        val theta = u * sweep
        val radius = if (long) 0.208 + 0.105 * stretch else 0.155 + 0.055 * stretch
        val curl = 1.0 - cos(theta)
        return AuroraOffset(bendX + side * radius * curl,
            bendY + 0.078 * curl + 0.12 * u * u * sin(phase + 0.67), bendZ + radius * sin(theta))
    }
}
