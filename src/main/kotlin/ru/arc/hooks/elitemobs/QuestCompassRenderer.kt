package ru.arc.hooks.elitemobs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.math.roundToInt

/** Texture-free, fixed-width compass. Native EliteMobs owns target projection (63 cells, 3° each). */
internal object QuestCompassRenderer {
    const val WIDTH = 49
    private const val CENTER = WIDTH / 2
    private const val DEGREES_PER_CELL = 180.0 / (WIDTH - 1)
    private val accent = TextColor.color(0xff6b6b)
    private val muted = TextColor.color(0x868b96)
    private val target = TextColor.color(0x9bd48d)
    private val gold = TextColor.color(0xffcf70)
    private val uniform: Key = Key.key("minecraft", "uniform")
    private val nativeSymbols = setOf('-', '⦿', '⬯', '☠', '⚔', '↑', '↓', '↕')

    fun render(yaw: Float, nativeTitle: String): Component {
        val plain = PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(nativeTitle))
        if (plain.length != 63 || plain.any { it !in nativeSymbols }) return status(plain)

        val heading = if (yaw.isFinite()) yaw.toDouble() else 0.0
        val glyphs = CharArray(WIDTH) { '·' }
        val colors = Array<TextColor>(WIDTH) { muted }
        // Bukkit yaw: south=0, west=90, north=180, east=270.
        for (bearing in 0 until 360 step 15) {
            val relative = wrap(bearing - heading)
            if (relative !in -90.0..90.0) continue
            val cell = (CENTER + relative / DEGREES_PER_CELL).roundToInt()
            glyphs[cell] = when (bearing) {
                0 -> 'S'
                90 -> 'W'
                180 -> 'N'
                270 -> 'E'
                else -> '│'
            }
            if (bearing % 90 == 0) colors[cell] = accent
        }
        colors[CENTER] = gold
        if (glyphs[CENTER] == '·') glyphs[CENTER] = '│'

        // Targets win collisions with ticks/cardinals. The native centre height cue wins last.
        plain.forEachIndexed { index, symbol ->
            if (symbol == '-') return@forEachIndexed
            val cell = (CENTER + (index - 31) * 3.0 / DEGREES_PER_CELL).roundToInt().coerceIn(0, WIDTH - 1)
            glyphs[cell] = when (symbol) {
                '⦿', '⬯' -> '◇'
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
        return Component.text("[", accent).font(uniform)
            .append(glyphs.indices.fold(Component.empty()) { line, index ->
                line.append(Component.text(glyphs[index], colors[index]))
            }).append(Component.text("]", accent))
    }

    private fun wrap(degrees: Double): Double = ((degrees % 360 + 540) % 360) - 180

    private fun status(text: String): Component {
        val localized = when {
            text.isBlank() -> "Поиск цели…"
            text == "Waiting for the dungeon to start" -> "Ожидание старта данжа"
            text == "[EM] No quest destination found!" -> "Цель пока не найдена"
            text.startsWith("[EM] Go to world ") -> "Цель в другом мире"
            else -> text.replace('\n', ' ').replace('\r', ' ')
        }
        val bounded = if (localized.length > 40) localized.take(39) + "…" else localized
        return Component.text("[ ", accent).append(Component.text(bounded, muted))
            .append(Component.text(" ]", accent))
    }
}
