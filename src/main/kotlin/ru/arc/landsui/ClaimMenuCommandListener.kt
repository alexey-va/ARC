package ru.arc.landsui

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.entity.Player
import java.util.Locale

internal class ClaimMenuCommandListener(
    private val canOpenClaimMenu: () -> Boolean,
    private val openClaimMenu: (Player) -> Unit,
    private val huskHomesEnabled: () -> Boolean = { Bukkit.getPluginManager().isPluginEnabled(HUSK_HOMES_PLUGIN) },
) : Listener, AutoCloseable {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        when (claimCommandRoute(event.message)) {
            ClaimCommandRoute.OPEN_CLAIMS -> {
                if (event.player.hasPermission(WAND_BYPASS_PERMISSION) || !canOpenClaimMenu()) return
                event.isCancelled = true
                openClaimMenu(event.player)
            }

            ClaimCommandRoute.SET_HOME -> {
                if (!huskHomesEnabled()) return
                event.isCancelled = true
                event.player.performCommand("sethome")
            }

            null -> Unit
        }
    }

    override fun close() {
        HandlerList.unregisterAll(this)
    }

    companion object {
        private const val HUSK_HOMES_PLUGIN = "HuskHomes"
        private const val WAND_BYPASS_PERMISSION = "arc.wand-bypass"
    }
}

internal enum class ClaimCommandRoute {
    OPEN_CLAIMS,
    SET_HOME,
}

internal fun claimCommandRoute(commandLine: String): ClaimCommandRoute? {
    val parts = commandLine.trim().removePrefix("/").split(WHITESPACE).filter(String::isNotEmpty)
    val label = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
    return when {
        label in CLAIM_MENU_ALIASES -> ClaimCommandRoute.OPEN_CLAIMS
        label == "home" && parts.size == 2 && parts[1].equals("set", ignoreCase = true) ->
            ClaimCommandRoute.SET_HOME

        else -> null
    }
}

private val CLAIM_MENU_ALIASES = setOf("/wand", "wand", "rg", "&fswand")
private val WHITESPACE = Regex("\\s+")
