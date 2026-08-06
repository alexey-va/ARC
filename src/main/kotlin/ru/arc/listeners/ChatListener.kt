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
        applyChatMode(event)
        val message = TextUtils.plain(event.message())
        val player = event.player
        sync {
            npcMessageHandler(message, player)
        }
    }

    private fun applyChatMode(event: AsyncChatEvent) {
        val mode = modeProvider(event.player.uniqueId)
        val message = event.message()
        val hadPrefix = TextUtils.plain(message).startsWith("!")
        if (mode != ChatMode.GLOBAL || hadPrefix) {
            debug(
                "[ChatMode] backend player={} mode={} had-prefix={} prefix-added=false",
                event.player.uniqueId,
                mode,
                hadPrefix,
            )
            return
        }
        event.message(Component.text("!").append(message))
        debug(
            "[ChatMode] backend player={} mode={} had-prefix=false prefix-added=true",
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
