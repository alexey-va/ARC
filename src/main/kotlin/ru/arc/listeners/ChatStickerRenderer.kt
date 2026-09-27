package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.chat.ChatStickerFontImage

/** Lays out only a sticker already resolved from the admitted signed source message. */
internal class ChatStickerRenderer(
    private val chatLineHeight: Int = DEFAULT_CHAT_LINE_HEIGHT,
    private val defaultFontAscent: Int = DEFAULT_FONT_ASCENT,
) {
    init {
        require(chatLineHeight > 0) { "Chat line height must be positive" }
        require(defaultFontAscent >= 0) { "Default font ascent cannot be negative" }
    }

    fun layout(
        rendered: Component,
        sticker: ChatStickerFontImage?,
    ): Component {
        if (sticker == null) return rendered
        val children = rendered.children()
        val body = children.lastOrNull() ?: return rendered
        val leadingRows = leadingRows(sticker)
        val trailingRows = trailingRows(sticker)
        if (alreadyLaidOut(children, sticker, leadingRows, trailingRows)) return rendered

        val updatedChildren = ArrayList<Component>(children.size + leadingRows + trailingRows * 2)
        updatedChildren.addAll(children.dropLast(1))
        updatedChildren += Component.newline()
        repeat(leadingRows) { updatedChildren += Component.newline() }
        // The font image is a pre-coloured bitmap; white avoids multiplying it by CMI's body tint.
        updatedChildren += Component.text(sticker.unicode).style(body.style().color(WHITE))
        repeat(trailingRows) {
            updatedChildren += Component.newline()
            // A visible ordinary space keeps the final reserved chat row through serialization.
            updatedChildren += Component.space()
        }
        return rendered.children(updatedChildren)
    }

    private fun leadingRows(sticker: ChatStickerFontImage): Int =
        ceilRows((sticker.ascent - defaultFontAscent).coerceAtLeast(0))

    private fun trailingRows(sticker: ChatStickerFontImage): Int =
        ceilRows((sticker.height - sticker.ascent - 1).coerceAtLeast(0))

    private fun ceilRows(pixels: Int): Int = (pixels + chatLineHeight - 1) / chatLineHeight

    private fun alreadyLaidOut(
        children: List<Component>,
        sticker: ChatStickerFontImage,
        leadingRows: Int,
        trailingRows: Int,
    ): Boolean {
        val glyphIndex = children.size - trailingRows * 2 - 1
        if (glyphIndex < 0 || PLAIN_TEXT.serialize(children[glyphIndex]) != sticker.unicode) return false
        repeat(trailingRows) { row ->
            val spaceIndex = children.size - row * 2 - 1
            if (PLAIN_TEXT.serialize(children[spaceIndex]) != " ") return false
            if (PLAIN_TEXT.serialize(children[spaceIndex - 1]) != "\n") return false
        }
        val breakCount = leadingRows + 1
        if (glyphIndex < breakCount) return false
        return (1..breakCount).all { offset ->
            PLAIN_TEXT.serialize(children[glyphIndex - offset]) == "\n"
        }
    }

    private companion object {
        const val DEFAULT_CHAT_LINE_HEIGHT = 9
        const val DEFAULT_FONT_ASCENT = 8
        val WHITE: TextColor = TextColor.color(0xFFFFFF)
        val PLAIN_TEXT = PlainTextComponentSerializer.plainText()
    }
}

/** Minimal renderer hook for isolated profiles that do not install ARC's full chat listener. */
internal class ChatStickerLayoutListener(
    private val resolveSticker: (Player, String) -> ChatStickerFontImage?,
) : Listener {
    private val stickerRenderer = ChatStickerRenderer()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun layOutSticker(event: AsyncChatEvent) {
        if (event.isCancelled) return
        val sticker = resolveSticker(event.player, event.signedMessage().message()) ?: return
        val renderer = event.renderer()
        event.renderer { source, sourceDisplayName, message, viewer ->
            stickerRenderer.layout(renderer.render(source, sourceDisplayName, message, viewer), sticker)
        }
    }
}
