package ru.arc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import ru.arc.ARC
import ru.arc.TitleInput
import ru.arc.ai.GPTManager

@Suppress("DEPRECATION")
class ChatListener : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        processTitleInput(event)
        GPTManager.processMessage(event)
    }

    private fun processTitleInput(event: AsyncPlayerChatEvent) {
        if (!event.isAsynchronous || !TitleInput.hasInput(event.player)) return
        event.isCancelled = true
        ARC.instance.server.scheduler.runTask(ARC.instance, Runnable {
            TitleInput.processMessage(event.player, event.message)
        })
    }
}
