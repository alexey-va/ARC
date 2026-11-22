package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin

internal object AuroraVoidCathedral : AuroraStoryParts {
    const val PART_COUNT = 55
    private const val FIRST_ENGINE = 43
    private const val PLUME_LENGTH = 0.42
    private const val EXHAUST_X = -1.075

    private val parts = buildList(PART_COUNT) {
        // Continuous armour bands tie the battery sponsons and transverse engine block into the keel.
        add(storyPart(Material.DEEPSLATE_TILES, -0.12, -0.16, 0.0, 1.68, 0.35, 0.46))
        add(storyPart(Material.LAPIS_BLOCK, -0.12, -0.11, 0.0, 1.66, 0.17, 0.49))
        add(storyPart(Material.CUT_COPPER, -0.12, -0.013, 0.0, 1.70, 0.030, 0.51))
        add(storyPart(Material.POLISHED_DEEPSLATE, -0.16, 0.032, 0.0, 1.55, 0.085, 0.43))
        add(storyPart(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, -0.15, -0.30, 0.0, 1.37, 0.17, 0.35, PI))
        add(storyPart(Material.POLISHED_BASALT, -0.78, -0.15, 0.0, 0.29, 0.33, 0.60))

        // Each section of the ram narrows in all three axes. Its two gold edges meet at the waterline.
        add(storyPart(Material.QUARTZ_STAIRS, 0.69, -0.091, 0.0, 0.38, 0.42, 0.521))
        add(storyPart(Material.QUARTZ_STAIRS, 0.91, -0.105, 0.0, 0.34, 0.29, 0.38))
        add(storyPart(Material.CHISELED_QUARTZ_BLOCK, 1.071, -0.117, 0.0, 0.22, 0.14, 0.23))
        add(storyPart(Material.GOLD_BLOCK, 0.928, -0.004, 0.0, 0.58, 0.039, 0.26, -0.37))
        add(storyPart(Material.GOLD_BLOCK, 0.928, -0.222, 0.0, 0.58, 0.039, 0.26, 0.36))
        add(storyPart(Material.GOLD_BLOCK, 1.199, -0.115, 0.0, 0.15, 0.053, 0.11))

        for (x in listOf(-0.62, -0.40, -0.18, 0.04)) {
            add(storyPart(Material.DISPENSER, x, -0.126, 0.0, 0.16, 0.146, 0.575))
        }
        // A central breastplate and two stepped pairs of gold feathers stand proud on both broadside faces.
        add(storyPart(Material.GOLD_BLOCK, 0.369, -0.131, 0.0, 0.094, 0.094, 0.548, PI / 4))
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.GOLD_BLOCK, 0.369 + side * 0.10, -0.082, 0.0,
                0.155, 0.043, 0.529, side * 0.31))
            add(storyPart(Material.GOLD_BLOCK, 0.369 + side * 0.077, -0.136, 0.0,
                0.105, 0.027, 0.531, side * 0.28))
        }

        add(storyPart(Material.POLISHED_ANDESITE, -0.185, 0.103, 0.0, 1.04, 0.087, 0.354))
        add(storyPart(Material.CHISELED_DEEPSLATE, -0.185, 0.224, 0.0, 0.99, 0.222, 0.293))
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.DARK_PRISMARINE, -0.185, 0.345, side * 0.089,
                1.06, 0.052, 0.235, pitch = side * 0.52))
        }
        // The lower halves of the diamond crowns intersect the lights to make three pointed lancets.
        for (x in listOf(-0.47, -0.18, 0.11)) {
            add(storyPart(Material.HONEYCOMB_BLOCK, x, 0.209, 0.0, 0.105, 0.114, 0.308))
            add(storyPart(Material.HONEYCOMB_BLOCK, x, 0.263, 0.0, 0.075, 0.075, 0.311, PI / 4))
        }

        fun tower(x: Double, z: Double, foot: Double, height: Double, width: Double, spire: Double, principal: Boolean) {
            val eave = foot + height
            add(storyPart(Material.CHISELED_STONE_BRICKS, x, foot + height / 2, z, width, height, width))
            add(storyPart(Material.POLISHED_DEEPSLATE, x, eave, z, width * 1.22, 0.043, width * 1.22))
            // Only the top 11 of 16 texel rows are opaque. After inversion, seat row 0 on the eave and row 10 in the finial.
            val spriteHeight = spire * 16.0 / 11.0
            add(storyPart(Material.POINTED_DRIPSTONE, x, eave + spriteHeight * 0.49, z,
                width * 2.12, spriteHeight, width * 10.0, PI))
            if (principal) {
                add(storyPart(Material.CLOCK, x, foot + height * 0.59, z + width * 0.535,
                    width * 0.89, width * 0.89, 0.22, yaw = PI))
            } else {
                add(storyPart(Material.LIGHTNING_ROD, x, eave + spire * 0.95, z, 0.087, 0.094, 0.087))
            }
        }
        tower(-0.55, -0.047, 0.335, 0.124, 0.14, 0.245, false)
        tower(-0.185, 0.00, 0.348, 0.245, 0.215, 0.347, true)
        tower(0.201, 0.032, 0.329, 0.135, 0.16, 0.222, false)

        fun engine(y: Double, z: Double) {
            add(storyPart(Material.BLAST_FURNACE, -0.895, y, z, 0.22, 0.22, 0.37, yaw = -PI / 2))
            add(storyPart(Material.SHROOMLIGHT, EXHAUST_X, y, z, 0.074, 0.16, 0.16))
            add(storyPart(Material.BLAZE_POWDER, EXHAUST_X - PLUME_LENGTH * 0.33, y, z,
                0.20, PLUME_LENGTH, 1.27, PI / 2))
        }
        engine(-0.119, -0.224)
        engine(-0.309, 0.0)
        engine(-0.119, 0.224)

        // Forward and aft flying buttresses rise outside the nave and leave the windows open between them.
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.POLISHED_ANDESITE, -0.185 + side * 0.177, 0.326, 0.099,
                0.345, 0.041, 0.18, -side * 0.78))
        }
        add(storyPart(Material.LIGHTNING_ROD, -0.185, 0.943, 0.0, 0.139, 0.131, 0.139))
    }

    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val local = parts[index]
        val arrive = storyBeat(t, 0.0, 0.183)
        val turn = storyBeat(t, 0.61, 0.915)
        val leave = storyBeat(t, 0.855, 1.0)
        val visible = storyBeat(t, 0.0, 0.058) * (1.0 - storyBeat(t, 0.944, 1.0))
        val size = (0.32 + 0.68 * arrive - 0.55 * leave) * visible
        val facing = Quaternionf().rotateZ((0.029 + 0.086 * turn).toFloat())
            .rotateY((-0.74 + 0.61 * arrive + 0.16 * storyBeat(t, 0.24, 0.61) + 1.08 * turn).toFloat())
            .rotateX((-0.18 + 0.075 * arrive + 0.16 * turn).toFloat())
        val center = local.center
        var x = center.x
        val scale = Vector3f(local.scale)
        if (index in FIRST_ENGINE until FIRST_ENGINE + 9 && (index - FIRST_ENGINE) % 3 == 2) {
            val engine = (index - FIRST_ENGINE) / 3
            val thrust = 0.87 + 0.33 * turn + 0.094 * sin(2 * PI * (3.7 * t + 0.31 * engine))
            scale.y *= thrust.toFloat()
            x = EXHAUST_X - PLUME_LENGTH * 0.33 * thrust
        }
        val point = facing.transform(Vector3f(x.toFloat(), center.y.toFloat(), center.z.toFloat())).mul(size.toFloat())
        val route = AuroraOffset(-0.32 + 0.63 * t + 0.30 * turn, -0.215 + 0.126 * arrive + 0.155 * turn,
            -0.27 + 0.26 * arrive - 0.54 * leave)
        return AuroraMiniaturePart(local.material, AuroraOffset(route.x + point.x, route.y + point.y, route.z + point.z),
            facing.mul(local.rotation), scale.mul(size.toFloat()))
    }
}
