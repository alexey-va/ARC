package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.classes.ClassResourceType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

internal data class DungeonCombatResource(
    val type: ClassResourceType,
    val amount: Long,
    val maximum: Long,
)

/** Main-thread dungeon source for ARC Core's shared native sidebar. */
internal class DungeonScoreboard(
    private val dungeon: EMDungeonQol,
    private val sidebar: ArcSidebarHandle? = null,
    private val enabled: (Player) -> Boolean = { true },
    private val party: (Player) -> DungeonPartyView? = { dungeon.parties.current(it) },
    private val resource: (Player) -> DungeonCombatResource? = ::readDungeonCombatResource,
    private val quest: (Player) -> DungeonQuestInfo? = { player -> readDungeonQuests(player)?.firstOrNull(DungeonQuestInfo::tracked) },
    private val crystals: (Player) -> String? = ::readDungeonCrystals,
) {
    private companion object { const val MAX_LINES = ArcSidebarFrame.MAX_ROWS }
    private val snapshots = ConcurrentHashMap<UUID, Map<String, String>>()
    private val legacy = LegacyComponentSerializer.builder().character('§').hexColors().build()

    internal fun refresh(players: Collection<Player>) {
        val present = mutableSetOf<UUID>()
        for (player in players) {
            if (!enabled(player)) {
                sidebar?.hide(player)
                continue
            }
            val view = dungeon.scoreboardView(player)
            if (view == null) {
                sidebar?.hide(player)
                continue
            }
            present += player.uniqueId
            val visit = view.visit
            val trackedQuest = if (view.participant) quest(player)?.takeIf(DungeonQuestInfo::tracked) else null
            val overview = trackedQuest?.let(::questRows) ?: buildList {
                addAll(wrapLegacyScoreboardText(legacy.serialize(dungeonDisplayName(visit)), 32, 2))
                add(line(if (visit.instanced) "instanced" else "open", if (visit.instanced) "<#aaa49a>Отдельное прохождение" else "<#aaa49a>Открытый данж"))
                add(when {
                    visit.waiting -> line("waiting", "<#f4bd6a>Сбор группы")
                    visit.canResume -> line("ongoing", "<#9bd48d>Прохождение идёт")
                    else -> line("finished", "<#aaa49a>Поход окончен")
                })
                if (!view.participant) add(line("observer", "<#ffb277>Наблюдение <#aaa49a>· <#e8dfd2>вы вне состава"))
            }
            val stats = buildList {
                visit.stats?.level?.takeIf { it > 0 }?.let { add(line("level", "<#f4bd6a>| <#e8dfd2>Уровень: <#f4bd6a><value>", "value" to Component.text(it))) }
                visit.stats?.difficulty?.let { add(line("difficulty", "<#f4bd6a>| <#e8dfd2>Сложность: <#f4bd6a><value>", "value" to dungeonDifficulty(dungeon, it))) }
                visit.stats?.playerCount?.let { add(line("party", "<#f4bd6a>| <#e8dfd2>Участников: <#9bd48d><value>", "value" to Component.text(it))) }
                resource(player)?.let {
                    add(line(
                        "resource",
                        "<#f4bd6a>| <#e8dfd2><resource>: <#92bed8><amount><#aaa49a>/<#92bed8><maximum>",
                        "resource" to Component.text(dungeonResourceName(it.type)),
                        "amount" to Component.text(it.amount),
                        "maximum" to Component.text(it.maximum),
                    ))
                }
                if (view.participant) crystals(player)?.let { add(line("crystals", "<#f4bd6a>| <#e8dfd2>Кристаллы: <#c7a0e8>💎 <value>", "value" to Component.text(it))) }
            }
            val footer = listOf(line(if (view.participant) "menu" else "observer-menu",
                if (view.participant) "<#f4bd6a>Shift + F <#e8dfd2>— меню данжа" else "<#aaa49a>Меню похода — для участников"))
            val fixedRows = joinDungeonScoreboardSections(overview, stats, footer)
            val partyRows = party(player)?.takeIf { it.inParty }?.let { partyView ->
                val room = (MAX_LINES - fixedRows.size - 1).coerceAtLeast(0)
                buildList {
                    if (room == 0) return@buildList
                    add(line("party-heading", "<#f4bd6a>Группа <#aaa49a>· <#e8dfd2><value>", "value" to Component.text(partyView.members.size)))
                    val memberRoom = room - 1
                    val visibleMembers = when {
                        partyView.members.size <= memberRoom -> partyView.members
                        memberRoom <= 1 -> partyView.members.take(memberRoom)
                        else -> partyView.members.take(memberRoom - 1)
                    }
                    visibleMembers.forEach { member ->
                        add(line(
                            if (!member.online) "party-offline" else if (member.leader) "party-leader" else "party-member",
                            when {
                                !member.online -> "<#aaa49a>○ <name> · не в сети"
                                member.leader -> "<#f4bd6a>★ <#e8dfd2><name>"
                                else -> "<#9bd48d>● <#e8dfd2><name>"
                            },
                            "name" to Component.text(member.name),
                        ))
                    }
                    if (memberRoom > 1 && partyView.members.size > visibleMembers.size) {
                        add(line("party-more", "<#aaa49a>… ещё <value>", "value" to Component.text(partyView.members.size - visibleMembers.size)))
                    }
                }
            }.orEmpty()
            val rows = joinDungeonScoreboardSections(overview, stats, partyRows, footer)
            val title = line("title", "<#b22222><bold>Rus<white>Crafting")
            snapshots[player.uniqueId] = buildMap {
                put("active", "true")
                put("title", title)
                rows.forEachIndexed { index, row -> put("line_${index + 1}", row) }
            }
            sidebar?.show(player, ArcSidebarFrame(legacy.deserialize(title), rows.map(legacy::deserialize)))
        }
        (snapshots.keys - present).forEach { sidebar?.hide(it) }
        snapshots.keys.retainAll(present)
    }

    internal fun value(playerId: UUID?, key: String): String =
        playerId?.let { snapshots[it]?.get(key) } ?: if (key == "active") "false" else ""
    internal fun remove(playerId: UUID) { snapshots.remove(playerId); sidebar?.hide(playerId) }
    internal fun clear() { snapshots.clear(); sidebar?.close() }
    private fun line(key: String, fallback: String, vararg values: Pair<String, Component>): String =
        legacy.serialize(dungeon.text("scoreboard.$key", fallback, *values))

    private fun questRows(quest: DungeonQuestInfo): List<String> {
        val rows = mutableListOf(wrapLegacyScoreboardText(line(
            "quest-heading",
            "<#c4a7e7>Задание <#aaa49a>· <#e8dfd2><name>",
            "name" to Component.text(compactDungeonQuestText(quest.name, 22)),
        ), 32, 1).single())
        val goals = quest.lines.mapIndexed { index, text ->
            text to quest.lineStates.getOrElse(index) { DungeonQuestGoalState.ACTIVE }
        }.filterNot { (_, state) -> state == DungeonQuestGoalState.COMPLETE }
            .sortedBy { (_, state) ->
                when (state) {
                    DungeonQuestGoalState.ACTIVE -> 0
                    DungeonQuestGoalState.NEXT -> 1
                    DungeonQuestGoalState.COMPLETE -> 2
                }
            }
        val visible = if (goals.size <= 3) goals else goals.take(2)
        visible.forEach { (text, state) ->
            rows += line(
                when (state) {
                    DungeonQuestGoalState.ACTIVE -> "quest-goal-active"
                    DungeonQuestGoalState.COMPLETE -> "quest-goal-complete"
                    DungeonQuestGoalState.NEXT -> "quest-goal-next"
                },
                when (state) {
                    DungeonQuestGoalState.ACTIVE -> "<#f4bd6a>○ <#e8dfd2><goal>"
                    DungeonQuestGoalState.COMPLETE -> "<#9bd48d>✔ <#e8dfd2><goal>"
                    DungeonQuestGoalState.NEXT -> "<#92bed8>▶ <#e8dfd2><goal>"
                },
                "goal" to Component.text(compactDungeonQuestText(text, 28)),
            )
        }
        if (goals.size > visible.size) rows += line(
            "quest-more",
            "<#aaa49a>… ещё <value>",
            "value" to Component.text(goals.size - visible.size),
        )
        return rows
    }
}

internal fun joinDungeonScoreboardSections(vararg sections: List<String>): List<String> = buildList {
    sections.filter { it.isNotEmpty() }.forEach { section ->
        if (isNotEmpty()) add("")
        addAll(section)
    }
}

private fun readDungeonCombatResource(player: Player): DungeonCombatResource? = runCatching {
    if (!AdvancedCombatModule.isInitialized()) return@runCatching null
    AdvancedCombatModule.resourceSnapshot(player.uniqueId).orElse(null)?.let {
        DungeonCombatResource(it.type(), it.amount().roundToLong(), it.maximum().roundToLong())
    }
}.getOrNull()

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
