package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** ItemDisplay.NONE, in size-one scene space. Each snapshot owns its quaternion and scale. */
data class AuroraMiniaturePart(
    val material: Material,
    val center: AuroraOffset,
    val rotation: Quaternionf,
    val scale: Vector3f,
)

object AuroraSkyMiniatures {
    private const val TURN = 2.0 * PI

    fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.FEATHER_GLIDER, AuroraShape.ASTRAL_PENDULUM, AuroraShape.TWIN_SAMARAS -> 4
        AuroraShape.SKY_PHONOGRAPH -> 6
        else -> error("Not a sky miniature: $shape")
    }

    /** Bounding radii about each item's center, before scene size. Generated-item caps sit at Z ±1/32. */
    fun partRadius(shape: AuroraShape): Double = when (shape) {
        AuroraShape.FEATHER_GLIDER -> 0.86
        AuroraShape.ASTRAL_PENDULUM -> 0.90
        AuroraShape.TWIN_SAMARAS -> 0.78
        AuroraShape.SKY_PHONOGRAPH -> 1.43
        else -> error("Not a sky miniature: $shape")
    }

    fun frames(shape: AuroraShape): List<List<AuroraCelestialPoint>> {
        val count = partCount(shape)
        return List(AuroraCelestialShapes.MAX_FRAMES) { frame ->
            val progress = frame.toDouble() / (AuroraCelestialShapes.MAX_FRAMES - 1)
            val visible = visibility(progress)
            List(count) { index -> AuroraCelestialPoint(part(shape, index, progress).center, visible, 0) }
        }
    }

    fun part(shape: AuroraShape, index: Int, progress: Double): AuroraMiniaturePart {
        require(index in 0 until partCount(shape) && progress.isFinite())
        val t = progress.coerceIn(0.0, 1.0)
        return when (shape) {
            AuroraShape.FEATHER_GLIDER -> glider(index, t)
            AuroraShape.ASTRAL_PENDULUM -> pendulum(index, t)
            AuroraShape.TWIN_SAMARAS -> samaras(index, t)
            AuroraShape.SKY_PHONOGRAPH -> phonograph(index, t)
            else -> error("Not a sky miniature: $shape")
        }
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

    /** The copper rod hangs behind the dial. Its fixed-length sweep carries the honeycomb counterweight. */
    private fun pendulum(index: Int, t: Double): AuroraMiniaturePart {
        val swing = -0.68 * cos((t - 0.10).coerceAtLeast(0.0) * 3.0 * TURN) *
            (1.0 - 0.40 * smooth((t - 0.74) / 0.23))
        val clock = Assembly(AuroraOffset(0.0, 0.05, 0.0),
            Quaternionf().rotateY((0.18 * sin(TURN * t - 0.7)).toFloat()).rotateX(0.15f), visibility(t))
        val length = 1.22
        return when (index) {
            0 -> clock.piece(Material.CLOCK, 0.0, 0.38, 0.0, Vector3f(1.25f, 1.25f, 2.40f))
            1 -> clock.piece(Material.GOLD_NUGGET, 0.0, 0.99, 0.0, Vector3f(0.92f, 0.84f, 1.45f), zTurn(-0.40))
            2 -> clock.piece(Material.LIGHTNING_ROD, sin(swing) * length / 2, 0.25 - cos(swing) * length / 2,
                -0.12, Vector3f(0.98f, length.toFloat(), 0.98f), zTurn(swing))
            else -> clock.piece(Material.HONEYCOMB, sin(swing) * length, 0.25 - cos(swing) * length,
                -0.045, Vector3f(0.76f, 0.76f, 1.95f), zTurn(swing * 0.35))
        }
    }

    /** A paired samara parts at its two seed heads. Each wing autorotates about its own falling seed. */
    private fun samaras(index: Int, t: Double): AuroraMiniaturePart {
        val left = index < 2
        val side = if (left) -1.0 else 1.0
        val release = if (left) 0.18 else 0.24
        val fall = ((t - release) / (1.0 - release)).coerceIn(0.0, 1.0)
        val spin = side * (if (left) 2.10 else 1.62) * TURN * 1.14 * fall * fall / (fall + 0.14)
        val drift = smooth(fall / 0.72)
        val seed = AuroraOffset(side * (0.105 + drift * 0.55) + sin(spin) * drift * 0.11,
            0.51 - fall * (if (left) 1.58 else 1.31) + 0.045 * sin(spin),
            side * (0.04 + drift * 0.20) + drift * 0.12 * cos(spin))
        val facing = zTurn(side * 0.10 * sin(TURN * t)).rotateX(0.25f).rotateY((spin + side * 0.13).toFloat())
        val pair = Assembly(seed, facing, visibility(t))
        if (index % 2 == 0) return pair.piece(Material.GLOW_BERRIES, 0.0, 0.0, 0.025,
            Vector3f(0.51f, 0.53f, 2.23f), zTurn(side * 0.27))
        val elevation = 0.59 + 0.11 * sin(spin)
        val vane = zTurn(side * (elevation - 0.77)).rotateY(if (side > 0) PI.toFloat() else 0f)
        return pair.piece(Material.WHEAT, side * 0.41 * cos(elevation), 0.06 + 0.41 * sin(elevation),
            0.0, Vector3f(0.98f, 0.94f, 1.85f), vane)
    }

    /** The flower horn unfolds as the record lands. A single hinged pickup follows it inwards, then lifts clear. */
    private fun phonograph(index: Int, t: Double): AuroraMiniaturePart {
        val instrument = Assembly(AuroraOffset(0.0, -0.02, 0.0),
            Quaternionf().rotateY(-0.21f).rotateX(0.88f), visibility(t))
        if (index == 0) return instrument.piece(Material.JUKEBOX, 0.0, -0.49, 0.0, Vector3f(1.30f, 0.52f, 1.12f))
        if (index == 1) {
            val landing = 0.38 * (1.0 - smooth((t - 0.045) / 0.24))
            val spin = t * 4.2 * TURN * smooth(t / 0.18)
            val rotation = Quaternionf().rotateY(spin.toFloat()).rotateX((-PI / 2).toFloat())
            // The inventory sprite draws a 15×10 oblique disc, half a texel off-center. Turn it on a round spindle.
            val eccentricity = 1.16 / 32
            return instrument.piece(Material.MUSIC_DISC_CREATOR, -0.08 - eccentricity * cos(spin), -0.204 + landing,
                0.035 + eccentricity * sin(spin), Vector3f(1.16f, 1.74f, 0.62f), rotation)
        }
        if (index == 2 || index == 3) {
            val hinge = -0.60 * (1.0 - smooth((t - 0.065) / 0.25)) - 0.21 * smooth((t - 0.84) / 0.16)
            val neck = index == 2
            val x = if (neck) 0.19 else 0.34
            val y = if (neck) 0.27 else 0.70
            val cx = 0.18 + x * cos(hinge) - y * sin(hinge)
            val cy = -0.22 + x * sin(hinge) + y * cos(hinge)
            return if (neck) instrument.piece(Material.GOAT_HORN, cx, cy, -0.34,
                Vector3f(1.06f, 1.09f, 2.05f), zTurn(hinge + 0.55))
            else instrument.piece(Material.SUNFLOWER, cx, cy, -0.27, Vector3f(1.99f, 1.89f, 2.19f),
                zTurn(hinge - 0.30).rotateX(-0.72f).rotateY(0.29f))
        }
        val raised = 1.0 - smooth((t - 0.29) / 0.16) * (1.0 - smooth((t - 0.77) / 0.15))
        val x = 0.18 + 0.23 * smooth((t - 0.43) / 0.34) - 0.15 * raised
        // The bottom 3/16 of the iron nugget meets the generated disc cap at +1/32 of its depth scale.
        val y = 0.19 - 0.204 + 0.62 / 32 + 0.68 * 3 / 16 + 0.34 * raised
        val z = 0.51 - 0.14 * raised
        val arm = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
        val rotation = Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), arm)
        return if (index == 4) instrument.piece(Material.END_ROD, -0.48 + x / 2, -0.19 + y / 2, -0.27 + z / 2,
            Vector3f(0.82f, arm.length(), 0.82f), rotation)
        else instrument.piece(Material.IRON_NUGGET, -0.48 + x, -0.19 + y, -0.27 + z, Vector3f(0.68f, 0.68f, 1.28f))
    }
}
