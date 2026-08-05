package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import ru.arc.TitleInput
import ru.arc.ai.GPTManager
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatModeService
import ru.arc.core.sync
import ru.arc.util.TextUtils

class ChatListener internal constructor(
    private val npcMessageHandler: (String, Player) -> Unit,
) : Listener {
    constructor() : this({ message, player ->
        GPTManager.processMessage(message, player, appendCancel = true)
    })

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncChatEvent) {
        if (processTitleInput(event)) return
        applyChatMode(event)
        val message = TextUtils.plain(event.message())
        val player = event.player
        sync {
            npcMessageHandler(message, player)
        }
    }

    private fun applyChatMode(event: AsyncChatEvent) {
        if (ChatModeService.getMode(event.player.uniqueId) != ChatMode.GLOBAL) return
        val message = event.message()
        if (TextUtils.plain(message).startsWith("!")) return
        event.message(Component.text("!").append(message))
    }

    private fun processTitleInput(event: AsyncChatEvent): Boolean {
        if (!event.isAsynchronous || !TitleInput.hasInput(event.player)) return false
        event.isCancelled = true
        val message = TextUtils.plain(event.message())
        sync {
            TitleInput.processMessage(event.player, message)
        }
        return true
    }
}
