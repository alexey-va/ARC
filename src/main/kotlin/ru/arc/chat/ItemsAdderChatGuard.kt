package ru.arc.chat

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.core.sync
import ru.arc.util.TextUtil.mm

/** Validates player-authored text before CMI forwards it or adds trusted glyphs. */
internal class ItemsAdderChatGuard(
    private val policy: () -> ChatGlyphPolicy?,
    private val config: ChatModeConfig,
    private val runSync: (() -> Unit) -> Unit = { action -> sync { action() } },
) : Listener, AutoCloseable {
    @Volatile private var closed = false

    fun rejectChat(event: AsyncChatEvent): Boolean {
        if (event.isCancelled) return true
        // The signed source excludes CMI/ItemsAdder-generated offsets, badges and formatting.
        val rejection = rejection(event.player, event.signedMessage().message(), "ia.user.image.chat") ?: return false
        event.isCancelled = true
        notify(event.player, rejection)
        return true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        // Check every player command so /msg aliases, /e and namespaced commands
        // cannot turn command arguments into an unvalidated chat message.
        val rejection = rejection(event.player, event.message, "ia.user.image.command") ?: return
        event.isCancelled = true
        notify(event.player, rejection)
    }

    private fun rejection(player: Player, message: String, channelPermission: String): String? {
        if (player.isOp) return null
        val active = policy() ?: return config.glyphRegistryUnavailableMessage
        return when (active.violation(message, player::hasPermission, channelPermission = channelPermission)?.reason) {
            ChatGlyphViolationReason.UNAUTHORIZED -> config.glyphUnauthorizedMessage
            ChatGlyphViolationReason.TECHNICAL, ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE -> config.glyphTechnicalMessage
            null -> null
        }
    }

    private fun notify(player: Player, message: String) {
        runSync { if (!closed && player.isOnline) player.sendMessage(mm(message)) }
    }

    override fun close() {
        closed = true
    }
}
