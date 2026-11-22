package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraFrogRain : AuroraStoryParts {
    private const val PARTS_PER_FROG = 11
    const val PART_COUNT = 3 * PARTS_PER_FROG
    private const val TURN = 2.0 * PI

    private data class Frog(val skin: Material, val throat: Material, val lane: Double, val depth: Double,
        val heading: Double, val phase: Double)

    private val frogs = listOf(
        Frog(Material.ORANGE_TERRACOTTA, Material.STRIPPED_OAK_WOOD, -0.71, 0.21, -0.26, 0.0625),
        Frog(Material.BONE_BLOCK, Material.SMOOTH_SANDSTONE, 0.04, -0.29, 1.16, 0.375),
        Frog(Material.MOSS_BLOCK, Material.STRIPPED_BIRCH_WOOD, 0.71, 0.14, -1.10, 0.6875),
    )

    override fun partCount(shape: AuroraShape): Int {
        require(shape == AuroraShape.FROG_RAIN)
        return PART_COUNT
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        require(shape == AuroraShape.FROG_RAIN && index in 0 until PART_COUNT)
        val frog = frogs[index / PARTS_PER_FROG]
        val slot = index % PARTS_PER_FROG
        val phase = (t * 2.0 + frog.phase) % 1.0
        // Each 80-tick fall has 12–13 fully invisible ticks across its return to the top.
        val visible = storyBeat(phase, 0.03125, 0.092) * (1.0 - storyBeat(phase, 0.814, 0.872))
        val fall = ((phase - 0.03125) / (0.872 - 0.03125)).coerceIn(0.0, 1.0)
        val distance = (0.71 * fall + 0.29 * fall * fall) * (1.0 - storyBeat(phase, 0.885, 0.991))
        val drift = TURN * (t + frog.phase)
        val cx = frog.lane + 0.100 * sin(drift) + 0.025 * sin(phase * TURN)
        val cy = 1.8 - 3.6 * distance
        val cz = frog.depth + 0.117 * cos(drift)
        val stroke = TURN * (2.0 * phase + frog.phase)
        val heading = frog.heading + 0.26 * sin(drift) + 0.13 * sin(phase * TURN)
        val tilt = 0.32 + 0.17 * cos(stroke)
        val bank = 0.11 * sin(phase * TURN + frog.heading)
        val pose = Quaternionf().rotateY(heading.toFloat()).rotateX(tilt.toFloat()).rotateZ(bank.toFloat())

        fun piece(material: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
            yaw: Double = 0.0, pitch: Double = 0.0): AuroraMiniaturePart {
            val offset = pose.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat())).mul(visible.toFloat())
            val rotation = Quaternionf(pose).rotateY(yaw.toFloat()).rotateX(pitch.toFloat())
            val scale = Vector3f(w.toFloat(), h.toFloat(), d.toFloat()).mul(visible.toFloat())
            return AuroraMiniaturePart(material, AuroraOffset(cx + offset.x, cy + offset.y, cz + offset.z), rotation, scale)
        }
        val side = if (slot % 2 == 0) -1.0 else 1.0
        when (slot) {
            0 -> return piece(frog.skin, 0.0, 0.0, -0.010, 0.302, 0.129, 0.314)
            1 -> return piece(frog.throat, 0.0, -0.044, 0.078, 0.309, 0.068, 0.195)
            2, 3 -> return piece(frog.skin, side * 0.106, 0.098, 0.091, 0.101, 0.100, 0.094)
            4, 5 -> return piece(Material.BLACK_TERRACOTTA, side * 0.112, 0.105, 0.099, 0.094, 0.032, 0.100)
            // The bridge is inside the chest. Only its two short front feet project past the throat.
            10 -> return piece(frog.skin, 0.0, -0.053, 0.076, 0.402, 0.041, 0.077, pitch = -0.12)
        }

        // A hind leg folds its foot forward beside the belly, then presses it back past the knee.
        val kick = (1.0 - cos(stroke + side * 0.17)) * 0.5
        val kneeX = side * (0.164 + 0.020 * kick)
        val kneeY = -0.030 - 0.040 * kick
        val kneeZ = -0.088 - 0.057 * kick
        val foot = slot >= 8
        val ax = if (foot) kneeX else side * 0.108
        val ay = if (foot) kneeY else -0.002
        val az = if (foot) kneeZ else -0.037
        val sweep = sin(PI * kick)
        val bx = if (foot) kneeX + side * (0.034 + 0.030 * sweep) else kneeX
        val by = if (foot) kneeY - 0.020 - 0.025 * sweep - 0.012 * kick else kneeY
        val bz = if (foot) kneeZ + 0.106 - 0.228 * kick else kneeZ
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val flat = hypot(dx, dz)
        return piece(frog.skin, (ax + bx) * 0.5, (ay + by) * 0.5, (az + bz) * 0.5,
            if (foot) 0.087 else 0.105, if (foot) 0.035 else 0.092, hypot(flat, dy) + 0.025,
            yaw = atan2(dx, dz), pitch = -atan2(dy, flat))
    }
}
