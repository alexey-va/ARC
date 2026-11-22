package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraMagicStories : AuroraStoryParts {
    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.TOTEM_HEART -> 24
        AuroraShape.SHATTERED_SWORD -> 10
        AuroraShape.REVERSE_FIREWORK -> 17
        else -> error("Not a magic story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (shape) {
        AuroraShape.TOTEM_HEART -> heart(index, t)
        AuroraShape.SHATTERED_SWORD -> sword(index, t)
        AuroraShape.REVERSE_FIREWORK -> firework(index, t)
        else -> error("Not a magic story: $shape")
    }

    private fun item(material: Material, x: Double, y: Double, z: Double, size: Double,
        roll: Double = 0.0, yaw: Double = 0.0) = storyPart(material, x, y, z, size, size, size, roll, yaw)

    // Rectangles follow the 9 × 9 vanilla HUD heart. The red rows grow from their left edge.
    private data class Row(val x: Int, val y: Int, val width: Int, val height: Int = 1, val half: Int = width)

    private val heartRows = listOf(
        Row(2, 0, 2), Row(5, 0, 2), Row(1, 1, 7), Row(0, 2, 9, 3),
        Row(1, 5, 7), Row(2, 6, 5), Row(3, 7, 3), Row(4, 8, 1),
        Row(5, 1, 2), Row(1, 2, 7, 3), Row(2, 5, 5), Row(3, 6, 3),
        Row(2, 1, 2), Row(5, 1, 2, half = 0), Row(1, 2, 7, 3, 4),
        Row(2, 5, 5, half = 3), Row(3, 6, 3, half = 2), Row(4, 7, 1), Row(2, 2, 1),
    )

    private fun heartbeat(t: Double, hit: Double): Double {
        val windup = storyBeat(t, hit - 0.027, hit)
        val strike = storyBeat(t, hit, hit + 0.016)
        return -0.11 * (windup - strike) + 0.32 * (strike - storyBeat(t, hit + 0.016, hit + 0.075))
    }

    private fun heart(index: Int, t: Double): AuroraMiniaturePart {
        if (index == 19) {
            val rise = storyBeat(t, 0.227, 0.327)
            val size = (1.40 * rise + 0.10 * sin(PI * rise)) * (1 - storyBeat(t, 0.438, 0.512))
            return item(Material.TOTEM_OF_UNDYING, 0.0, -0.32 + 0.40 * rise + 0.025 * sin(22 * t), 0.09,
                size, 0.15 * (1 - rise) + 0.035 * sin(24 * t))
        }
        if (index >= 20) {
            val pair = (index - 20) / 2
            val side = if (index % 2 == 0) -1.0 else 1.0
            val p = ((t - 0.23 - 0.032 * pair) / 0.285).coerceIn(0.0, 1.0)
            val arc = if (p > 0 && p < 1) sin(PI * p) else 0.0
            return item(if (pair == 0) Material.GOLD_NUGGET else Material.EMERALD,
                side * (0.12 + (0.60 + 0.15 * pair) * arc), -0.30 + 0.98 * p, 0.06,
                (0.20 + 0.05 * pair) * arc, side * (0.4 + p))
        }

        val last = storyBeat(t, 0.0, 0.038) * (1 - storyBeat(t, 0.203, 0.275))
        val healed = storyBeat(t, 0.456, 0.547) * (1 - storyBeat(t, 0.911, 1.0))
        val pulse = heartbeat(t, 0.603) + 1.08 * heartbeat(t, 0.758)
        val pixel = 0.154 * (last + healed)
        val dx = pixel * (1 + pulse)
        val dy = pixel * (1 + pulse * 0.89)
        val color = when (index) {
            in 0..7 -> Material.BLACK_CONCRETE
            in 8..11 -> Material.GRAY_CONCRETE
            in 12..17 -> Material.RED_CONCRETE
            else -> Material.PINK_CONCRETE
        }
        val row = heartRows[index]
        val width = if (index >= 12 && t < 0.4) row.half * (1 - storyBeat(t, 0.135, 0.219))
            else row.width.toDouble()
        val present = if (width > 0) 1.0 else 0.0
        // The nested solid slabs expose the painted face on both sides of the heart.
        val depth = when (index) {
            in 0..7 -> 0.70
            in 8..11 -> 0.85
            in 12..17 -> 1.0
            else -> 1.13
        }
        return storyPart(color, (row.x + width / 2 - 4.5) * dx,
            0.035 + (4.5 - row.y - row.height * 0.5) * dy - 0.09 * (1 - last) * (1 - healed),
            0.0, width * dx, row.height * dy * present, depth * pixel * present)
    }

    // Pixel centres run along the actual diamond sword from guard to tip.
    private data class Shard(val px: Double, val py: Double, val length: Double, val width: Double,
        val fromX: Double, val fromY: Double, val bendX: Double, val bendY: Double, val spin: Double)

    private val blade = listOf(
        Shard(7.8, 8.2, 2.88, 2.70, -1.08, -0.05, -0.59, 0.37, -0.92),
        Shard(9.8, 6.2, 2.88, 2.68, -1.06, 0.64, -0.46, 0.77, 1.32),
        Shard(11.8, 4.2, 2.86, 2.61, -0.98, -0.72, -0.26, -0.69, -1.45),
        Shard(13.5, 2.5, 2.28, 2.47, -0.76, 0.90, -0.16, 0.83, 0.86),
        Shard(14.8, 1.2, 1.95, 1.75, -0.72, -0.82, 0.01, -0.45, -1.13),
    )

    private fun sword(index: Int, t: Double): AuroraMiniaturePart {
        val travel = (t / 0.20).coerceIn(0.0, 1.0)
        val arrive = 1 - (1 - travel) * (1 - travel)
        val coast = storyBeat(t, 0.16, 0.60)
        val cock = storyBeat(t, 0.735, 0.828)
        val rise = ((t - 0.828) / 0.159).coerceIn(0.0, 1.0)
        val lift = rise * rise * (1.58 - 0.58 * rise)
        val roll = -0.56 + 0.55 * arrive + 0.63 * cock
        val x = -0.70 + 0.70 * arrive + 0.13 * coast - 0.045 * cock + 0.20 * lift
        val y = -0.21 + 0.20 * arrive + 0.16 * sin(PI * arrive) - 0.04 * coast - 0.12 * cock + 1.05 * lift
        val pixel = (0.086 + 0.0115 * arrive - 0.067 * lift) * storyBeat(t, 0.0, 0.032)
        val cs = cos(roll)
        val sn = sin(roll)
        fun worldX(px: Double, py: Double, spacing: Double = pixel) = x + ((px - 6.5) * cs - (9.5 - py) * sn) * spacing
        fun worldY(px: Double, py: Double, spacing: Double = pixel) = y + ((px - 6.5) * sn + (9.5 - py) * cs) * spacing

        if (index == 9) {
            // NONE mirrors generated items on X. Restore the source diagonal to meet the assembled strips.
            val remaining = ((0.649 - t) / 0.051).coerceIn(0.0, 1.0)
            val size = 16 * pixel * (1 - remaining * remaining) * (1 - storyBeat(t, 0.926, 0.980))
            return item(Material.DIAMOND_SWORD, worldX(8.0, 8.0), worldY(8.0, 8.0), 0.11, size, roll, PI)
        }
        // Hold the assembled silhouette while the textured sword grows, then shrink each fragment in place.
        val proxy = 1 - storyBeat(t, 0.634, 0.649)
        val unit = pixel * proxy
        val spacing = if (proxy > 0.0) pixel else 0.0
        if (index < 4) {
            fun hilt(material: Material, px: Double, py: Double, length: Double, width: Double, angle: Double,
                z: Double = 0.0) = storyPart(material, worldX(px, py, spacing), worldY(px, py, spacing), z,
                length * unit, width * unit, 1.32 * unit, roll + angle)
            return when (index) {
                0 -> hilt(Material.SPRUCE_PLANKS, 3.6, 12.4, 6.08, 1.64, PI / 4)
                1 -> hilt(Material.DARK_PRISMARINE, 1.45, 14.55, 2.55, 2.37, PI / 4, 0.009)
                2 -> hilt(Material.DARK_PRISMARINE, 5.95, 9.90, 10.0, 1.83, -PI / 4, 0.012)
                else -> hilt(Material.OXIDIZED_COPPER, 5.95, 9.70, 7.53, 0.78, -PI / 4, 0.031)
            }
        }
        val n = index - 4
        val shard = blade[n]
        val start = 0.027 + 0.033 * n
        val q = storyBeat(t, start, 0.278 + 0.080 * n).let { it * (0.22 + 0.78 * it) }
        fun chase(startAt: Double, bend: Double, end: Double) =
            (1 - q) * (1 - q) * startAt + 2 * (1 - q) * q * bend + q * q * end
        val size = unit * (0.73 + 0.27 * q) * storyBeat(t, start, start + 0.034)
        return storyPart(Material.DIAMOND_BLOCK,
            chase(shard.fromX, shard.bendX, worldX(shard.px, shard.py, spacing)),
            chase(shard.fromY, shard.bendY, worldY(shard.px, shard.py, spacing)), 0.025 + 0.10 * sin(PI * q),
            shard.length * size, shard.width * size, 1.10 * size,
            roll + PI / 4 + shard.spin * (1 - q), 0.32 * sin(PI * q))
    }

    private data class Ray(val x: Double, val y: Double, val bow: Double, val material: Material,
        val pixelX: Int, val pixelY: Int)

    private val rays = listOf(
        Ray(-1.08, 0.14, 0.16, Material.DIAMOND_BLOCK, 7, 8), Ray(-0.51, 0.31, 0.10, Material.LIGHT_BLUE_CONCRETE, 7, 9),
        Ray(-0.61, 0.68, 0.15, Material.AMETHYST_BLOCK, 7, 7), Ray(-0.12, 0.77, 0.07, Material.PINK_CONCRETE, 8, 3),
        Ray(0.15, 0.34, 0.07, Material.REDSTONE_BLOCK, 9, 4), Ray(0.60, 0.58, 0.14, Material.GOLD_BLOCK, 8, 5),
        Ray(0.48, 0.22, 0.09, Material.EMERALD_BLOCK, 9, 6), Ray(1.09, 0.06, 0.21, Material.LIGHT_BLUE_CONCRETE, 9, 7),
        Ray(0.93, -0.29, 0.21, Material.PURPLE_CONCRETE, 9, 9), Ray(0.42, -0.29, 0.10, Material.MAGENTA_CONCRETE, 8, 10),
        Ray(0.36, -0.68, 0.16, Material.GOLD_BLOCK, 9, 12), Ray(0.01, -0.48, 0.11, Material.REDSTONE_BLOCK, 8, 13),
        Ray(-0.24, -0.31, 0.09, Material.PINK_CONCRETE, 10, 14), Ray(-0.68, -0.56, 0.18, Material.YELLOW_CONCRETE, 13, 12),
        Ray(-0.97, -0.26, 0.19, Material.REDSTONE_BLOCK, 7, 11), Ray(-0.015, 0.018, 0.01, Material.SEA_LANTERN, 8, 8),
    )

    private fun firework(index: Int, t: Double): AuroraMiniaturePart {
        val fall = ((t - 0.774) / 0.201).coerceIn(0.0, 1.0)
        val drop = fall * fall * (1.40 - 0.40 * fall)
        val x = 0.095 * drop + 0.029 * sin(PI * fall)
        val y = 0.08 - 0.99 * drop
        val fullSize = 1.40
        if (index == 16) {
            val size = fullSize * storyBeat(t, 0.49, 0.631) * (1 - 0.41 * drop) * (1 - storyBeat(t, 0.932, 0.980))
            return item(Material.FIREWORK_ROCKET, x, y, 0.085, size, -0.10 * sin(PI * fall), PI)
        }
        val ray = rays[index]
        val delay = index * 7 % 13 / 190.0
        val q = storyBeat(t, 0.156 + delay, 0.577 + delay)
        val spread = 1 - q
        val swept = sin(PI * spread)
        val fit = q * q * fullSize / 16
        val arrival = 1 - storyBeat(t, 0.532 + delay, 0.594 + delay)
        val size = storyBeat(t, 0.0, 0.025) * arrival
        val core = index == rays.lastIndex
        val width = (if (core) 0.13 else 0.058 + 0.014 * (index % 3)) * size
        val tail = (0.10 + 0.30 * hypot(ray.x, ray.y)) * (0.85 + 0.14 * (index % 3))
        val length = if (core) width else (0.085 + tail * spread) * size
        // The streak follows the tangent of its own bowed path as it returns to a pixel of the rocket.
        val tangent = if (core) PI / 4 else atan2(ray.y + PI * ray.bow * cos(PI * spread), ray.x)
        return storyPart(ray.material, ray.x * spread + (ray.pixelX - 7.5) * fit,
            0.08 + ray.y * spread + ray.bow * swept + (7.5 - ray.pixelY) * fit,
            0.11 + spread * 0.12 * cos(index * 1.8), length, width, width,
            tangent, spread * 0.18 * sin(index * 1.5))
    }
}
