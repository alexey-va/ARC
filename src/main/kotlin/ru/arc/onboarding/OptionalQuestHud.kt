package ru.arc.onboarding

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

/** One selected optional activity; the service gives the first-home guide precedence. */
internal class OptionalQuestHud(
    private val text: (String) -> Component,
    private val hologram: QuestHologramHud = QuestHologramHud(QuestGuidanceHudService.Source.OPTIONAL),
) : AutoCloseable {
    private val bars = mutableMapOf<UUID, Pair<Player, BossBar>>()

    fun refresh(player: Player, view: OptionalQuestView?) {
        val instruction = view?.let(::optionalQuestInstruction)
        if (!player.isOnline || player.isDead || instruction == null) {
            clear(player.uniqueId)
            return
        }
        val title = text("goal-$instruction").colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
        val hint = text("hint-$instruction").colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
        if (hologram.refresh(player, title.append(Component.newline()).append(hint))) {
            clearBar(player.uniqueId)
            return
        }
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

/** Three short rows, without promising any extra onboarding payout. */
internal fun optionalQuestGuideLines(view: OptionalQuestView, text: (String) -> Component): List<Component> {
    val instruction = optionalQuestInstruction(view) ?: return emptyList()
    fun copy(key: String) = text(key).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    val done = view.steps.count { it.kind == view.kind }
    return listOf(
        copy("title-${view.kind.id}").append(Component.text(" · $done / ${view.kind.steps.size}", BODY)),
        Component.text("★ ", GOLD).append(copy("goal-$instruction")),
        Component.text("   ", BODY).append(copy("hint-$instruction")),
    )
}

private val BODY = TextColor.color(0xE8DFD2)
private val GOLD = TextColor.color(0xF4D87A)
