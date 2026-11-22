package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

internal object AuroraSolarCorsair : AuroraStoryParts {
    private val mastFoot = AuroraOffset(-0.11, -0.025, -0.035)

    /** The local X axis of a spar runs between its joints. */
    private fun spar(material: Material, ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double, height: Double, width: Double): AuroraMiniaturePart {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val run = hypot(dx, dy)
        return storyPart(material, (ax + bx) / 2, (ay + by) / 2, (az + bz) / 2,
            hypot(run, dz) + 0.009, height, width, atan2(dy, dx), atan2(-dz, run))
    }

    private val hull = buildList {
        add(storyPart(Material.DARK_PRISMARINE, -0.235, -0.114, 0.0, 1.04, 0.141, 0.256))
        add(spar(Material.SMOOTH_SANDSTONE, -0.91, -0.111, 0.0, -0.39, -0.084, 0.0, 0.098, 0.181))
        add(spar(Material.BONE_BLOCK, -0.49, -0.057, 0.0, 0.36, -0.086, 0.0, 0.119, 0.204))
        for (side in listOf(-1.0, 1.0)) {
            add(spar(Material.SMOOTH_QUARTZ, -0.90, -0.105, side * 0.031, -0.50, -0.075, side * 0.127, 0.103, 0.100))
            add(spar(Material.BONE_BLOCK, -0.50, -0.075, side * 0.127, -0.09, -0.068, side * 0.154, 0.139, 0.121))
            add(spar(Material.SMOOTH_SANDSTONE, -0.09, -0.068, side * 0.154, 0.28, -0.103, side * 0.116, 0.112, 0.115))

            // The longer, higher tine and the shorter, lower tine keep daylight between the blades broadside.
            val outer = side > 0.0
            val knuckle = if (outer) 1.12 else 0.995
            val tip = if (outer) 1.39 else 1.18
            add(spar(Material.BONE_BLOCK, -0.015, -0.099, side * 0.104, 0.45, -0.089 + side * 0.022, side * 0.195, 0.139, 0.170))
            add(spar(Material.SMOOTH_QUARTZ, 0.44, -0.089 + side * 0.022, side * 0.194,
                0.83, -0.106 + side * 0.058, side * 0.312, 0.095, 0.132))
            add(spar(Material.BONE_BLOCK, 0.82, -0.106 + side * 0.058, side * 0.309,
                knuckle, -0.099 + side * 0.075, side * 0.401, 0.064, 0.093))
            add(spar(Material.SMOOTH_QUARTZ, knuckle - 0.012, -0.099 + side * 0.075, side * 0.399,
                tip, -0.080 + side * 0.095, side * 0.462, 0.028, 0.048))
        }
        // A single swept foil hangs below the belly. A shorter stabiliser curls out on the far quarter.
        add(spar(Material.SMOOTH_SANDSTONE, -0.035, -0.154, 0.013, -0.45, -0.365, 0.066, 0.093, 0.092))
        add(spar(Material.BONE_BLOCK, -0.45, -0.365, 0.066, -0.72, -0.383, 0.101, 0.064, 0.069))
        add(spar(Material.SMOOTH_QUARTZ, -0.72, -0.383, 0.101, -0.885, -0.335, 0.140, 0.029, 0.044))
        add(spar(Material.BONE_BLOCK, -0.36, -0.098, -0.133, -0.695, -0.101, -0.367, 0.071, 0.105))
        add(spar(Material.SMOOTH_QUARTZ, -0.695, -0.101, -0.367, -0.957, -0.072, -0.462, 0.037, 0.062))

        add(storyPart(Material.DARK_PRISMARINE, 0.029, -0.001, 0.036, 0.365, 0.094, 0.209))
        add(storyPart(Material.ENDER_PEARL, 0.04, 0.056, 0.090, 0.267, 0.174, 2.10, -0.06, pitch = -0.40))
        add(storyPart(Material.CUT_COPPER, -0.29, -0.189, 0.007, 0.248, 0.028, 0.138, yaw = -0.18))
        add(storyPart(Material.ENDER_PEARL, -0.29, -0.211, 0.007, 0.198, 0.153, 0.69, yaw = -0.18, pitch = PI / 2))
        add(storyPart(Material.CUT_COPPER, mastFoot.x, mastFoot.y, mastFoot.z, 0.149, 0.103, 0.163))
    }

