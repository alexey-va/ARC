package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatCommandDecorateEvent
import io.papermc.paper.event.player.AsyncChatDecorateEvent
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.TitleInput
import ru.arc.ai.GPTManager
import ru.arc.chat.ChatMessageColorVariation
import ru.arc.chat.ChatMessageColorizer
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatModeConfig
import ru.arc.chat.ChatModeService
import ru.arc.core.sync
import ru.arc.util.Logging.debug
import ru.arc.util.TextUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatListener internal constructor(
    private val npcMessageHandler: (String, Player) -> Unit,
    private val modeProvider: (UUID) -> ChatMode,
    private val titleInputProvider: (Player) -> Boolean,
    private val messageColorVariationProvider: () -> ChatMessageColorVariation,
) : Listener {
    private val pendingChannels = ConcurrentHashMap<UUID, ChatMode>()

    internal constructor(npcMessageHandler: (String, Player) -> Unit) :
        this(
            npcMessageHandler,
            ChatModeService::getMode,
            TitleInput::hasInput,
            { ChatMessageColorVariation.DISABLED },
        )

    internal constructor(
        npcMessageHandler: (String, Player) -> Unit,
        modeProvider: (UUID) -> ChatMode,
    ) : this(
        npcMessageHandler,
        modeProvider,
        TitleInput::hasInput,
        { ChatMessageColorVariation.DISABLED },
    )

    internal constructor(
        npcMessageHandler: (String, Player) -> Unit,
        modeProvider: (UUID) -> ChatMode,
        titleInputProvider: (Player) -> Boolean,
    ) : this(
        npcMessageHandler,
        modeProvider,
        titleInputProvider,
        { ChatMessageColorVariation.DISABLED },
    )

    internal constructor(
        npcMessageHandler: (String, Player) -> Unit,
        modeProvider: (UUID) -> ChatMode,
        titleInputProvider: (Player) -> Boolean,
        messageColorVariation: ChatMessageColorVariation,
    ) : this(
        npcMessageHandler,
        modeProvider,
        titleInputProvider,
        { messageColorVariation },
    )

    constructor() : this({ message, player ->
        GPTManager.processMessage(message, player, appendCancel = true)
    }, ChatModeService::getMode, TitleInput::hasInput, productionVariationProvider())

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChatDecorate(event: AsyncChatDecorateEvent) {
        if (event is AsyncChatCommandDecorateEvent) return
        val player = event.player() ?: return
        if (titleInputProvider(player)) return
        val message = event.result()
        val mode = modeProvider(player.uniqueId)
        val hadPrefix = TextUtils.plain(message).startsWith("!")
        pendingChannels[player.uniqueId] =
            if (mode == ChatMode.GLOBAL || hadPrefix) ChatMode.GLOBAL else ChatMode.LOCAL
        applyChatMode(player, message, mode, hadPrefix) { event.result(it) }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncChatEvent) {
        if (processTitleInput(event)) return
        val message = TextUtils.plain(event.message())
        val player = event.player
        sync {
            npcMessageHandler(message, player)
        }
    }

    // ARC soft-depends on CMI, so this HIGHEST listener wraps CMI's renderer after it is installed.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun applyChatPalette(event: AsyncChatEvent) {
        val channel =
            pendingChannels.remove(event.player.uniqueId)
                ?: modeProvider(event.player.uniqueId)
        if (event.isCancelled) return
        val renderer = event.renderer()
        event.renderer { source, sourceDisplayName, message, viewer ->
            ensureChannelPrefix(
                colorSenderName(
                    colorMessageBody(
                        renderer.render(source, sourceDisplayName, message, viewer),
                        source.name,
                        channel,
                    ),
                    source.name,
                    channel,
                ),
                source.name,
                channel,
            )
        }
    }

    private fun applyChatMode(
        player: Player,
        message: Component,
        mode: ChatMode,
        hadPrefix: Boolean,
        setResult: (Component) -> Unit,
    ) {
        if (mode != ChatMode.GLOBAL || hadPrefix) {
            debug(
                "[ChatMode] backend player={} mode={} had-prefix={} prefix-added=false prefix-owner=backend-decoration",
                player.uniqueId,
                mode,
                hadPrefix,
            )
            return
        }

        setResult(Component.text("!").append(message))
        debug(
            "[ChatMode] backend player={} mode={} had-prefix=false prefix-added=true prefix-owner=backend-decoration",
            player.uniqueId,
            mode,
        )
    }

    internal fun colorSenderName(
        component: Component,
        senderName: String,
        channel: ChatMode,
    ): Component =
        component.replaceText(
            TextReplacementConfig.builder()
                .matchLiteral(senderName)
                .once()
                .replacement { _, matched -> matched.color(NAME_COLORS.getValue(channel)) }
                .build(),
        )

    internal fun ensureChannelPrefix(
        component: Component,
        senderName: String,
        channel: ChatMode,
    ): Component {
        val visible = TextUtils.plain(component)
        val senderIndex = visible.indexOf(senderName)
        val prefixArea =
            if (senderIndex >= 0) visible.substring(0, senderIndex) else visible.take(64)
        if (prefixArea.contains(CHAT_PREFIX_MARKERS.getValue(channel))) return component

        val styledComponent =
            if (component.color() == null) component.color(NAME_COLORS.getValue(channel)) else component
        return channelPrefix(channel).append(styledComponent)
    }

    internal fun colorMessageBody(
        component: Component,
        senderName: String,
        channel: ChatMode,
    ): Component {
        val children = component.children()
        if (children.isEmpty()) return component

        // CMI 9.8.9.8 Paper_ChatFormatListener.visual appends the formatted message last.
        val updated = children.toMutableList()
        val baseColor = MESSAGE_COLORS.getValue(channel)
        val speakerColor =
            ChatMessageColorizer.colorFor(
                base = baseColor,
                playerName = senderName,
                variation = messageColorVariationProvider(),
            )
        updated[updated.lastIndex] = replaceDefaultMessageColor(updated.last(), speakerColor)
        return component.children(updated)
    }

    private fun replaceDefaultMessageColor(
        component: Component,
        color: TextColor,
    ): Component {
        val recolored =
            if (component.color() == CMI_DEFAULT_MESSAGE_COLOR) component.color(color) else component
        if (recolored.children().isEmpty()) return recolored
        return recolored.children(recolored.children().map { replaceDefaultMessageColor(it, color) })
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

    private companion object {
        fun productionVariationProvider(): () -> ChatMessageColorVariation {
            val config = ChatModeConfig.load(ARC.instance.dataPath)
            return { config.messageColorVariation }
        }

        val NAME_COLORS =
            mapOf(
                ChatMode.LOCAL to TextColor.color(0xD6A85F),
                ChatMode.GLOBAL to TextColor.color(0x72B8E6),
            )
        val MESSAGE_COLORS =
            mapOf(
                ChatMode.LOCAL to TextColor.color(0xE8D7B7),
                ChatMode.GLOBAL to TextColor.color(0xCFE7FF),
            )
        val CHAT_GLYPHS =
            mapOf(
                ChatMode.LOCAL to "",
                ChatMode.GLOBAL to "",
            )
        val CHAT_PREFIX_MARKERS = CHAT_GLYPHS.mapValues { (_, glyph) -> "$glyph | " }
        val CMI_DEFAULT_MESSAGE_COLOR: TextColor = MESSAGE_COLORS.getValue(ChatMode.LOCAL)

        fun channelPrefix(channel: ChatMode): Component =
            Component.text()
                .append(Component.text(CHAT_GLYPHS.getValue(channel), TextColor.color(0xFFFFFF)))
                .append(Component.space())
                .append(Component.text("|", TextColor.color(0x555555)))
                .append(Component.space())
                .build()
    }
}
