package ru.arc.treasurechests

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Одноразовая генерация точек для охоты: поверхность, не в жидкости, с зазором между сундуками.
 */
data class HuntLocationGeneratorConfig(
    val horizontalRadius: Double,
    val verticalRange: Int = 48,
    val minSeparation: Double = 10.0,
    val maxAttemptsPerChest: Int = 80,
) {
    init {
        require(horizontalRadius > 0) { "horizontalRadius must be positive" }
        require(verticalRange > 0) { "verticalRange must be positive" }
        require(minSeparation >= 0) { "minSeparation must be non-negative" }
        require(maxAttemptsPerChest > 0) { "maxAttemptsPerChest must be positive" }
    }
}

object HuntLocationGenerator {
    private val forbiddenGround =
        setOf(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.WATER,
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.MAGMA_BLOCK,
            Material.POWDER_SNOW,
        )

    fun generate(
        world: World,
        center: Location,
        count: Int,
        config: HuntLocationGeneratorConfig,
        random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    ): List<Location> {
        require(count > 0) { "count must be positive" }
        require(center.world == world) { "center must be in the same world" }

        val picked = ArrayList<Location>(count)
        val centerY = center.blockY
        val minY = centerY - config.verticalRange
        val maxY = centerY + config.verticalRange

        repeat(count) outer@{
            repeat(config.maxAttemptsPerChest) {
                val candidate = tryPick(world, center, config, minY, maxY, random) ?: return@repeat
                if (picked.none { it.distance(candidate) < config.minSeparation }) {
                    picked.add(candidate)
                    return@outer
                }
            }
        }

        return picked
    }

    private fun tryPick(
        world: World,
        center: Location,
        config: HuntLocationGeneratorConfig,
        minY: Int,
        maxY: Int,
        random: () -> Double,
    ): Location? {
        val angle = random() * Math.PI * 2
        val distance = sqrt(random()) * config.horizontalRadius
        val x = center.x + cos(angle) * distance
        val z = center.z + sin(angle) * distance

        val blockX = x.toInt()
        val blockZ = z.toInt()

        val surfaceY =
            findSurfaceY(world, blockX, blockZ, center.blockY, minY, maxY) ?: return null

        val ground = world.getBlockAt(blockX, surfaceY, blockZ)
        if (!isValidGround(ground.type)) return null

        val chestY = surfaceY + 1
        val chestBlock = world.getBlockAt(blockX, chestY, blockZ)
        if (!canPlaceChest(chestBlock.type)) return null
        if (!hasHeadroom(world, blockX, chestY, blockZ)) return null

        val candidate =
            Location(
                world,
                blockX + 0.5,
                chestY.toDouble(),
                blockZ + 0.5,
            )

        if (candidate.distance(center) > config.horizontalRadius + 1.0) return null
        return candidate
    }

    private fun findSurfaceY(
        world: World,
        blockX: Int,
        blockZ: Int,
        centerY: Int,
        minY: Int,
        maxY: Int,
    ): Int? {
        val highest = world.getHighestBlockYAt(blockX, blockZ)
        if (highest in minY..maxY && isValidGround(world.getBlockAt(blockX, highest, blockZ).type)) {
            return highest
        }

        for (y in centerY downTo minY) {
            if (isValidGround(world.getBlockAt(blockX, y, blockZ).type)) return y
        }
        for (y in (centerY + 1)..maxY) {
            if (isValidGround(world.getBlockAt(blockX, y, blockZ).type)) return y
        }
        return null
    }

    internal fun isValidGround(material: Material): Boolean {
        if (material in forbiddenGround) return false
        if (material.isAir) return false
        if (!material.isSolid) return false
        if (Tag.LEAVES.isTagged(material)) return false
        return true
    }

    internal fun canPlaceChest(material: Material): Boolean =
        material.isAir || material == Material.SHORT_GRASS || material == Material.TALL_GRASS ||
            material == Material.FERN || material == Material.LARGE_FERN || material == Material.SNOW ||
            Tag.REPLACEABLE.isTagged(material)

    private fun hasHeadroom(
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): Boolean {
        for (offset in 1..3) {
            val above = world.getBlockAt(x, y + offset, z).type
            if (!above.isAir && !Tag.REPLACEABLE.isTagged(above)) return false
        }
        return true
    }
}
