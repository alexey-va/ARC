package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal object AuroraAzathoth : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private val arms = listOf(
        Arm(0.218, 0.158, 0.6, 0.39, Material.CRIMSON_HYPHAE, listOf(
            p(-0.65, 0.33, -0.23), p(-0.94, 0.55, -0.08), p(-1.19, 0.72, 0.05),
            p(-1.31, 0.99, 0.15), p(-1.22, 1.19, 0.19), p(-0.99, 1.24, 0.18), p(-0.89, 1.08, 0.24))),
        Arm(0.242, 0.177, 2.1, -0.26, Material.NETHER_WART_BLOCK, listOf(
            p(-0.70, -0.04, 0.07), p(-1.00, -0.16, 0.16), p(-1.27, -0.29, 0.31),
            p(-1.45, -0.51, 0.45), p(-1.43, -0.73, 0.48), p(-1.23, -0.85, 0.49))),
        Arm(0.252, 0.194, 4.2, 0.29, Material.STRIPPED_CRIMSON_HYPHAE, listOf(
            p(-0.11, -0.52, -0.03), p(-0.32, -0.72, 0.17), p(-0.54, -0.92, 0.25),
            p(-0.50, -1.16, 0.38), p(-0.29, -1.31, 0.52), p(-0.035, -1.28, 0.62), p(0.10, -1.13, 0.66))),
        Arm(0.167, 0.174, 5.1, -0.53, Material.CRIMSON_HYPHAE, listOf(
            p(0.57, 0.16, -0.16), p(0.91, 0.24, -0.07), p(1.11, 0.43, 0.03),
            p(1.19, 0.67, 0.11), p(1.40, 0.82, 0.24), p(1.56, 0.73, 0.32))),
        Arm(0.237, 0.129, 3.3, 0.41, Material.CRIMSON_HYPHAE, listOf(
            p(0.65, -0.25, -0.32), p(0.94, -0.38, -0.41), p(1.17, -0.55, -0.35),
            p(1.19, -0.81, -0.22), p(0.99, -0.93, -0.02), p(0.84, -0.83, 0.26))),
        Arm(0.154, 0.118, 1.0, 0.51, Material.NETHER_WART_BLOCK, listOf(
            p(0.16, 0.38, -0.38), p(0.13, 0.66, -0.52), p(0.22, 0.88, -0.59),
            p(0.43, 1.01, -0.56), p(0.61, 0.96, -0.40), p(0.66, 0.83, -0.23))),
    )
    private data class Arm(val rootWidth: Double, val eye: Double, val phase: Double, val curl: Double,
        val skin: Material, val path: List<AuroraOffset>)

    // One cheek is high and heavy. The opposite corner of the mouth hangs down and forward.
    private val lips = listOf(p(-0.70, 0.08, 0.46), p(-0.53, 0.31, 0.56), p(-0.32, 0.49, 0.65),
        p(-0.05, 0.46, 0.67), p(0.25, 0.42, 0.62), p(0.48, 0.27, 0.61), p(0.60, 0.02, 0.56),
        p(0.52, -0.25, 0.64), p(0.32, -0.48, 0.69), p(0.035, -0.54, 0.66),
        p(-0.28, -0.43, 0.58), p(-0.56, -0.25, 0.48))

    private fun p(x: Double, y: Double, z: Double) = AuroraOffset(x, y, z)
    private fun between(a: AuroraOffset, b: AuroraOffset, t: Double) = AuroraCelestialShapes.mix(a, b, t)
    private fun gape(t: Double) = storyBeat(t, 0.12, 0.44) * (1 - storyBeat(t, 0.476, 0.548))
    private fun bite(t: Double, lag: Double = 0.0) =
        storyBeat(t, 0.472 + lag, 0.542 + lag) * (1 - storyBeat(t, 0.583 + lag, 0.761 + lag))

    private fun mouth(position: Double, reach: Double, t: Double): AuroraOffset {
        val n = position.toInt()
        val v = between(lips[n % lips.size], lips[(n + 1) % lips.size], position - n)
        val draw = gape(t)
        val shut = bite(t)
        return p(0.12 + v.x * reach * (0.97 + 0.13 * draw - 0.13 * shut) - 0.072 * (1 - reach),
            -0.03 + v.y * reach * (0.88 + 0.32 * draw - 0.57 * shut) + 0.042 * (1 - reach),
            0.10 + (v.z - 0.10 + 0.073 * draw + 0.064 * shut) * reach)
    }

    private fun cord(material: Material, a: AuroraOffset, b: AuroraOffset, girth: Double, depth: Double,
        overlap: Double = 0.0): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val xy = hypot(dx, dy)
        return storyPart(material, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(xy, dz) + overlap, girth, depth, atan2(dy, dx), atan2(-dz, xy))
    }

    private fun armPoint(arm: Arm, joint: Int, t: Double): AuroraOffset {
        val root = arm.path.first()
        val at = arm.path[joint]
        val u = joint.toDouble() / arm.path.lastIndex
        val wave = bite(t, 0.035 + 0.161 * u + 0.006 * arm.phase)
        val angle = u * (0.099 * sin(TURN * t + arm.phase + 0.85 * u) + arm.curl * wave)
        val x = at.x - root.x
        val y = at.y - root.y
        return p(root.x + 0.78 * (x * cos(angle) - y * sin(angle)), root.y + y * cos(angle) + x * sin(angle),
            at.z + u * (0.090 * sin(TURN * t + arm.phase - 1.4 * u) + 0.18 * wave))
    }

    private data class Tooth(val position: Double, val length: Double, val slant: Double, val width: Double,
        val inner: Boolean = false, val hooked: Boolean = false)

    private val teeth = listOf(
        Tooth(0.83, 0.214, 0.66, 0.081), Tooth(1.43, 0.422, 0.24, 0.115, hooked = true),
        Tooth(2.16, 0.222, 0.32, 0.076), Tooth(2.72, 0.295, 0.15, 0.098),
        Tooth(3.38, 0.166, -0.14, 0.066), Tooth(3.94, 0.265, -0.23, 0.083),
        Tooth(4.56, 0.363, -0.40, 0.107, hooked = true), Tooth(5.19, 0.227, -0.62, 0.075),
        Tooth(6.68, 0.195, -0.59, 0.068), Tooth(7.44, 0.329, -0.36, 0.099, hooked = true),
        Tooth(8.24, 0.202, -0.19, 0.071), Tooth(8.95, 0.353, 0.15, 0.106, hooked = true),
        Tooth(9.62, 0.228, 0.30, 0.069), Tooth(10.23, 0.153, 0.47, 0.065), Tooth(10.82, 0.261, 0.67, 0.075),
        Tooth(1.94, 0.158, 0.44, 0.074, inner = true), Tooth(2.91, 0.120, 0.19, 0.067, inner = true),
        Tooth(3.63, 0.139, -0.19, 0.063, inner = true), Tooth(4.79, 0.161, -0.44, 0.078, inner = true),
        Tooth(7.79, 0.138, -0.35, 0.072, inner = true), Tooth(8.58, 0.113, -0.16, 0.065, inner = true),
        Tooth(9.34, 0.152, 0.20, 0.072, inner = true), Tooth(10.05, 0.110, 0.46, 0.065, inner = true),
    )

    private fun toothPoint(tooth: Tooth, fraction: Double, t: Double): AuroraOffset {
        val at = mouth(tooth.position, if (tooth.inner) 0.68 else 0.92, t)
        val shut = bite(t)
        val x = tooth.slant
        val y = (if (tooth.position < 6) -1 else 1) * (1 - 0.40 * shut)
        val z = -0.29 - 0.92 * shut
        val length = tooth.length * fraction / sqrt(x * x + y * y + z * z)
        val bend = if (tooth.hooked) (if (x > 0) 1 else -1) * tooth.length * 0.43 * fraction * fraction else 0.0
        return p(at.x + x * length + bend, at.y + y * length, at.z + (if (tooth.inner) 0.030 else 0.088) + z * length)
    }

    /** The sugar cap spans 11 native texels; quartz spans 15 and supplies a little cluster of inner needles. */
    private fun fang(tooth: Tooth, t: Double): AuroraMiniaturePart {
        val root = toothPoint(tooth, if (tooth.hooked) 0.40 else 0.0, t)
        val tip = toothPoint(tooth, 1.0, t)
        val dx = tip.x - root.x
        val dy = tip.y - root.y
        val dz = tip.z - root.z
        val xy = hypot(dx, dy)
        val location = between(root, tip, if (tooth.inner) 7.0 / 15 else 6.0 / 11)
        return storyPart(if (tooth.inner) Material.QUARTZ else Material.SUGAR, location.x, location.y, location.z,
            tooth.width * 1.34, hypot(xy, dz) * 16 / (if (tooth.inner) 15 else 11), tooth.width * 8.5,
            roll = atan2(-dx, dy), pitch = atan2(dz, xy))
    }

    private val body = listOf(
        storyPart(Material.DRAGON_EGG, -0.42, 0.21, -0.42, 1.48, 1.28, 1.25, -0.27, 0.25, 0.12),
        storyPart(Material.DRAGON_EGG, 0.43, -0.11, -0.38, 1.50, 1.22, 1.08, -1.01, -0.41, 0.22),
        storyPart(Material.DRAGON_EGG, -0.25, -0.39, -0.42, 1.39, 0.94, 1.15, 0.48, 0.57, -0.22),
        storyPart(Material.CRIMSON_HYPHAE, -0.50, 0.46, -0.31, 0.94, 0.44, 0.89, 0.18, 0.27, -0.13),
        storyPart(Material.NETHERRACK, 0.38, 0.35, -0.39, 0.95, 0.56, 0.87, -0.19, -0.30, 0.18),
        storyPart(Material.CRIMSON_HYPHAE, 0.44, -0.38, -0.29, 1.05, 0.54, 0.89, 0.32, 0.26, -0.20),
        storyPart(Material.DRAGON_EGG, -0.80, 0.19, -0.15, 0.85, 0.97, 0.81, 0.58, -0.42, -0.31),
        storyPart(Material.DRAGON_EGG, 0.63, -0.32, 0.01, 0.74, 0.90, 0.70, -0.85, 0.42, 0.27),
        storyPart(Material.FERMENTED_SPIDER_EYE, -0.51, 0.46, 0.13, 1.12, 0.90, 3.90, 0.31, -0.38, 0.29),
        storyPart(Material.FERMENTED_SPIDER_EYE, -0.83, 0.12, 0.18, 0.83, 0.79, 3.70, 1.77, -0.26, -0.18),
        storyPart(Material.FERMENTED_SPIDER_EYE, -0.64, -0.33, 0.13, 0.81, 0.71, 4.20, -0.27, -0.36, -0.09),
        storyPart(Material.ARMADILLO_SCUTE, -0.58, 0.66, 0.12, 0.70, 0.35, 2.90, 0.11, -0.34, 0.34),
        storyPart(Material.FERMENTED_SPIDER_EYE, 0.18, 0.51, 0.10, 0.90, 0.65, 4.10, -0.18, 0.28, 0.16),
        storyPart(Material.FERMENTED_SPIDER_EYE, 0.75, 0.03, 0.11, 0.73, 0.85, 3.90, -1.75, 0.47, 0.29),
        storyPart(Material.FERMENTED_SPIDER_EYE, 0.30, -0.53, 0.13, 0.90, 0.57, 3.90, -2.67, 0.26, -0.22),
        storyPart(Material.ARMADILLO_SCUTE, 0.78, -0.41, 0.19, 0.46, 0.26, 2.0, 0.58, 0.38, 0.16),
        storyPart(Material.BLACK_CONCRETE, 0.05, -0.012, 0.112, 1.09, 0.69, 0.16, -0.12),
        storyPart(Material.DRAGON_EGG, -0.86, 0.55, -0.21, 1.20, 0.88, 0.89, 0.35, -0.40, -0.24),
        storyPart(Material.DRAGON_EGG, 0.74, -0.48, -0.19, 1.01, 0.75, 0.85, -0.93, 0.39, 0.23),
        storyPart(Material.GILDED_BLACKSTONE, -0.89, 0.45, -0.065, 0.42, 0.57, 0.51, 0.43, -0.36, 0.21),
        storyPart(Material.GOAT_HORN, -0.79, 0.74, -0.08, 0.52, 0.66, 2.42, -2.73, -0.49, 0.03),
        storyPart(Material.WEEPING_VINES, -0.66, -0.61, -0.04, 0.62, 0.72, 1.07, -0.31, -0.31, 0.07),
        storyPart(Material.WEEPING_VINES, 0.64, -0.61, -0.13, 0.45, 0.60, 1.28, 0.24, 0.27, 0.12),
        storyPart(Material.ARMADILLO_SCUTE, -0.76, 0.28, 0.36, 0.58, 0.28, 1.76, -0.87, -0.35, 0.12),
        storyPart(Material.RESIN_CLUMP, -0.76, 0.61, 0.28, 0.27, 0.10, 0.92, 0.42, -0.38),
        storyPart(Material.RESIN_CLUMP, -0.88, 0.32, 0.37, 0.21, 0.078, 0.96, -0.98, -0.40),
        storyPart(Material.RESIN_CLUMP, 0.79, -0.31, 0.36, 0.24, 0.081, 1.03, 0.73, 0.39),
        storyPart(Material.ARMADILLO_SCUTE, 0.52, 0.42, 0.32, 0.49, 0.23, 1.71, -0.74, 0.31, 0.16),
    )

    private val slots = buildList<(Double) -> AuroraMiniaturePart> {
        for (lobe in body) add { lobe }
        for (n in lips.indices) {
            add { t ->
                val deep = mouth(n + 0.50, 0.48, t)
                val a = p(deep.x, deep.y, deep.z - 0.20)
                val b = mouth(n + 0.50, 1.035, t)
                val skin = when (n) {
                    1, 2, 5, 8, 9 -> Material.CRIMSON_HYPHAE
                    3, 10 -> Material.STRIPPED_CRIMSON_HYPHAE
                    else -> Material.BLACK_TERRACOTTA
                }
                cord(skin, a, b, 0.37, 0.12, 0.038)
            }
            add { t ->
                val skin = when (n) {
                    1, 4, 10 -> Material.NETHERRACK
                    3, 7, 8, 11 -> Material.STRIPPED_CRIMSON_HYPHAE
                    else -> Material.NETHER_WART_BLOCK
                }
                val width = when (n) { 1, 2, 8 -> 0.205; 5, 6 -> 0.139; else -> 0.167 }
                cord(skin, mouth(n.toDouble(), 1.0, t), mouth(n + 1.0, 1.0, t), width, width * 1.19, width * 0.47)
            }
        }
        for (tooth in teeth) {
            if (tooth.hooked) add { t -> cord(Material.BONE_BLOCK, toothPoint(tooth, 0.0, t), toothPoint(tooth, 0.54, t),
                tooth.width * 0.88, tooth.width * 0.78, 0.025) }
            add { t -> fang(tooth, t) }
        }
        for ((armIndex, arm) in arms.withIndex()) {
            for (j in 0 until arm.path.lastIndex) add { t ->
                val u = j.toDouble() / arm.path.lastIndex
                val width = arm.rootWidth * (1 - 0.57 * u) * (1 + 0.17 * bite(t, 0.04 + 0.17 * u))
                cord(arm.skin, armPoint(arm, j, t), armPoint(arm, j + 1, t), width, width * 0.92, width * 0.53)
            }
            for (j in listOf(1, arm.path.lastIndex - 2)) add { t ->
                val bulge = armPoint(arm, j, t)
                val width = arm.rootWidth * (1.33 - 0.62 * j / arm.path.lastIndex)
                storyPart(if ((armIndex + j) % 2 == 0) Material.FERMENTED_SPIDER_EYE else Material.ARMADILLO_SCUTE,
                    bulge.x, bulge.y + 0.022, bulge.z + width * 0.39, width * 1.95, width * 1.41,
                    width * 6.2, arm.phase + j * 0.61, yaw = 0.16 * sin(arm.phase))
            }
            add { t ->
                val eye = armPoint(arm, arm.path.lastIndex, t)
                storyPart(Material.FERMENTED_SPIDER_EYE, eye.x, eye.y, eye.z, arm.eye * 1.97, arm.eye * 1.82,
                    arm.eye * 12.05, arm.phase, yaw = 0.18 * sin(arm.phase))
            }
            add { t ->
                val eye = armPoint(arm, arm.path.lastIndex, t)
                val pearl = when (armIndex) { 1 -> Material.EGG; 4 -> Material.CLAY_BALL; else -> Material.SNOWBALL }
                storyPart(pearl, eye.x - 0.09 * arm.eye, eye.y + 0.05 * arm.eye, eye.z + arm.eye * 0.36,
                    arm.eye * 1.49, arm.eye * (if (armIndex % 2 == 0) 0.90 else 1.25), arm.eye * 9.5, -0.15 * arm.phase)
            }
            if (armIndex != 4) add { t ->
                val eye = armPoint(arm, arm.path.lastIndex, t)
                storyPart(Material.ARMADILLO_SCUTE, eye.x, eye.y + arm.eye * 0.31, eye.z + arm.eye * 0.57,
                    arm.eye * 1.58, arm.eye * 0.40, arm.eye * 5.2, -0.19 * arm.phase)
            }
        }
    }

    override fun partCount(shape: AuroraShape) = slots.size

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val piece = slots[index](t)
        val rocking = sin(TURN * t)
        val facing = Quaternionf().rotationZ((-0.069 + 0.042 * rocking).toFloat())
            .rotateY((-0.19 + 0.18 * sin(TURN * t + 0.23)).toFloat()).rotateX((0.09 + 0.056 * cos(TURN * t)).toFloat())
        val p = piece.center
        val point = facing.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
        return AuroraMiniaturePart(piece.material, AuroraOffset(point.x + 0.046 * rocking,
            point.y + 0.044 * cos(TURN * t), point.z + 0.046 * bite(t)), facing.mul(piece.rotation), Vector3f(piece.scale))
    }
}
