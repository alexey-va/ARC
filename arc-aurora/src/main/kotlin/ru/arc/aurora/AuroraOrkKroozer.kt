package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object AuroraOrkKroozer : AuroraStoryParts {
    const val PART_COUNT = 53
    private const val FIRST_ENGINE = 37
    private const val FIRST_SMOKE = 50
    private const val HINGE_X = 0.388
    private const val HINGE_Y = -0.168
    private const val TURN = 2.0 * PI

    private data class Engine(val x: Double, val y: Double, val z: Double, val cant: Double, val length: Double,
        val width: Double, val casing: Material, val core: Material, val plume: Material)

    // Each position is the actual mouth of that salvaged engine, in hull coordinates.
    private val engines = listOf(
        Engine(-1.02, -0.024, 0.322, 0.092, 0.53, 0.335, Material.BLAST_FURNACE, Material.SHROOMLIGHT, Material.BLAZE_POWDER),
        Engine(-0.88, 0.267, -0.25, -0.255, 0.435, 0.238, Material.SMOKER, Material.MAGMA_BLOCK, Material.BLAZE_POWDER),
        Engine(-0.825, -0.305, -0.057, 0.245, 0.41, 0.265, Material.EXPOSED_CUT_COPPER, Material.SEA_LANTERN, Material.GLOW_INK_SAC),
    )
    private val coughs = doubleArrayOf(0.389, 0.471, 0.536)

    private val parts = buildList(PART_COUNT) {
        add(storyPart(Material.DEEPSLATE_TILES, -0.20, -0.035, 0.0, 1.19, 0.47, 0.72))
        add(storyPart(Material.RED_TERRACOTTA, -0.48, 0.065, 0.292, 0.56, 0.281, 0.30, -0.15, -0.09))
        add(storyPart(Material.RED_GLAZED_TERRACOTTA, 0.32, 0.068, 0.16, 0.486, 0.30, 0.52, 0.084, -0.16))
        add(storyPart(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, -0.13, -0.275, 0.0, 1.15, 0.185, 0.55, PI))
        add(storyPart(Material.EXPOSED_CUT_COPPER, -0.31, -0.142, -0.33, 0.76, 0.25, 0.32, -0.068, 0.16))
        add(storyPart(Material.RED_CONCRETE, -0.27, 0.201, -0.014, 0.91, 0.197, 0.594, 0.078, -0.145))
        add(storyPart(Material.CHISELED_TUFF, -0.19, 0.312, -0.088, 0.48, 0.248, 0.40, -0.086))
        add(storyPart(Material.RED_NETHER_BRICK_STAIRS, -0.181, 0.442, -0.096, 0.46, 0.139, 0.378, -0.067, 0.14))
        add(storyPart(Material.BLAST_FURNACE, -0.515, 0.335, -0.19, 0.235, 0.385, 0.241, 0.163))
        add(storyPart(Material.ANVIL, -0.554, 0.546, -0.19, 0.264, 0.133, 0.261, PI + 0.163))
        add(storyPart(Material.PISTON, -0.355, 0.452, 0.055, 0.141, 0.367, 0.147, -0.262))
        add(storyPart(Material.RED_CONCRETE, -0.29, -0.235, 0.36, 0.92, 0.116, 0.22, 0.116))

        // Four by three enamel squares on a solid starboard sponson. The ink is its black face.
        val rake = 0.08
        val cell = 0.137
        add(storyPart(Material.BLACK_CONCRETE, -0.17, -0.017, 0.453, 0.578, 0.441, 0.218, rake))
        for (row in 0 until 3) for (column in 0 until 4) {
            if ((row + column) % 2 == 0) {
                val x = (column - 1.5) * cell
                val y = (row - 1) * cell
                add(storyPart(Material.WHITE_CONCRETE, -0.17 + x * cos(rake) - y * sin(rake),
                    -0.017 + x * sin(rake) + y * cos(rake), 0.565, cell, cell, 0.021, rake))
            }
        }

        add(storyPart(Material.RED_CONCRETE, 0.69, 0.18, 0.0, 0.74, 0.18, 0.63, -0.044))
        add(storyPart(Material.RED_NETHER_BRICK_STAIRS, 0.621, 0.269, -0.028, 0.70, 0.143, 0.57, -0.065))
        add(storyPart(Material.CRACKED_DEEPSLATE_BRICKS, 0.662, 0.260, 0.310, 0.311, 0.126, 0.090, -0.23))
        add(storyPart(Material.OCHRE_FROGLIGHT, 0.666, 0.261, 0.364, 0.230, 0.067, 0.031, -0.23))
        add(storyPart(Material.BLACK_CONCRETE, 0.704, 0.253, 0.383, 0.034, 0.049, 0.017, -0.23))
        add(storyPart(Material.CUT_COPPER, 1.00, 0.173, -0.01, 0.115, 0.174, 0.648, -0.095))

        // The solid outline of the vanilla sugar sprite has a pointed five-row cap. Its broad roots sit inside the armour.
        fun fang(x: Double, y: Double, z: Double, width: Double, height: Double, roll: Double) =
            storyPart(Material.SUGAR, x, y, z, width, height, 2.16, roll)
        add(fang(0.559, 0.103, 0.146, 0.155, 0.445, PI + 0.11))
        add(fang(0.749, 0.102, 0.146, 0.151, 0.377, PI - 0.04))
        add(fang(0.939, 0.099, 0.146, 0.180, 0.525, PI - 0.13))

        add(storyPart(Material.RED_TERRACOTTA, 0.76, -0.332, 0.01, 0.94, 0.225, 0.702, 0.012))
        add(storyPart(Material.POLISHED_DEEPSLATE, 0.75, -0.419, 0.015, 0.90, 0.074, 0.75, 0.045))
        add(storyPart(Material.EXPOSED_CUT_COPPER, 1.125, -0.278, -0.015, 0.20, 0.207, 0.63, 0.23))
        add(fang(0.497, -0.20, 0.20, 0.17, 0.38, -0.10))
        add(fang(0.671, -0.21, 0.20, 0.18, 0.50, -0.14))
        add(fang(0.85, -0.20, 0.20, 0.16, 0.43, -0.12))
        add(fang(1.105, -0.154, 0.212, 0.237, 0.84, -0.23))
        add(fang(1.032, -0.167, -0.211, 0.192, 0.70, -0.175))
        add(storyPart(Material.LODESTONE, HINGE_X, HINGE_Y, 0.003, 0.247, 0.246, 0.789, 0.12))

        for (engine in engines) {
            with(engine) {
                add(storyPart(casing, x + length * 0.47 * cos(cant), y + length * 0.47 * sin(cant), z,
                    width, width, length, cant, -PI / 2))
                add(storyPart(core, x, y, z, 0.081, width * 0.76, width * 0.76, cant))
                add(storyPart(plume, x, y, z, width * 1.04, length, width * 4.85, PI / 2 + cant))
            }
        }
        add(storyPart(Material.ANVIL, 0.082, 0.322, -0.28, 0.27, 0.226, 0.29, -0.069))
        add(storyPart(Material.POLISHED_BASALT, 0.336, 0.377, -0.28, 0.535, 0.095, 0.118, 0.156))
        add(storyPart(Material.RED_TERRACOTTA, -0.165, 0.012, -0.458, 0.73, 0.126, 0.39, 0.13, -0.43))
        add(fang(0.858, 0.097, -0.218, 0.173, 0.487, PI - 0.09))
        repeat(coughs.size) { add(storyPart(Material.GUNPOWDER, 0.0, 0.0, 0.0, 0.36, 0.24, 1.14)) }
    }

    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val local = parts[index]
        val smoke = index >= FIRST_SMOKE
        // A soot puff keeps the heading and origin of its own misfire when the ship lunges away.
        val poseTime = if (smoke) minOf(t, coughs[index - FIRST_SMOKE]) else t
        val enter = storyBeat(poseTime, 0.0, 0.154)
        val run = storyBeat(poseTime, 0.575, 0.713)
        val depart = storyBeat(poseTime, 0.822, 1.0)
        val cock = storyBeat(poseTime, 0.407, 0.553) * (1.0 - storyBeat(poseTime, 0.572, 0.632))
        val work = enter * (1.0 - depart)
        val rattle = sin(TURN * (2.73 * poseTime + 0.04)) + 0.30 * sin(TURN * 5.51 * poseTime)
        val heading = -0.52 + 0.36 * enter + 0.144 * work * rattle - 0.19 * cock +
            0.76 * storyBeat(poseTime, 0.605, 0.819) - 0.17 * depart
        val facing = Quaternionf().rotateZ((-0.035 + 0.046 * work * rattle - 0.13 * cock +
            0.16 * run - 0.089 * depart).toFloat()).rotateY(heading.toFloat())
            .rotateX((-0.27 + 0.156 * work * sin(TURN * (1.8 * poseTime + 0.14)) + 0.29 * run).toFloat())
        val size = (0.49 + 0.51 * enter - 0.59 * depart) * storyBeat(poseTime, 0.0, 0.067) *
            (1.0 - storyBeat(poseTime, 0.936, 1.0))
        val rootX = -0.25 + 0.09 * enter + 0.12 * poseTime - 0.106 * cock + 0.59 * run + 0.08 * depart
        val rootY = -0.09 + 0.060 * enter + 0.027 * work * rattle + 0.081 * run + 0.115 * depart
        val rootZ = -0.16 + 0.21 * enter + 0.141 * cock - 0.20 * run - 0.37 * depart
        val scale = Vector3f(local.scale)
        var x = local.center.x
        var y = local.center.y
        var z = local.center.z
        var rotation = local.rotation
        when {
            index in 28..35 -> {
                val angle = -0.245 * cock + 0.067 * run + 0.035 * work * rattle
                val dx = x - HINGE_X
                val dy = y - HINGE_Y
                x = HINGE_X + dx * cos(angle) - dy * sin(angle)
                y = HINGE_Y + dx * sin(angle) + dy * cos(angle)
                rotation = Quaternionf().rotationZ(angle.toFloat()).mul(rotation)
            }
            index in FIRST_ENGINE until FIRST_ENGINE + 9 && (index - FIRST_ENGINE) % 3 == 2 -> {
                val n = (index - FIRST_ENGINE) / 3
                val engine = engines[n]
                val shake = sin(TURN * (3.47 * t + 0.38 * n))
                val stumble = storyBeat(t, coughs[n] - 0.019, coughs[n]) * (1.0 - storyBeat(t, coughs[n], coughs[n] + 0.047))
                val thrust = 0.54 + 0.19 * shake - 0.36 * stumble + 0.94 * run - 0.48 * depart
                scale.y *= thrust.toFloat()
                val setback = engine.length * 0.33 * thrust
                x -= setback * cos(engine.cant)
                y -= setback * sin(engine.cant)
            }
            smoke -> {
                val n = index - FIRST_SMOKE
                val age = ((t - coughs[n]) / 0.148).coerceIn(0.0, 1.0)
                val amount = if (age > 0 && age < 1) sin(PI * age) else 0.0
                val engine = engines[n]
                scale.mul(amount.toFloat())
                x = engine.x - 0.085 - 0.315 * age
                y = engine.y + 0.025 + 0.20 * age
                z = engine.z
            }
        }
        val point = facing.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat())).mul(size.toFloat())
        return AuroraMiniaturePart(local.material, AuroraOffset(rootX + point.x, rootY + point.y, rootZ + point.z),
            facing.mul(rotation), scale.mul(size.toFloat()))
    }
}
