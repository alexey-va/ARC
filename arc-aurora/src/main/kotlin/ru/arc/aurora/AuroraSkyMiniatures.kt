package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** ItemDisplay.NONE, in size-one scene space. Each snapshot owns its quaternion and scale. */
data class AuroraMiniaturePart @JvmOverloads constructor(
    val material: Material,
    val center: AuroraOffset,
    val rotation: Quaternionf,
    val scale: Vector3f,
    internal val generatedSprite: Boolean = false,
) {
    // Vanilla generated items occupy Z ±1/32 in the NONE display context. Blocks can fill Z ±1/2.
    internal fun meshRadius(): Double {
        val depth = if (generatedSprite) scale.z / 16.0 else scale.z.toDouble()
        return 0.5 * sqrt(scale.x.toDouble() * scale.x + scale.y.toDouble() * scale.y + depth * depth)
    }
}

internal interface AuroraStoryParts {
    fun partCount(shape: AuroraShape): Int
    fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart
}

internal fun storyBeat(t: Double, start: Double, end: Double): Double =
    AuroraCelestialShapes.smooth((t - start) / (end - start))

/** The authored coordinates use X across, Y up and positive Z towards the viewer. Angles are radians. */
internal fun storyPart(material: Material, x: Double, y: Double, z: Double,
    sx: Double, sy: Double, sz: Double, roll: Double = 0.0, yaw: Double = 0.0, pitch: Double = 0.0) =
    AuroraMiniaturePart(material, AuroraOffset(x, y, z),
        Quaternionf().rotateZ(roll.toFloat()).rotateY(yaw.toFloat()).rotateX(pitch.toFloat()),
        Vector3f(sx.toFloat(), sy.toFloat(), sz.toFloat()))

object AuroraSkyMiniatures {
    private const val TURN = 2.0 * PI

    fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.FEATHER_GLIDER -> 4
        else -> requireNotNull(shape.storyParts) { "Not a sky miniature: $shape" }.partCount(shape)
    }

    /** Bounding radii about each item's center, before scene size. Generated-item caps sit at Z ±1/32. */
    fun partRadius(shape: AuroraShape): Double = when (shape) {
        AuroraShape.FEATHER_GLIDER -> 0.86
        else -> error("Only the glider uses a fixed item radius: $shape")
    }

    fun frames(shape: AuroraShape): List<List<AuroraCelestialPoint>> {
        val count = partCount(shape)
        return List(AuroraCelestialShapes.MAX_FRAMES) { frame ->
            val progress = frame.toDouble() / (AuroraCelestialShapes.MAX_FRAMES - 1)
            val visible = visibility(progress)
            List(count) { index ->
                val pose = part(shape, index, progress)
                val opacity = when {
                    !shape.isStory -> visible
                    pose.scale.lengthSquared() > 0f -> 1.0
                    else -> 0.0
                }
                AuroraCelestialPoint(pose.center, opacity, 0)
            }
        }
    }

    fun part(shape: AuroraShape, index: Int, progress: Double): AuroraMiniaturePart =
        part(shape, index, progress, packFace = false)

    internal fun part(shape: AuroraShape, index: Int, progress: Double, packFace: Boolean): AuroraMiniaturePart {
        require(index in 0 until partCount(shape) && progress.isFinite())
        val t = progress.coerceIn(0.0, 1.0)
        val story = shape.storyParts ?: return glider(index, t)
        val pose = when {
            packFace && index == AuroraJuFufu.packFaceIndex -> AuroraJuFufu.packFacePart(t)
            packFace && AuroraJuFufu.isPackFaceFallback(index) ->
                return story.part(shape, index, t).copy(scale = Vector3f())
            else -> story.part(shape, index, t)
        }
        if (shape.loopsAtFullSize) return pose
        val fade = smooth(t / 0.02) * smooth((1.0 - t) / 0.02)
        if (fade == 1.0) return pose
        val center = pose.center
        return pose.copy(center = AuroraOffset(center.x * fade, center.y * fade, center.z * fade),
            scale = Vector3f(pose.scale).mul(fade.toFloat()))
    }

    private fun visibility(t: Double) = smooth(t / 0.10) * smooth((1.0 - t) / 0.13)
    private fun smooth(t: Double) = AuroraCelestialShapes.smooth(t)
    private fun zTurn(angle: Double) = Quaternionf().rotationZ(angle.toFloat())

    /** Scaling the offsets about the same moving pivot as the meshes keeps joined pieces together at birth and exit. */
    private class Assembly(val origin: AuroraOffset, val facing: Quaternionf, val visible: Double) {
        fun piece(material: Material, x: Double, y: Double, z: Double, scale: Vector3f,
            rotation: Quaternionf = Quaternionf()): AuroraMiniaturePart {
            val point = facing.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat())).mul(visible.toFloat())
            return AuroraMiniaturePart(material, AuroraOffset(origin.x + point.x, origin.y + point.y, origin.z + point.z),
                Quaternionf(facing).mul(rotation), scale.mul(visible.toFloat()))
        }
    }

    /** Two quills unfold about the keel, hold their span in the bank, then sweep back for the dive. */
    private fun glider(index: Int, t: Double): AuroraMiniaturePart {
        val spread = smooth((t - 0.08) / 0.24) * (1.0 - smooth((t - 0.73) / 0.23))
        val bank = 0.34 * sin(TURN * (t - 0.13)) - 0.18 * smooth((t - 0.69) / 0.26)
        val route = AuroraOffset(-0.66 + 1.36 * t, -0.16 + 0.39 * sin(t * PI) - 0.22 * t * t, -0.60 + 1.38 * t)
        val facing = zTurn(bank).rotateY((0.24 * sin(TURN * t)).toFloat()).rotateX(0.34f)
        val glider = Assembly(route, facing, visibility(t))
        if (index == 0) return glider.piece(Material.DIAMOND, 0.0, 0.11, 0.09, Vector3f(0.54f, 0.98f, 2.20f))
        if (index == 3) return glider.piece(Material.PRISMARINE_SHARD, 0.0, -0.35, -0.02,
            Vector3f(0.52f, 0.66f, 1.50f), zTurn(-2.34))
        val side = if (index == 1) -1.0 else 1.0
        val elevation = -1.11 + 1.30 * spread + 0.055 * sin(3.0 * TURN * t) * spread
        // The native Y-half-turn puts the quill tip in upper-left. Mirror the right wing about Y.
        val feather = zTurn(side * (elevation - 0.88)).rotateY(if (side > 0) PI.toFloat() else 0f)
        return glider.piece(Material.FEATHER, side * (0.095 + cos(elevation) * 0.52),
            0.10 + sin(elevation) * 0.52, -0.02, Vector3f(1.16f, 1.16f, 1.75f), feather)
    }
}
