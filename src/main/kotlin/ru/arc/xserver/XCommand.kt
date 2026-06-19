package ru.arc.xserver

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.configs.Config
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class XCommand(
    var command: String? = null,
    var sender: Sender = Sender.CONSOLE,
    var playerName: String? = null,
    var playerUuid: UUID? = null,
    var ticksTimeout: Int = 20 * 5,
    var ticksDelay: Int? = 40,
    var servers: Set<String>? = null
) : XAction() {

    override fun runInternal() {
        info("[XCommand] runInternal — server={}, servers={}, command={}, sender={}, player={}, uuid={}",
            ARC.serverName, servers, command, sender, playerName, playerUuid)
        val miscConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
        if (servers != null && !servers!!.contains(ARC.serverName)) {
            info("[XCommand] Skipping — this server ({}) is not in target list: {}", ARC.serverName, servers)
            return
        }
        val cmd = command?.takeIf { it.isNotEmpty() } ?: run {
            error("[XCommand] Empty or null command in xcommand: {}", this)
            return
        }
        if (sender == Sender.PLAYER && playerName == null && playerUuid == null) {
            error("[XCommand] Sender is PLAYER but no playerName or uuid set: {}", this)
            return
        }
        var resolvedCmd = cmd
        playerName?.let { resolvedCmd = resolvedCmd.replace("%player_name%", it) }
        playerUuid?.let { resolvedCmd = resolvedCmd.replace("%player_uuid%", it.toString()) }
        command = resolvedCmd

        if (sender == Sender.CONSOLE && playerName == null && playerUuid == null) {
            info("[XCommand] Executing as console: {}", resolvedCmd)
            ARC.trySeverCommand(resolvedCmd)
        } else {
            info("[XCommand] Waiting for player {}/{} to run: {}", playerName, playerUuid, resolvedCmd)
            createAwaitingCommand(miscConfig)
        }
    }

    private fun createAwaitingCommand(miscConfig: Config) {
        val ticks = AtomicInteger(-1)
        repeating(period = 1.ticks, delay = 0.ticks) {
            val player = when {
                playerName != null -> Bukkit.getPlayer(playerName!!)
                playerUuid != null -> Bukkit.getPlayer(playerUuid!!)
                else -> null
            }
            if (player != null) {
                val delay = (ticksDelay ?: miscConfig.integer("xaction.command-delay-ticks", 10)).toLong()
                info("[XCommand] Player {} found, scheduling command '{}' in {} ticks as {}", player.name, command, delay, sender)
                Tasks.scheduler.runLater(delay, Runnable {
                    val cmd = command ?: return@Runnable
                    if (player.isOnline && sender == Sender.PLAYER) {
                        info("[XCommand] Player {} performing command: {}", player.name, cmd)
                        player.performCommand(cmd)
                    } else {
                        info("[XCommand] Console executing command (player context: {}): {}", player.name, cmd)
                        ARC.trySeverCommand(cmd)
                    }
                })
                cancel()
            } else {
                if (ticks.incrementAndGet() >= ticksTimeout) {
                    warn("[XCommand] Player not found after {} ticks, giving up: player={}, uuid={}, command={}",
                        ticksTimeout, playerName, playerUuid, command)
                    cancel()
                }
            }
        }
    }

    enum class Sender { PLAYER, CONSOLE }

    companion object {
        @JvmStatic
        fun create(
            command: String?,
            sender: Sender?,
            playerName: String?,
            playerUuid: UUID?,
            ticksTimeout: Int,
            ticksDelay: Int?,
            servers: Set<String>?
        ) = XCommand(
            command = command,
            sender = sender ?: Sender.CONSOLE,
            playerName = playerName,
            playerUuid = playerUuid,
            ticksTimeout = if (ticksTimeout > 0) ticksTimeout else 100,
            ticksDelay = ticksDelay ?: 40,
            servers = servers
        )
    }
}
