package ru.arc.hooks.elitemobs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.math.roundToInt

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

    fun render(yaw: Float, nativeTitle: String): Component {
        val plain = PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(nativeTitle))
        if (plain.length != 63 || plain.any { it !in nativeSymbols }) return status(plain)

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

        // Targets win collisions with cardinals. Never pin out-of-view targets to an edge.
        plain.forEachIndexed { index, symbol ->
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
        if (plain[31] in setOf('↑', '↓', '↕')) {
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
