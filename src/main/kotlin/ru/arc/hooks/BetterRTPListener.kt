package ru.arc.hooks

import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPostEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.rtp.RtpRespawnCompletion
import ru.arc.rtp.RtpProvider

class BetterRTPListener : Listener {
    @EventHandler
    fun onBetterRTPEvent(event: RTP_TeleportPostEvent) {
        RtpRespawnCompletion.complete(event.player, RtpProvider.BETTERRTP) { event.location }
    }
}
