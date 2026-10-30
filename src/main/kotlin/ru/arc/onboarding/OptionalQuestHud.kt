package ru.arc.onboarding

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

/** One explicitly tracked story; holograms accompany its ordinary progress bar. */
internal class OptionalQuestHud(
    private val text: (String) -> Component,
    private val hologram: QuestHologramHud = QuestHologramHud(QuestGuidanceHudService.Source.OPTIONAL),
) : AutoCloseable {
    private val bars = mutableMapOf<UUID, Pair<Player, BossBar>>()

    fun refresh(player: Player, view: OptionalQuestView?) {
        val instruction = view?.let(::optionalQuestInstruction)
        if (view == null || !player.isOnline || player.isDead || instruction == null) {
            clear(player.uniqueId)
            return
        }
        val title = text("goal-$instruction").colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
        val hint = text("hint-$instruction").colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
        hologram.refresh(player, title.append(Component.newline()).append(hint))
        if (bars[player.uniqueId]?.first?.let { it !== player } == true) clear(player.uniqueId)
        val bar = bars.getOrPut(player.uniqueId) {
            val created = BossBar.bossBar(title, 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
            player.showBossBar(created)
            player to created
        }.second
        bar.name(title)
        bar.progress((view.steps.size.toFloat() / view.kind.steps.size).coerceIn(0f, 1f))
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

/** At most four short rows, without promising any extra onboarding payout. */
internal fun optionalQuestGuideLines(view: OptionalQuestView, text: (String) -> Component): List<Component> {
    val instruction = optionalQuestInstruction(view) ?: return emptyList()
    fun copy(key: String) = text(key).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    val done = view.steps.count { it.kind == view.kind }
    return listOf(
        copy("title-${view.kind.id}").append(Component.text(" · $done / ${view.kind.steps.size}", BODY)),
        Component.text("★ ", GOLD).append(copy("goal-$instruction")),
    ) + optionalQuestHintLines(copy("hint-$instruction")).map { Component.text("   ", BODY).append(Component.text(it, BODY)) }
}

private val BODY = TextColor.color(0xE8DFD2)
private val GOLD = TextColor.color(0xF4D87A)

private fun optionalQuestHintLines(text: Component): List<String> {
    val words = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        .serialize(text).split(Regex("\\s+")).filter(String::isNotBlank)
    val lines = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val next = if (line.isEmpty()) word else "$line $word"
        if (next.sumOf(ru.arc.paper.menu.DialogTextLayout::glyphWidth) > 225 && line.isNotEmpty()) {
            lines += line
            line = word
        } else line = next
    }
    if (line.isNotEmpty()) lines += line
    return lines.take(2)
}
