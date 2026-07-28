package ru.arc.hooks

import io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent
import io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.rtp.RtpRespawnCompletion
import ru.arc.rtp.RtpProvider

class LeafRTPListener : Listener {
    @EventHandler
    fun onLeafRtp(event: PostTeleportEvent) {
        val player = (event.doTeleport.player() as? BukkitRTPPlayer)?.player() ?: return
        RtpRespawnCompletion.complete(player, RtpProvider.LEAFRTP) { player.location }
    }
}
