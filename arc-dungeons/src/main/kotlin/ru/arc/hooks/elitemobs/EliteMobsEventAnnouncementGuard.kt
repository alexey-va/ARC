package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.CustomEventStartEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** EliteMobs checks only for null before announcing both the start and the later end. */
internal class EliteMobsEventAnnouncementGuard : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCustomEventStart(event: CustomEventStartEvent) {
        val native = event.customEvent
        native.startMessage = visibleMessage(native.startMessage)
        native.endMessage = visibleMessage(native.endMessage)
    }

    private fun visibleMessage(message: String?): String? = message?.takeIf {
        PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(it)).isNotBlank()
    }
}
