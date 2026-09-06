package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Main-thread rendering; TAB/PAPI threads read immutable strings only. TAB owns the sidebar. */
internal class DungeonScoreboard(private val dungeon: EMDungeonQol, private val crystals: (Player) -> String? = ::readDungeonCrystals) {
    private val snapshots = ConcurrentHashMap<UUID, Map<String, String>>()
    private val legacy = LegacyComponentSerializer.builder().character('§').hexColors().build()

    internal fun refresh(players: Collection<Player>) {
        val present = mutableSetOf<UUID>()
        for (player in players) {
            val view = dungeon.panelView(player) ?: continue
            present += player.uniqueId
            val visit = view.visit
            val rows = buildList {
                add(line("heading", "<#f4bd6a>Поход в данж"))
                add(legacy.serialize(dungeonDisplayName(visit)))
                add("§0 ")
                add(line(if (visit.instanced) "instanced" else "open", if (visit.instanced) "<#aaa49a>Отдельное прохождение" else "<#aaa49a>Открытый данж"))
                add(when {
                    visit.waiting -> line("waiting", "<#f4bd6a>Сбор группы · /начать")
                    visit.canResume -> line("ongoing", "<#9bd48d>Исследование")
                    else -> line("finished", "<#aaa49a>Поход окончен · /данж выйти")
                })
                visit.stats?.level?.takeIf { it > 0 }?.let { add(line("level", "<#f4bd6a>| <#e8dfd2>Уровень: <#f4bd6a><value>", Component.text(it))) }
                visit.stats?.difficulty?.let { add(line("difficulty", "<#f4bd6a>| <#e8dfd2>Сложность: <#f4bd6a><value>", dungeonDifficulty(dungeon, it))) }
                visit.stats?.playerCount?.let { add(line("party", "<#f4bd6a>| <#e8dfd2>Участников: <#9bd48d><value>", Component.text(it))) }
                crystals(player)?.let { add(line("crystals", "<#f4bd6a>| <#e8dfd2>Кристаллы: <#c7a0e8><value>", Component.text(it))) }
                add("§1 ")
                add(line("menu", "<#f4bd6a>/данж <#e8dfd2>— меню данжа"))
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

internal fun dungeonDisplayName(visit: DungeonVisit): Component =
    LegacyComponentSerializer.legacyAmpersand().deserialize((visit.name ?: "Данж").replace('§', '&'))
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)

internal fun dungeonDifficulty(dungeon: EMDungeonQol, value: String): Component = dungeon.text(
    "panel.difficulties.${value.lowercase().replace(Regex("[^a-z0-9_-]"), "")}",
    when (value.lowercase()) { "normal" -> "Обычная"; "hard" -> "Сложная"; "mythic" -> "Мифическая"; else -> value },
)
