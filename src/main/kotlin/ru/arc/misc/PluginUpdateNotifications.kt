package ru.arc.misc

import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.util.Logging.warn
import java.util.UUID

/** Lifecycle reads prepared config; Netty reads only the immutable recipient snapshot and packet. */
internal class PluginUpdateNotifications(private val events: EventManager) :
    PacketListenerAbstract(PacketListenerPriority.HIGHEST), AutoCloseable {
    @Volatile
    private var mutedPlayers: Set<UUID> = emptySet()

    init { events.registerListener(this) }

    fun reload(players: List<String>) {
        mutedPlayers = players.mapNotNull { player ->
            runCatching { UUID.fromString(player.trim()) }.getOrElse {
                warn("Plugin update notifications: invalid UUID in modules/misc.yml:{}; entry ignored", CONFIG_KEY)
                null
            }
        }.toSet()
    }

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.packetType != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE || event.user.uuid !in mutedPlayers) return
        val packet = WrapperPlayServerSystemChatMessage(event)
        if (!packet.isOverlay && isPluginUpdateNotice(packet.message)) event.isCancelled = true
    }

    override fun close() {
        mutedPlayers = emptySet()
        events.unregisterListener(this)
    }

    companion object {
        const val CONFIG_KEY = "plugin-update-notifications-muted-players"
    }
}

private val plainText = PlainTextComponentSerializer.plainText()
private val decorations = Regex("(?i)§[0-9a-fk-orx]|\\p{Co}")
private val whitespace = Regex("\\s+")
private const val VERSION = "[0-9][0-9A-Za-z._+-]*"
private val auctionUpdate = Regex(
    "Доступна новая версия zAuctionHouse! Текущая: $VERSION[;,] новая: $VERSION|" +
        "There is a new version of zAuctionHouse available ! \\(current: $VERSION \\| latest: $VERSION\\)",
)
private val updateNotices = setOf(
    "(zAuctionHouse Redis) You do not use the latest version of the plugin! " +
        "Thank you for taking the latest version to avoid any risk of problem!",
    "(zAuctionHouse Redis) Download plugin here: https://groupez.dev/resources/210",
) + mapOf("FreeMinecraftModels" to "freeminecraftmodels", "BetterStructures" to "betterstructures")
    .flatMap { (plugin, slug) ->
        val notice = "[$plugin] Your version of $plugin is outdated. " +
            "You can download the latest version from https://nightbreak.io/plugin/$slug/download/"
        listOf(notice, "[$plugin] $notice")
    }

internal fun isPluginUpdateNotice(message: Component): Boolean {
    val text = whitespace.replace(decorations.replace(plainText.serialize(message), ""), " ").trim()
    return text in updateNotices || auctionUpdate.matches(text)
}
