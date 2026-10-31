package ru.arc.onboarding

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.staffspells.api.QuestGuidanceHudService
import ru.arc.util.Logging.error
import java.util.UUID

/** Borrow the same client-mounted owner as quest guidance; retain the native fallback without it. */
internal class ClaimGuideHud(
    private val provider: () -> QuestGuidanceHudService? = {
        Bukkit.getServicesManager().load(QuestGuidanceHudService::class.java)
    },
) : AutoCloseable {
    private val owners = mutableMapOf<UUID, QuestGuidanceHudService>()
    private var failedProvider: QuestGuidanceHudService? = null

    fun refresh(player: Player, text: Component, verticalOffset: Double, menuOpen: Boolean): Boolean {
        val current = provider()
        if (current !== failedProvider) failedProvider = null
        if (owners[player.uniqueId]?.let { it !== current } == true) clear(player.uniqueId)
        if (current == null || current === failedProvider) { clear(player.uniqueId); return false }
        return call(current, player.uniqueId, "refresh") {
            owners[player.uniqueId] = current
            current.setSuppressed(ARC.instance, player.uniqueId, MENU_SURFACE, menuOpen)
            if (menuOpen) {
                current.clear(player.uniqueId, QuestGuidanceHudService.Source.CLAIM)
                true
            } else if (current.showClaim(player, text, verticalOffset)) true
            else { clear(player.uniqueId); false }
        }
    }

    fun hasHologram(playerId: UUID): Boolean = playerId in owners
    fun isTarget(player: Player): Boolean = owners[player.uniqueId]?.let { current ->
        call(current, player.uniqueId, "inspect target") { current.isClaimTarget(player) }
    } == true

    fun clear(playerId: UUID) {
        val current = owners.remove(playerId) ?: return
        call(current, playerId, "clear") {
            current.clear(playerId, QuestGuidanceHudService.Source.CLAIM)
            true
        }
        call(current, playerId, "release suppression") {
            current.setSuppressed(ARC.instance, playerId, MENU_SURFACE, false)
            true
        }
    }

    private inline fun call(current: QuestGuidanceHudService, playerId: UUID, operation: String,
        action: () -> Boolean): Boolean {
        try {
            return action()
        } catch (failure: Exception) {
            disable(current, playerId, operation, failure)
        } catch (failure: LinkageError) {
            disable(current, playerId, operation, failure)
        }
        return false
    }

    private fun disable(current: QuestGuidanceHudService, playerId: UUID, operation: String, failure: Throwable) {
        if (failedProvider === current) return
        failedProvider = current
        owners.filterValues { it === current }.keys.toList().forEach(::clear)
        error("Claim HUD {} failed for {} (provider={}): provider instance disabled; using native fallback. " +
            "Check ARC/ArcDungeons compatibility and replace the failing provider",
            operation, playerId, current.javaClass.name, failure)
    }

    override fun close() {
        owners.keys.toList().forEach(::clear)
        failedProvider = null
    }

    private companion object { const val MENU_SURFACE = "lands-claim-menu" }
}
