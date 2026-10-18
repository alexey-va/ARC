package ru.arc.onboarding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

internal data class OnboardingGuideReward(val coins: Long, val tokens: Long) {
    init { require(coins >= 0 && tokens >= 0) }
}

/** Cached domain projection only. Service owns eligibility, expiry and reward amounts. */
internal data class OnboardingGuideView(
    val quest: OnboardingQuestView,
    val remainingTicks: Long,
    val reward: OnboardingGuideReward? = null,
)

/** At most five short rows; an empty result immediately restores the ordinary sidebar. */
internal fun onboardingGuideLines(view: OnboardingGuideView): List<Component> {
    val quest = view.quest
    val goal = quest.goal
    if (!quest.accepted || quest.completed || goal == null || view.remainingTicks <= 0) return emptyList()
    val (step, heading, instruction) = when (goal) {
        OnboardingQuestGoal.TRAVEL -> Triple(1, "Выберите мир", "Обычный или новые биомы")
        OnboardingQuestGoal.HOME -> if (OnboardingMilestone.HOME_CREATED in quest.milestones)
            Triple(2, "Перенесите точку дома", "Внутрь своего привата")
        else Triple(2, "Сохраните точку дома", "На месте будущей базы")
        OnboardingQuestGoal.CLAIM -> Triple(3, "Защитите участок", "Поставьте блок привата")
        OnboardingQuestGoal.BUILD -> when {
            OnboardingMilestone.AUTOBUILD_STARTED in quest.milestones -> Triple(4, "Дом строится", "Дождитесь постройки")
            OnboardingMilestone.BUILD_BOOK_OPENED in quest.milestones -> Triple(4, "Начните постройку", "Выберите место в книге")
            else -> Triple(4, "Постройте дом", "Книга в стартовом наборе")
        }
    }
    val reward = view.reward?.takeIf { it.coins > 0 || it.tokens > 0 }
    return listOf(
        text("Первый дом", PURPLE).append(text(" · $step / 4", BODY)),
        row(heading, GREEN),
        row(instruction),
        if (reward == null) row("Подсказки: ${remainingTime(view.remainingTicks)}") else rewardRow(reward),
        row("Журнал: ").append(text("/arc onboarding", CYAN)),
    )
}

private fun rewardRow(reward: OnboardingGuideReward): Component {
    val parts = buildList {
        if (reward.coins > 0) add(text("${reward.coins} ", GOLD).append(text("💰", WHITE)))
        if (reward.tokens > 0) add(text("${reward.tokens} ", PURPLE).append(text("", WHITE)))
    }
    return row("За шаг: ").append(parts.reduce { first, second -> first.append(text(" + ", BODY)).append(second) })
}

private fun remainingTime(ticks: Long): String {
    val minutes = (ticks.coerceAtMost(OnboardingPlaytime.LIMIT_TICKS) + 1199) / 1200
    return if (minutes >= 60) "${minutes / 60}ч ${minutes % 60}м" else "${minutes}м"
}

private fun text(value: String, color: TextColor = BODY) = Component.text(value, color).decoration(TextDecoration.ITALIC, false)
private fun row(value: String, color: TextColor = BODY) = text("| ", GOLD).append(text(value, color))
private val BODY = TextColor.color(0xE8DFD2)
private val WHITE = TextColor.color(0xFFFFFF)
private val GREEN = TextColor.color(0x77EE94)
private val CYAN = TextColor.color(0x6DE4F2)
private val PURPLE = TextColor.color(0xC4ABFF)
private val GOLD = TextColor.color(0xF4D87A)