    /** The clew is aft and the mast is at X=0. The membrane and its battens turn together on the mast. */
    private val sail = buildList {
        val panels = 10
        for (i in 0 until panels) {
            val u = (i + 0.5) / panels
            val foot = 0.68 * (1.0 - u).pow(1.38)
            val height = 0.12 + 1.04 * u.pow(0.78)
            add(storyPart(if (i == panels - 1) Material.RED_CONCRETE else Material.RED_WOOL,
                -0.86 * (1.0 - u), foot + height / 2, 0.070 * sin(PI * u), 0.091, height, 0.049))
        }
        // Both faces of each batten stand proud of the curved membrane, including on the reverse tack.
        for (height in listOf(0.33, 0.66, 0.97)) {
            val material = if (height == 0.66) Material.CUT_COPPER else Material.RED_NETHER_BRICKS
            add(spar(material, -0.824, 0.70, 0.009, -0.438, (0.70 + height) / 2, 0.070, 0.025, 0.108))
            add(spar(material, -0.438, (0.70 + height) / 2, 0.070, -0.014, height, 0.005, 0.025, 0.108))
        }
        // The outer rim joins the ends of the woven panels all the way to the swept tip.
        add(spar(Material.RED_TERRACOTTA, -0.854, 0.806, 0.005, -0.616, 0.938, 0.056, 0.033, 0.054))
        add(spar(Material.RED_TERRACOTTA, -0.616, 0.938, 0.056, -0.292, 1.036, 0.065, 0.033, 0.054))
        add(spar(Material.RED_TERRACOTTA, -0.292, 1.036, 0.065, -0.005, 1.163, 0.003, 0.033, 0.054))
        add(storyPart(Material.SMOOTH_SANDSTONE, -0.003, 0.577, 0.0, 0.037, 1.191, 0.050))
        add(spar(Material.BONE_BLOCK, -0.003, 1.148, 0.0, -0.033, 1.245, 0.001, 0.028, 0.034))
        add(spar(Material.CUT_COPPER, -0.004, 0.020, 0.0, -0.417, 0.244, 0.070, 0.029, 0.043))
        add(spar(Material.CUT_COPPER, -0.417, 0.244, 0.070, -0.825, 0.665, 0.009, 0.029, 0.043))
        add(storyPart(Material.GOLD_BLOCK, -0.811, 0.697, 0.029, 0.079, 0.079, 0.084, PI / 4))
    }

    override fun partCount(shape: AuroraShape) = hull.size + sail.size

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val arrive = storyBeat(t, 0.0, 0.21)
        val tack = storyBeat(t, 0.535, 0.708)
        val right = storyBeat(t, 0.68, 0.865)
        val leave = storyBeat(t, 0.844, 1.0)
        val size = 0.47 + 0.53 * arrive - 0.36 * leave
        val course = Quaternionf().rotateZ((0.038 + 0.10 * sin(PI * t) - 0.16 * tack + 0.12 * right).toFloat())
            .rotateY((-0.69 + 0.48 * arrive + 1.10 * tack).toFloat())
            .rotateX((-0.22 - 0.10 * arrive - 0.78 * tack + 0.81 * right).toFloat())
        val route = AuroraOffset(-0.72 + 1.12 * t + 0.27 * leave, -0.33 + 0.25 * sin(PI * t) - 0.12 * tack,
            -0.45 + 0.72 * arrive + 0.11 * t - 0.60 * tack - 0.22 * leave)

        val isSail = index >= hull.size
        val piece = if (isSail) sail[index - hull.size] else hull[index]
        val point = Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat())
        val rotation = Quaternionf(course)
        if (isSail) {
            val trim = -0.17 + 0.12 * sin(2 * PI * (t - 0.15)) + 0.60 * tack - 0.51 * right
            val hinge = Quaternionf().rotateZ(0.55f).rotateY(trim.toFloat())
            hinge.transform(point).add(mastFoot.x.toFloat(), mastFoot.y.toFloat(), mastFoot.z.toFloat())
            rotation.mul(hinge)
        }
        course.transform(point).mul(size.toFloat())
        return AuroraMiniaturePart(piece.material, AuroraOffset(route.x + point.x, route.y + point.y, route.z + point.z),
            rotation.mul(piece.rotation), Vector3f(piece.scale).mul(size.toFloat()))
    }
}
