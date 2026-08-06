package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import ru.arc.TitleInput
import ru.arc.ai.GPTManager
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatModeService
import ru.arc.core.sync
import ru.arc.util.Logging.debug
import ru.arc.util.TextUtils
import java.util.UUID

class ChatListener internal constructor(
    private val npcMessageHandler: (String, Player) -> Unit,
    private val modeProvider: (UUID) -> ChatMode,
) : Listener {
    internal constructor(npcMessageHandler: (String, Player) -> Unit) :
        this(npcMessageHandler, ChatModeService::getMode)

    constructor() : this({ message, player ->
        GPTManager.processMessage(message, player, appendCancel = true)
    }, ChatModeService::getMode)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncChatEvent) {
        if (processTitleInput(event)) return
        logChatMode(event)
        val message = TextUtils.plain(event.message())
        val player = event.player
        sync {
            npcMessageHandler(message, player)
        }
    }

    private fun logChatMode(event: AsyncChatEvent) {
        val mode = modeProvider(event.player.uniqueId)
        debug(
            "[ChatMode] backend player={} mode={} prefix-owner=proxy",
            event.player.uniqueId,
            mode,
        )
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
