package ru.arc.tablist

internal const val TABLIST_SKILLS_META_KEY = "arc-tablist-skills-selection"

/** Personal content above/below the player table; TAB keeps ownership of the table. */
internal enum class TablistSection(val id: String, val priority: Int, val defaultEnabled: Boolean = false) {
    PROFILE("profile", 100, true),
    BALANCE("balance", 30, true),
    LOCATION("location", 60),
    COORDINATES("coordinates", 90),
    RANK_PROGRESS("rank-progress", 50),
    QUESTS("quests", 110),
    ACTIVITY("activity", 120),
    PROFESSION("profession", 70),
    SKILLS("skills", 80),
    ONLINE("online", 10, true),
    TECHNICAL("technical", 0, true);

    val metaKey: String get() = "arc-tablist-$id"

    fun enabled(value: String?): Boolean = when {
        value.equals("true", true) -> true
        value.equals("false", true) -> false
        else -> defaultEnabled
    }
}

internal fun tablistEnabled(hasPermission: (String) -> Boolean): Boolean =
    (1..20).any { hasPermission(if (it == 1) "tab.tablist" else "tab.tablist$it") }

internal data class TablistFrame(val header: String = "", val footer: String = "")

/** Trim complete sections, preserving logo spacing and removing unused separators. */
internal fun composeTablistSections(
    brand: List<String>,
    sections: Map<TablistSection, List<String>>,
    maximumRows: Int,
): TablistFrame {
    val visible = sections.mapValues { (_, rows) -> rows.dropWhile(String::isBlank).dropLastWhile(String::isBlank) }
        .filterValues(List<String>::isNotEmpty).toMutableMap()
    fun compose(): Pair<List<String>, List<String>> {
        val profile = visible[TablistSection.PROFILE].orEmpty()
        val header = brand + profile + if (profile.isEmpty()) emptyList() else listOf("")
        val groups = TablistSection.entries.filter { it != TablistSection.PROFILE }.mapNotNull(visible::get)
        val footer = if (groups.isEmpty()) emptyList() else listOf("") + groups.reduce { a, b -> a + "" + b } + ""
        return header to footer
    }
    var frame = compose()
    while (frame.first.size + frame.second.size > maximumRows.coerceAtLeast(brand.size) && visible.isNotEmpty()) {
        visible.remove(visible.keys.minBy(TablistSection::priority))
        frame = compose()
    }
    return TablistFrame(frame.first.joinToString("\n"), frame.second.joinToString("\n"))
}
