package ru.arc.hooks

import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_TeleportPostEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.rtp.RtpRespawnCompletion
import ru.arc.rtp.RtpProvider
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.ProductEntryPoint
import ru.arc.metrics.ProductFeature
import ru.arc.metrics.ProductOutcome

class BetterRTPListener : Listener {
    @EventHandler
    fun onBetterRTPEvent(event: RTP_TeleportPostEvent) {
        MetricsModule.recordProductOutcome(event.player, ProductOutcome.RTP_COMPLETE, ProductFeature.RTP, ProductEntryPoint.GAMEPLAY)
        RtpRespawnCompletion.complete(event.player, RtpProvider.BETTERRTP) { event.location }
    }
}
