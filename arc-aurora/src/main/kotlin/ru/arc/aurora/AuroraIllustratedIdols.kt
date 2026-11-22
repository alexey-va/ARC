package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin

/** The paper and the solid props share the viewer-facing axes of the scene. */
internal object AuroraIllustratedIdols : AuroraStoryParts {
    private const val KETTLE_SIZE = 0.90
    private const val KERNELS = 3

    private val heart = listOf(
        storyPart(Material.RED_WOOL, -0.083, 0.095, 0.0, 0.122, 0.072, 0.087),
        storyPart(Material.RED_WOOL,  0.083, 0.095, 0.0, 0.122, 0.072, 0.087),
        storyPart(Material.RED_WOOL, 0.0, 0.033, 0.0, 0.339, 0.086, 0.124),
        storyPart(Material.RED_WOOL, 0.0, -0.038, 0.0, 0.253, 0.061, 0.106),
        storyPart(Material.RED_WOOL, 0.0, -0.090, 0.0, 0.163, 0.050, 0.085),
        storyPart(Material.RED_CONCRETE, 0.0, -0.137, 0.0, 0.074, 0.050, 0.064),
        storyPart(Material.PINK_CONCRETE, -0.102, 0.081, 0.051, 0.044, 0.043, 0.029),
    )
    private val kernel = listOf(
        storyPart(Material.BONE_BLOCK, -0.013, 0.011, 0.0, 0.068, 0.059, 0.063, -0.22),
        storyPart(Material.SMOOTH_SANDSTONE, 0.019, 0.013, 0.008, 0.056, 0.064, 0.052, 0.44),
        storyPart(Material.YELLOW_WOOL, 0.001, -0.018, 0.012, 0.049, 0.041, 0.043, 0.24),
    )

    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.SAKURA_IDOL_2D -> 1 + heart.size
        AuroraShape.JU_FUFU_2D -> 1 + AuroraJuFufu.huWeiPartCount + KERNELS * kernel.size
        else -> error("Unsupported illustrated idol: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when {
        index == 0 -> figure(shape, t)
        shape == AuroraShape.SAKURA_IDOL_2D -> heartbeat(index - 1, t)
        index <= AuroraJuFufu.huWeiPartCount -> huWei(index - 1, t)
        else -> popcorn(index - 1 - AuroraJuFufu.huWeiPartCount, t)
    }

    private fun figure(shape: AuroraShape, t: Double): AuroraMiniaturePart {
        val sakura = shape == AuroraShape.SAKURA_IDOL_2D
        val side = if (sakura) 2.52 else 2.30
        val base = if (sakura) -0.135 else -0.065
        val phase = 2 * PI * t
        val breath = sin(2 * phase)
        val gesture = if (sakura) storyBeat(t, 0.18, 0.40) * (1 - storyBeat(t, 0.46, 0.66))
            else storyBeat(t, 0.40, 0.62) * (1 - storyBeat(t, 0.72, 0.91))
        val lean = if (sakura) 0.030 else -0.034
        return storyPart(Material.PAPER, 0.014 * sin(phase), base + 0.011 * breath + 0.015 * gesture, 0.0,
            side * (1 - 0.002 * breath), side * (1 + 0.005 * breath), 0.08,
            0.010 * sin(phase) + lean * gesture, 0.090 * sin(phase) + 0.055 * gesture)
            .copy(generatedSprite = true)
    }

    private fun offset(origin: AuroraOffset, facing: Quaternionf, x: Double, y: Double, z: Double): AuroraOffset {
        val d = facing.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
        return AuroraOffset(origin.x + d.x, origin.y + d.y, origin.z + d.z)
    }

    private fun palm(figure: AuroraMiniaturePart, u: Double, v: Double) =
        offset(figure.center, figure.rotation, (u - 0.5) * figure.scale.x, (0.5 - v) * figure.scale.y, 0.0)

    private fun place(piece: AuroraMiniaturePart, center: AuroraOffset, rotation: Quaternionf, size: Double): AuroraMiniaturePart {
        val c = piece.center
        return piece.copy(center = offset(center, rotation, c.x * size, c.y * size, c.z * size),
            rotation = Quaternionf(rotation).mul(piece.rotation), scale = Vector3f(piece.scale).mul(size.toFloat()))
    }

    private fun heartbeat(i: Int, t: Double): AuroraMiniaturePart {
        val hand = palm(figure(AuroraShape.SAKURA_IDOL_2D, t), 0.527911, 0.423646)
        val grow = storyBeat(t, 0.14, 0.30)
        val pulse = 1 + 0.115 * sin(PI * storyBeat(t, 0.30, 0.39))
        val size = grow * pulse * (1 - storyBeat(t, 0.74, 0.85))
        val flight = storyBeat(t, 0.39, 0.82) * (1 - storyBeat(t, 0.86, 0.98))
        val lift = sin(PI * flight)
        val center = AuroraOffset(hand.x - 0.053 * size - 0.77 * flight,
            hand.y + 0.188 * size + 0.24 * flight + 0.145 * lift,
            hand.z + 0.053 * size + 0.26 * flight)
        val turn = Quaternionf().rotateZ((-0.11 * flight + 0.045 * lift).toFloat())
            .rotateY((0.09 + 0.47 * lift).toFloat()).rotateX((-0.17 * lift).toFloat())
        return place(heart[i], center, turn, size)
    }

    private fun huWei(i: Int, t: Double): AuroraMiniaturePart {
        // Keep the shared kettle full size through the 3D heroine's entry and exit poses.
        val pose = AuroraJuFufu.huWeiPart(i, t.coerceIn(0.08, 0.91))
        val p = pose.center
        return pose.copy(center = AuroraOffset(0.598 + (p.x - 0.726) * KETTLE_SIZE,
            -0.709 + (p.y + 0.589) * KETTLE_SIZE, 0.145 + (p.z + 0.061) * KETTLE_SIZE),
            scale = Vector3f(pose.scale).mul(KETTLE_SIZE.toFloat()))
    }

    private fun popcorn(i: Int, t: Double): AuroraMiniaturePart {
        val n = i / kernel.size
        val start = 0.371 + 0.021 * n
        val land = start + 0.265 + 0.008 * n
        val hull = huWei(1, start)
        val from = offset(hull.center, hull.rotation, 0.0, 0.205 * KETTLE_SIZE, 0.034 * KETTLE_SIZE)
        val hand = palm(figure(AuroraShape.JU_FUFU_2D, t), 0.740008, 0.132053)
        val u = ((t - start) / (land - start)).coerceIn(0.0, 1.0) * (1 - storyBeat(t, 0.80, 0.93))
        val arc = 4 * u * (1 - u)
        val bend = when (n) { 0 -> 0.40; 1 -> 0.19; else -> 0.53 }
        val height = when (n) { 0 -> 0.78; 1 -> 0.56; else -> 0.66 }
        val center = AuroraOffset(from.x + (hand.x - from.x) * u + bend * arc,
            from.y + (hand.y - from.y) * u + height * arc,
            from.z + (hand.z + 0.045 - from.z) * u + 0.13 * arc)
        val size = (1 - 0.11 * n) * storyBeat(t, start, start + 0.019) * (1 - storyBeat(t, land + 0.008, land + 0.048))
        val spin = Quaternionf().rotateZ((n + 4.3 * u).toFloat()).rotateY((0.35 + 2.8 * u).toFloat())
        return place(kernel[i % kernel.size], center, spin, size)
    }
}
