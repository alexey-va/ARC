package ru.arc.sidebar

internal object SidebarQuestRewards {
    const val META_KEY = "arc-scoreboard-quest-rewards"
    const val SUMMARY_PLACEHOLDER = "%arcranks_quest_reward_summary%"

    fun enabled(value: String?): Boolean = !value.equals("false", ignoreCase = true)

    fun visibleLine(template: String, enabled: Boolean): String? =
        template.takeUnless { !enabled && SUMMARY_PLACEHOLDER in it }
}
