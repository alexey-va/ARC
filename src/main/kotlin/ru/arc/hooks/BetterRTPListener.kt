package ru.arc.hooks

import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPostEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.commands.arc.subcommands.RespawnOnRtpSubCommand
import ru.arc.configs.ConfigManager

class BetterRTPListener : Listener {

    private val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler
    fun onBetterRTPEvent(event: RTP_TeleportPostEvent) {
        val player = event.player
        val ifPresent = RespawnOnRtpSubCommand.playersForRtp.getIfPresent(player.name) ?: return
        RespawnOnRtpSubCommand.playersForRtp.invalidate(player.name)
        player.setRespawnLocation(event.location, true)
        player.sendMessage(config.component("rtp-respawn.set-spawn-message", "<green>Ваша точка возрождения установлена здесь! <gray>Чтобы изменить ее, используйте команду /sethome"))
    }
}
