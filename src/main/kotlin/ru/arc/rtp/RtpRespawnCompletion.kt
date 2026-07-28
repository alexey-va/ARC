package ru.arc.rtp

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.util.Logging.error

/**
 * Completes an ARC-requested RTP on the Bukkit thread.
 *
 * LeafRTP may publish its completion event asynchronously, so Bukkit player
 * state must not be read before this action reaches the main thread.
 */
object RtpRespawnCompletion {
    fun complete(
        player: Player,
        provider: RtpProvider,
        location: () -> Location,
    ) {
        val action =
            Runnable {
                val request = RtpRespawnTracker.take(player.name, provider) ?: return@Runnable
                if (request.persistPlayerId != null && request.persistWorldName != null) {
                    runCatching {
                        RtpPlayerRegistry.markTeleported(
                            request.persistPlayerId,
                            request.persistWorldName,
                        )
                    }.onFailure { failure ->
                        error("Could not persist completed first RTP for {}", player.name, failure)
                    }
                }
                if (!request.setRespawn || !player.isOnline) return@Runnable

                player.setRespawnLocation(location(), true)
                val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
                player.sendMessage(
                    config.component(
                        "rtp-respawn.set-spawn-message",
                        "<green>Ваша точка возрождения установлена здесь! " +
                            "<gray>Чтобы изменить ее, используйте команду /sethome",
                    ),
                )
            }

        if (Bukkit.isPrimaryThread()) {
            action.run()
        } else {
            Tasks.scheduler.runSync(action)
        }
    }
}
