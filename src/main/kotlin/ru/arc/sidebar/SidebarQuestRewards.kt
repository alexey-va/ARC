package ru.arc.sidebar

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

internal data class SidebarQuestRewardRow(
    val content: Component,
    val reward: Component? = null,
)

/**
 * Gives every reward row the same rendered right edge, based on the visible
 * scoreboard title, rows and quest/reward pairs for this player.
 */
internal fun alignSidebarQuestRewards(
    title: Component,
    rows: List<SidebarQuestRewardRow>,
): List<Component> {
    if (rows.none { it.reward != null }) return rows.map(SidebarQuestRewardRow::content)

    val titleWidth = sidebarTextWidth(title) ?: return appendUnmeasuredRewards(rows)
    val contentWidths = rows.map { sidebarTextWidth(it.content) }
    val rewardWidths = rows.map { row -> row.reward?.let(::sidebarTextWidth) }
    if (contentWidths.any { it == null } || rewardWidths.withIndex().any { (index, width) ->
            rows[index].reward != null && width == null
        }) return appendUnmeasuredRewards(rows)

    val measuredContentWidths = contentWidths.filterNotNull()
    val frameWidth = maxOf(
        titleWidth,
        measuredContentWidths.maxOrNull() ?: 0,
        rows.indices.mapNotNull { index ->
            val rewardWidth = rewardWidths[index] ?: return@mapNotNull null
            contentWidths[index]!! + MINIMUM_REWARD_GAP + rewardWidth
        }.maxOrNull() ?: 0,
    )

    if (frameWidth > MAX_LAYOUT_WIDTH - 8) return appendUnmeasuredRewards(rows)

    return rows.mapIndexed { index, row ->
        val reward = row.reward ?: return@mapIndexed row.content
        val contentWidth = contentWidths[index]!!
        val rewardWidth = rewardWidths[index]!!
        Component.empty().append(row.content)
            .append(DialogTextLayout.spacing.padding(frameWidth - contentWidth - rewardWidth))
            .append(reward)
    }
}

/** Pixel measurement is delegated to ARC Core's active-pack layout engine. */
internal fun sidebarTextWidth(component: Component): Int? {
    val layout = DialogTextLayout.layout(component, TextAlignment.LEFT, MAX_LAYOUT_WIDTH)
        as? TextLayoutResult.Aligned ?: return null
    if (layout.lineCount != 1) return null

    val finalText = layout.component.children().lastOrNull() as? TextComponent
    val trailingPadding = finalText?.content()?.let(::spacingWidth) ?: 0
    return layout.width - trailingPadding
}

private fun appendUnmeasuredRewards(rows: List<SidebarQuestRewardRow>): List<Component> = rows.map { row ->
    row.reward?.let { Component.empty().append(row.content).append(Component.space()).append(it) } ?: row.content
}

private fun spacingWidth(text: String): Int? {
    val points = text.codePoints().toArray()
    if (points.any { it !in FIRST_SPACING_CODE_POINT..LAST_SPACING_CODE_POINT }) return null
    return points.sumOf { 1 shl (it - FIRST_SPACING_CODE_POINT) }
}

private const val MINIMUM_REWARD_GAP = 8
private const val MAX_LAYOUT_WIDTH = 1024
private const val FIRST_SPACING_CODE_POINT = 0xF0F01
private const val LAST_SPACING_CODE_POINT = FIRST_SPACING_CODE_POINT + 9

internal object SidebarQuestRewards {
    const val META_KEY = "arc-scoreboard-quest-rewards"
    fun enabled(value: String?): Boolean = !value.equals("false", ignoreCase = true)

    private val boardSlot = Regex("%arcranks_quest_board_([1-3])%")

    fun placeholder(template: String): String? {
        val board = boardSlot.find(template)
        if (board != null) return "%arcranks_quest_board_reward_${board.groupValues[1]}%"
        return if ("%arcranks_quest_compact%" in template) "%arcranks_quest_reward%" else null
    }
}
