package ru.arc.onboarding

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

/** Optional test surface; only a successful provider response replaces the ordinary bossbar. */
internal class QuestHologramHud(
    private val source: QuestGuidanceHudService.Source,
    private val provider: () -> QuestGuidanceHudService? = { Bukkit.getServicesManager().load(QuestGuidanceHudService::class.java) },
) : AutoCloseable {
    private val owners = mutableMapOf<UUID, QuestGuidanceHudService>()

    fun refresh(player: Player, text: Component): Boolean {
        if (!player.hasPermission(QuestGuidanceHudService.PERMISSION)) {
            clear(player.uniqueId)
            return false
        }
        val current = provider()
        if (owners[player.uniqueId]?.let { it !== current } == true) clear(player.uniqueId)
        if (current == null) {
            clear(player.uniqueId)
            return false
        }
        if (!current.show(player, source, text)) {
            current.clear(player.uniqueId, source)
            owners.remove(player.uniqueId)
            return false
        }
        owners[player.uniqueId] = current
        return true
    }

    fun clear(playerId: UUID) { owners.remove(playerId)?.clear(playerId, source) }
    override fun close() = owners.keys.toList().forEach(::clear)
}
