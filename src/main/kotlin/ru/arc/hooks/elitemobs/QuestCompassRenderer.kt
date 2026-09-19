package ru.arc.hooks.elitemobs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.sqrt
import org.bukkit.Location

internal data class CompassPoi(val bearing: Double, val distance: Double, val kind: DungeonCompassPointKind)

internal fun DungeonCompassPoint.project(origin: Location): CompassPoi? {
    if (origin.world?.uid != worldId) return null
    val dx = x - origin.x
    val dy = y - origin.y
    val dz = z - origin.z
    val distanceSquared = dx * dx + dy * dy + dz * dz
    if (!distanceSquared.isFinite() || distanceSquared > DUNGEON_COMPASS_RADIUS * DUNGEON_COMPASS_RADIUS ||
        dx * dx + dz * dz < 0.000001) return null
    return CompassPoi((Math.toDegrees(atan2(-dx, dz)) + 360) % 360, sqrt(distanceSquared), kind)
}

/** Texture-free compass. Native EliteMobs owns target projection (63 cells, 3° each). */
internal object QuestCompassRenderer {
    const val WIDTH = 73
    private const val CENTER = WIDTH / 2
    private const val DEGREES_PER_CELL = 1.0
    private const val HALF_VIEW = CENTER * DEGREES_PER_CELL
    private val accent = TextColor.color(0xff6b6b)
    private val scale = TextColor.color(0xe8dfd2)
    private val target = TextColor.color(0x9bd48d)
    private val gold = TextColor.color(0xffcf70)
    private val font: Key = Key.key("minecraft", "default")
    private val nativeSymbols = setOf('-', '⦿', '⬯', '☠', '⚔', '↑', '↓', '↕')

    fun render(yaw: Float, nativeTitle: String? = null, points: List<CompassPoi> = emptyList()): Component {
        val plain = PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(nativeTitle ?: "-".repeat(63)))
        val hasNativeProjection = plain.length == 63 && plain.all { it in nativeSymbols }
        if (!hasNativeProjection && points.isEmpty()) return status(plain)

        val heading = if (yaw.isFinite()) yaw.toDouble() else 0.0
        // The reference is an unruled strip: blank space, directions and POIs only.
        // Default-font spaces advance 4 GUI pixels, keeping the strip about 300 px wide.
        val glyphs = CharArray(WIDTH) { ' ' }
        val colors = Array<TextColor>(WIDTH) { scale }
        // Bukkit yaw: south=0, west=90, north=180, east=270.
        for (bearing in 0 until 360 step 90) {
            val relative = wrap(bearing - heading)
            if (relative !in -HALF_VIEW..HALF_VIEW) continue
            val cell = (CENTER + relative / DEGREES_PER_CELL).roundToInt()
            glyphs[cell] = when (bearing) {
                0 -> 'S'
                90 -> 'W'
                180 -> 'N'
                270 -> 'E'
                else -> error("Unsupported cardinal bearing")
            }
            colors[cell] = accent
        }

        val nativeCells = if (hasNativeProjection) plain.mapIndexedNotNull { index, symbol ->
            val relative = (index - 31) * 3.0
            if (symbol != '-' && relative in -HALF_VIEW..HALF_VIEW)
                (CENTER + relative / DEGREES_PER_CELL).roundToInt() else null
        } else emptyList()
        val occupied = mutableListOf<Int>()
        for (point in points.sortedBy { it.distance }) {
            if (occupied.size >= 8) break
            if (!point.bearing.isFinite() || !point.distance.isFinite() || point.distance !in 0.0..DUNGEON_COMPASS_RADIUS) continue
            val relative = wrap(point.bearing - heading)
            if (relative !in -HALF_VIEW..HALF_VIEW) continue
            val cell = (CENTER + relative / DEGREES_PER_CELL).roundToInt()
            // Keep wide glyphs apart without shifting their actual direction.
            if ((nativeCells + occupied).any { kotlin.math.abs(it - cell) < 3 }) continue
            occupied += cell
            glyphs[cell] = when (point.kind) {
                DungeonCompassPointKind.CHEST -> '▣'
                DungeonCompassPointKind.AVAILABLE_QUEST -> '!'
            }
            colors[cell] = if (point.kind == DungeonCompassPointKind.CHEST) gold else target
        }

        // Tracked targets win collisions with cardinals and nearby points.
        if (hasNativeProjection) plain.forEachIndexed { index, symbol ->
            if (symbol == '-') return@forEachIndexed
            val relative = (index - 31) * 3.0
            if (relative !in -HALF_VIEW..HALF_VIEW) return@forEachIndexed
            val cell = (CENTER + relative / DEGREES_PER_CELL).roundToInt()
            glyphs[cell] = when (symbol) {
                '⦿' -> '◇'
                '⬯' -> '○'
                else -> symbol
            }
            colors[cell] = when (symbol) {
                '☠', '⚔' -> accent
                '⬯', '↑', '↓', '↕' -> gold
                else -> target
            }
        }
        if (hasNativeProjection && plain[31] in setOf('↑', '↓', '↕')) {
            glyphs[CENTER] = plain[31]
            colors[CENTER] = gold
        }
        return Component.text("<", accent).font(font)
            .append(glyphs.indices.fold(Component.empty()) { line, index ->
                line.append(Component.text(glyphs[index], colors[index]))
            }).append(Component.text(">", accent))
    }

    private fun wrap(degrees: Double): Double = ((degrees % 360 + 540) % 360) - 180

    private fun status(text: String): Component {
        val localized = when {
            text.isBlank() -> "Поиск цели…"
            text == "Waiting for the dungeon to start" -> "Ожидание старта данжа"
            text == "[EM] No quest destination found!" -> "Цель пока не найдена"
            text.startsWith("[EM] Go to world ") ->
                "Цель в мире: " + text.removePrefix("[EM] Go to world ").removeSuffix("!")
            else -> text.replace('\n', ' ').replace('\r', ' ')
        }
        val bounded = if (localized.length > 40) localized.take(39) + "…" else localized
        return Component.text("< ", accent).append(Component.text(bounded, scale))
            .append(Component.text(" >", accent))
    }
}
