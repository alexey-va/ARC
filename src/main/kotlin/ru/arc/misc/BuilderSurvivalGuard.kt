package ru.arc.misc

import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import java.util.UUID

/** Cleans up a builder's transferred creative state without changing other players or backends. */
internal class BuilderSurvivalGuard(
    private val serverName: () -> String? = { ARC.serverName },
    private val isBuilder: (Player) -> Boolean = { player ->
        HookRegistry.luckPermsHook?.getGroups(player)?.contains("builder") == true
    },
    private val currentPlayer: (UUID) -> Player? = { ARC.instance.server.getPlayer(it) },
    private val runLater: (Long, () -> Unit) -> Unit = { ticks, action -> delayed(ticks.ticks) { action() } },
) : AutoCloseable {
    private var closed = false

    fun checkLater(player: Player) {
        if (closed || serverName() != "survival") return
        runLater(40L) {
            if (closed || serverName() != "survival" || !player.isOnline) return@runLater
            if (currentPlayer(player.uniqueId) !== player || !isBuilder(player)) return@runLater
            if (player.gameMode != GameMode.CREATIVE) return@runLater

            player.gameMode = GameMode.SURVIVAL
            player.isFlying = false
            player.allowFlight = false
        }
    }

    override fun close() {
        closed = true
    }
}
