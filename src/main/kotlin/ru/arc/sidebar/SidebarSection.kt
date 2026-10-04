package ru.arc.sidebar

import ru.arc.paper.api.ArcSidebarFrame

/** One layout; each section's visibility is stored in network-wide player metadata. */
internal enum class SidebarSection(val id: String, val group: Int, val priority: Int, val defaultEnabled: Boolean = true) {
    RANK("rank", 0, 80),
    BALANCE("balance", 0, 70),
    RANK_PROGRESS("rank-progress", 0, 40, false),
    LOCATION("location", 1, 60),
    COORDINATES("coordinates", 1, 90, false),
    QUESTS("quests", 2, 100),
    REWARDS("rewards", 2, 10),
    ACTIVITY("activity", 3, 110, false),
    PROFESSION("profession", 4, 30, false),
    SKILLS("skills", 5, 20, false),
    FOOTER("footer", 6, 0);

    val metaKey: String
        get() = if (this == REWARDS) SidebarQuestRewards.META_KEY else "arc-scoreboard-$id"

    fun enabled(value: String?): Boolean = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> defaultEnabled
    }
}

internal fun sidebarEnabled(hasPermission: (String) -> Boolean): Boolean =
    (1..20).any { hasPermission(if (it == 1) "tab.scoreboard" else "tab.scoreboard$it") }

/** Keep complete sections within the protocol budget; trim auxiliary sections first. */
internal fun <T> composeSidebarSections(
    enabled: (SidebarSection) -> Boolean,
    lines: (SidebarSection) -> List<T>,
    separator: T,
    isBlank: (T) -> Boolean,
): List<T> {
    val sections = SidebarSection.entries.filter(enabled).associateWith { section ->
        lines(section).dropWhile(isBlank).dropLastWhile(isBlank)
    }.filterValues(List<T>::isNotEmpty).toMutableMap()
    fun join(): List<T> {
        if (SidebarSection.QUESTS !in sections) sections.remove(SidebarSection.REWARDS)
        return sections.entries.groupBy { it.key.group }.values.fold(emptyList()) { rows, group ->
            rows + (if (rows.isEmpty()) emptyList() else listOf(separator)) + group.flatMap { it.value }
        }
    }
    var rows = join()
    while (rows.size > ArcSidebarFrame.MAX_ROWS) {
        sections.remove(sections.keys.minBy(SidebarSection::priority))
        rows = join()
    }
    return rows
}
