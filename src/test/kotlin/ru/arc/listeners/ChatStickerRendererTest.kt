package ru.arc.listeners

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import ru.arc.chat.ChatStickerFontImage

class ChatStickerRendererTest : StringSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val glyph = "\uE123"

    "replaces provider output with a white sticker while preserving the formatted header and hover" {
        val sticker = ChatStickerFontImage("arc:warhammer_one", glyph, height = 48, ascent = 8)
        val prefixHover = HoverEvent.showText(Component.text("channel help"))
        val nameHover = HoverEvent.showText(Component.text("player card"))
        val stickerHover = HoverEvent.showText(Component.text("sticker details"))
        val prefix = Component.text("local | ").hoverEvent(prefixHover)
        val name = Component.text("Player").color(TextColor.color(0xD6A85F)).hoverEvent(nameHover)
        val body = Component.text("$glyph\uE6A2").color(TextColor.color(0xE8D7B7)).hoverEvent(stickerHover)
        val original = Component.empty().children(listOf(prefix, name, Component.space(), body))

        val laidOut = ChatStickerRenderer().layout(original, sticker)

        plain.serialize(laidOut) shouldBe "local | Player \n$glyph\n \n \n \n \n "
        laidOut.children().take(3) shouldBe listOf(prefix, name, Component.space())
        laidOut.children()[4].color() shouldBe TextColor.color(0xFFFFFF)
        laidOut.children()[4].hoverEvent() shouldBe stickerHover
        laidOut.children()[1].hoverEvent() shouldBe nameHover
        laidOut.children()[0].hoverEvent() shouldBe prefixHover
        ChatStickerRenderer().layout(laidOut, sticker) shouldBe laidOut
    }

    "adds leading rows for an ascent taller than the default font" {
        val sticker = ChatStickerFontImage("arc:warhammer_tall", glyph, height = 48, ascent = 24)
        val original = Component.empty().children(listOf(Component.text("formatted header"), Component.text(":arc:warhammer_tall:")))

        val laidOut = ChatStickerRenderer().layout(original, sticker)

        plain.serialize(laidOut) shouldBe "formatted header\n\n\n$glyph\n \n \n "
    }

    "isolated renderer uses the resolved signed alias to remove ItemsAdder provider offsets" {
        val sticker = ChatStickerFontImage("arc:warhammer_one", glyph, height = 48, ascent = 8)
        val player = mockk<Player>(relaxed = true)
        val rawAlias = "! :arc:warhammer_one:"
        val signedMessage = mockk<SignedMessage> { every { message() } returns rawAlias }
        val header = Component.text("CMI header | ")
        var renderer: ChatRenderer =
            ChatRenderer { _, _, _, _ ->
                Component.empty().children(
                    listOf(header, Component.text("$glyph\uE6A2")),
                )
            }
        val event =
            mockk<AsyncChatEvent>(relaxed = true) {
                every { this@mockk.player } returns player
                every { signedMessage() } returns signedMessage
                every { renderer() } answers { renderer }
                every { renderer(any()) } answers { renderer = firstArg() }
            }
        var resolvedSource: String? = null
        val listener =
            ChatStickerLayoutListener { actualPlayer, message ->
                resolvedSource = message
                sticker.takeIf { actualPlayer == player && message == rawAlias }
            }

        listener.layOutSticker(event)
        val formatted = renderer.render(player, Component.text("Player"), Component.text(rawAlias), player)

        resolvedSource shouldBe rawAlias
        plain.serialize(formatted) shouldBe "CMI header | \n$glyph\n \n \n \n \n "
        signedMessage.message() shouldBe rawAlias
        ChatStickerLayoutListener::class.java
            .getDeclaredMethod("layOutSticker", AsyncChatEvent::class.java)
            .getAnnotation(EventHandler::class.java)
            .priority shouldBe EventPriority.HIGHEST
    }

    "does not alter a normal rendered message without a resolved standalone sticker" {
        val original = Component.text("Player: ordinary chat")

        ChatStickerRenderer().layout(original, null) shouldBe original
    }
})
