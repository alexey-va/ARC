package ru.arc.onboarding

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

/** One main-thread instruction bar per participating player; no task or inventory mutations. */
internal class OnboardingHud(
    private val config: OnboardingConfig,
    private val hologram: QuestHologramHud = QuestHologramHud(QuestGuidanceHudService.Source.FIRST_HOME),
) : AutoCloseable {
    private val bars = mutableMapOf<UUID, Pair<Player, BossBar>>()

    fun refresh(player: Player, guide: OnboardingGuideView?) {
        if (!player.isOnline || player.isDead || guide == null || guide.remainingTicks <= 0 || guide.quest.goal == null) {
            clear(player.uniqueId)
            return
        }
        val goal = guide.quest.goal
        val needsBook = goal == OnboardingQuestGoal.BUILD &&
            OnboardingMilestone.AUTOBUILD_STARTED !in guide.quest.milestones
        val worldHint = config.questWorldHint(player.world.name)
        val items = if (worldHint == null && (goal == OnboardingQuestGoal.CLAIM || needsBook))
            player.inventory.contents.filterNotNull() else emptyList()
        val title = onboardingHudTitle(guide,
            hasClaimBlock = goal == OnboardingQuestGoal.CLAIM && items.any { ClaimBlockIdentity.matches(it) && ClaimBlockIdentity.usableBy(it, player) },
            hasBuildBook = needsBook && items.any(::isOnboardingBuildBook),
            worldHint = worldHint,
        ) ?: return
        hologram.refresh(player, title)
        if (bars[player.uniqueId]?.first?.let { it !== player } == true) clear(player.uniqueId)
        val bar = bars.getOrPut(player.uniqueId) {
            val created = BossBar.bossBar(title, 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
            player.showBossBar(created)
            player to created
        }.second
        bar.name(title)
    }

    fun clear(playerId: UUID) {
        hologram.clear(playerId)
        clearBar(playerId)
    }

    private fun clearBar(playerId: UUID) {
        bars.remove(playerId)?.let { (player, bar) -> player.hideBossBar(bar) }
    }

    override fun close() {
        hologram.close()
        bars.keys.toList().forEach(::clearBar)
    }
}

/** Read only the durable item identity; ArcBuilder still validates and owns every book action. */
internal fun isOnboardingBuildBook(item: ItemStack): Boolean {
    if (item.type != Material.BOOK) return false
    val data = item.itemMeta?.persistentDataContainer ?: return false
    return data.get(BUILD_BOOK_ID, PersistentDataType.STRING)?.isNotBlank() == true &&
        data.get(BUILD_BOOK_SCHEMA, PersistentDataType.INTEGER)?.let { it in 1..6 } == true
}

internal fun onboardingHudTitle(
    guide: OnboardingGuideView,
    hasClaimBlock: Boolean = false,
    hasBuildBook: Boolean = false,
    worldHint: Component? = null,
): Component? {
    if (guide.remainingTicks <= 0) return null
    val goal = guide.quest.goal ?: return null
    val building = goal == OnboardingQuestGoal.BUILD && OnboardingMilestone.AUTOBUILD_STARTED in guide.quest.milestones
    if (worldHint != null && goal != OnboardingQuestGoal.TRAVEL && !building) return worldHint
    val (action, explanation) = when (goal) {
        OnboardingQuestGoal.TRAVEL -> "Выберите мир" to " у Проводника"
        OnboardingQuestGoal.HOME -> "/sethome" to if (OnboardingMilestone.HOME_CREATED in guide.quest.milestones)
            " — перенести точку в свой приват" else " — сохранить точку дома"
        OnboardingQuestGoal.CLAIM -> if (hasClaimBlock)
            "ПКМ блоком привата по земле" to " — защитить участок"
        else "/kit start" to " — получить блок привата"
        OnboardingQuestGoal.BUILD -> when {
            OnboardingMilestone.AUTOBUILD_STARTED in guide.quest.milestones -> "Дом строится" to " — дождитесь завершения"
            hasBuildBook -> "ПКМ книгой строительства" to " по земле"
            else -> "/kit start" to " — получить книгу дома"
        }
    }
    return Component.text(action, TextColor.color(0x77EE94)).decoration(TextDecoration.ITALIC, false)
        .append(Component.text(explanation, TextColor.color(0xE8DFD2)))
}

private val BUILD_BOOK_ID = NamespacedKey("arc", "build_book_id")
private val BUILD_BOOK_SCHEMA = NamespacedKey("arc", "build_book_schema")
