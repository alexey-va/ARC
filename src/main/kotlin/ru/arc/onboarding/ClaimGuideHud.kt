package ru.arc.onboarding

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

/** Borrow the same client-mounted owner as quest guidance; retain the native fallback without it. */
internal class ClaimGuideHud(
    private val provider: () -> QuestGuidanceHudService? = {
        Bukkit.getServicesManager().load(QuestGuidanceHudService::class.java)
    },
) : AutoCloseable {
    private val owners = mutableMapOf<UUID, QuestGuidanceHudService>()

    fun refresh(player: Player, text: Component, verticalOffset: Double, menuOpen: Boolean): Boolean {
        val current = provider()
        if (owners[player.uniqueId]?.let { it !== current } == true) clear(player.uniqueId)
        if (current == null) { clear(player.uniqueId); return false }
        owners[player.uniqueId] = current
        current.setSuppressed(ARC.instance, player.uniqueId, MENU_SURFACE, menuOpen)
        if (menuOpen) {
            current.clear(player.uniqueId, QuestGuidanceHudService.Source.CLAIM)
            return true
        }
        if (current.showClaim(player, text, verticalOffset)) return true
        clear(player.uniqueId)
        return false
    }

    fun hasHologram(playerId: UUID): Boolean = playerId in owners
    fun isTarget(player: Player): Boolean = owners[player.uniqueId]?.isClaimTarget(player) == true

    fun clear(playerId: UUID) {
        owners.remove(playerId)?.let {
            it.clear(playerId, QuestGuidanceHudService.Source.CLAIM)
            it.setSuppressed(ARC.instance, playerId, MENU_SURFACE, false)
        }
    }

    override fun close() = owners.keys.toList().forEach(::clear)

    private companion object { const val MENU_SURFACE = "lands-claim-menu" }
}
