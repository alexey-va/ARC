package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Main-thread rendering; TAB/PAPI threads read immutable strings only. TAB owns the sidebar. */
internal class DungeonScoreboard(
    private val dungeon: EMDungeonQol,
    private val enabled: (Player) -> Boolean = { true },
    private val crystals: (Player) -> String? = ::readDungeonCrystals,
) {
    private val snapshots = ConcurrentHashMap<UUID, Map<String, String>>()
    private val legacy = LegacyComponentSerializer.builder().character('§').hexColors().build()

    internal fun refresh(players: Collection<Player>) {
        val present = mutableSetOf<UUID>()
        for (player in players) {
            if (!enabled(player)) continue
            val view = dungeon.panelView(player) ?: continue
            present += player.uniqueId
            val visit = view.visit
            val name = wrapLegacyScoreboardText(legacy.serialize(dungeonDisplayName(visit)), 32, 2)
            val rows = buildList {
                add(line("heading", "<#f4bd6a>Поход в данж"))
                add(name.first())
                add(name.getOrElse(1) { "§0 " })
                add(line(if (visit.instanced) "instanced" else "open", if (visit.instanced) "<#aaa49a>Отдельное прохождение" else "<#aaa49a>Открытый данж"))
                add(when {
                    visit.waiting -> line("waiting", "<#f4bd6a>Сбор группы")
                    visit.canResume -> line("ongoing", "<#9bd48d>Прохождение идёт")
                    else -> line("finished", "<#aaa49a>Поход окончен")
                })
                visit.stats?.level?.takeIf { it > 0 }?.let { add(line("level", "<#f4bd6a>| <#e8dfd2>Уровень: <#f4bd6a><value>", Component.text(it))) }
                visit.stats?.difficulty?.let { add(line("difficulty", "<#f4bd6a>| <#e8dfd2>Сложность: <#f4bd6a><value>", dungeonDifficulty(dungeon, it))) }
                visit.stats?.playerCount?.let { add(line("party", "<#f4bd6a>| <#e8dfd2>Участников: <#9bd48d><value>", Component.text(it))) }
                crystals(player)?.let { add(line("crystals", "<#f4bd6a>| <#e8dfd2>Кристаллы: <#c7a0e8>💎 <value>", Component.text(it))) }
                add("§1 ")
                add(line("menu", "<#f4bd6a>Shift + F <#e8dfd2>— меню данжа"))
            }
            snapshots[player.uniqueId] = buildMap {
                put("active", "true")
                put("title", line("title", "<#b22222><bold>Rus<white>Crafting"))
                rows.forEachIndexed { index, row -> put("line_${index + 1}", row) }
            }
        }
        snapshots.keys.retainAll(present)
    }

    internal fun value(playerId: UUID?, key: String): String =
        playerId?.let { snapshots[it]?.get(key) } ?: if (key == "active") "false" else ""
    internal fun remove(playerId: UUID) { snapshots.remove(playerId) }
    internal fun clear() { snapshots.clear() }
    private fun line(key: String, fallback: String, value: Component = Component.empty()): String =
        legacy.serialize(dungeon.text("scoreboard.$key", fallback, "value" to value))
}

private data class LegacyGlyph(val value: String, val format: String, val whitespace: Boolean)

/** Wraps by visible code points while preserving legacy colours across line boundaries. */
internal fun wrapLegacyScoreboardText(value: String, width: Int, maxLines: Int): List<String> {
    require(width > 0 && maxLines > 0)
    val glyphs = legacyGlyphs(value)
    if (glyphs.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var start = 0
    while (start < glyphs.size && lines.size < maxLines) {
        while (start < glyphs.size && glyphs[start].whitespace) start++
        if (start >= glyphs.size) break
        val limit = (start + width).coerceAtMost(glyphs.size)
        var end = limit
        var next = limit
        if (limit < glyphs.size && lines.size + 1 < maxLines) {
            val boundary = (limit - 1 downTo start).firstOrNull { glyphs[it].whitespace }
            if (boundary != null) {
                end = boundary
                next = boundary + 1
                while (next < glyphs.size && glyphs[next].whitespace) next++
            }
        }
        val truncated = lines.size + 1 == maxLines && limit < glyphs.size
        val selected = glyphs.subList(start, end).toMutableList()
        if (truncated && selected.isNotEmpty()) {
            val last = selected.last()
            selected[selected.lastIndex] = LegacyGlyph("…", last.format, false)
        }
        lines += renderLegacyGlyphs(selected)
        start = next
    }
    return lines.ifEmpty { listOf("") }
}

private fun legacyGlyphs(value: String): List<LegacyGlyph> {
    val glyphs = mutableListOf<LegacyGlyph>()
    var index = 0
    var colour = ""
    val decorations = linkedSetOf<Char>()
    while (index < value.length) {
        if (value[index] == '§' && index + 1 < value.length) {
            val code = value[index + 1].lowercaseChar()
            if (code == '#' && index + 7 < value.length && value.substring(index + 2, index + 8).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                colour = value.substring(index, index + 8)
                decorations.clear()
                index += 8
                continue
            }
            if (code == 'x' && index + 13 < value.length && (0 until 6).all { value[index + 2 + it * 2] == '§' }) {
                colour = value.substring(index, index + 14)
                decorations.clear()
                index += 14
                continue
            }
            when (code) {
                in '0'..'9', in 'a'..'f' -> { colour = "§$code"; decorations.clear() }
                in 'k'..'o' -> decorations += code
                'r' -> { colour = ""; decorations.clear() }
            }
            index += 2
            continue
        }
        val codePoint = value.codePointAt(index)
        val rendered = String(Character.toChars(codePoint))
        glyphs += LegacyGlyph(rendered, colour + decorations.joinToString("") { "§$it" }, Character.isWhitespace(codePoint))
        index += Character.charCount(codePoint)
    }
    return glyphs
}

private fun renderLegacyGlyphs(glyphs: List<LegacyGlyph>): String = buildString {
    var format: String? = null
    glyphs.forEach { glyph ->
        if (glyph.format != format) {
            if (format != null) append('§').append('r')
            append(glyph.format)
            format = glyph.format
        }
        append(glyph.value)
    }
}

internal fun dungeonDisplayName(visit: DungeonVisit): Component =
    LegacyComponentSerializer.legacyAmpersand().deserialize((visit.name ?: "Данж").replace('§', '&'))
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)

internal fun dungeonDifficulty(dungeon: EMDungeonQol, value: String): Component = dungeon.text(
    "panel.difficulties.${value.lowercase().replace(Regex("[^a-z0-9_-]"), "")}",
    when (value.lowercase()) { "normal" -> "Обычная"; "hard" -> "Сложная"; "mythic" -> "Мифическая"; else -> value },
)
