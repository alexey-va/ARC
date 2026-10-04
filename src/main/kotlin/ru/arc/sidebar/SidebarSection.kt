package ru.arc.sidebar

/** One layout; each section's visibility is stored in network-wide player metadata. */
internal enum class SidebarSection(val id: String, val group: Int) {
    RANK("rank", 0),
    BALANCE("balance", 0),
    LOCATION("location", 1),
    QUESTS("quests", 2),
    REWARDS("rewards", 2),
    FOOTER("footer", 3);

    val metaKey: String
        get() = if (this == REWARDS) SidebarQuestRewards.META_KEY else "arc-scoreboard-$id"

    fun enabled(value: String?): Boolean = !value.equals("false", ignoreCase = true)
}

internal fun sidebarEnabled(hasPermission: (String) -> Boolean): Boolean =
    (1..20).any { hasPermission(if (it == 1) "tab.scoreboard" else "tab.scoreboard$it") }

/** Resolve rows before joining groups so absent quests leave no empty section or separators. */
internal fun composeSidebarSections(
    enabled: (SidebarSection) -> Boolean,
    lines: (SidebarSection) -> List<String>,
    resolve: (String) -> String?,
): List<String> = SidebarSection.entries
    .filter { enabled(it) && (it != SidebarSection.REWARDS || enabled(SidebarSection.QUESTS)) }
    .groupBy(SidebarSection::group)
    .values
    .map { sections -> sections.flatMap { lines(it).mapNotNull(resolve) }.dropWhile(String::isBlank).dropLastWhile(String::isBlank) }
    .filter(List<String>::isNotEmpty)
    .fold(emptyList()) { rows, group -> if (rows.isEmpty()) group else rows + "" + group }
