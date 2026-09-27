package ru.arc.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.chat.ChatRenderer
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.chat.ChatMessageColorVariation
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatStickerFontImage
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import java.util.UUID

class ChatListenerTest {
    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
    }

    @AfterEach
    fun tearDown() {
        Tasks.reset()
    }

    @Test
    fun `npc chat handling is moved from async event to the main scheduler`() {
        val player =
            mockk<Player> {
                every { uniqueId } returns UUID.randomUUID()
            }
        val event =
            mockk<AsyncChatEvent>(relaxed = true) {
                every { isAsynchronous } returns true
                every { this@mockk.player } returns player
                every { message() } returns Component.text("hello")
            }
        var handledMessage: String? = null
        var handledPlayer: Player? = null
        val listener =
            ChatListener { message, actualPlayer ->
                handledMessage = message
                handledPlayer = actualPlayer
            }

        listener.onPlayerChat(event)

        assertNull(handledMessage)
        scheduler.executeImmediate()
        assertEquals("hello", handledMessage)
        assertEquals(player, handledPlayer)
    }

    @Test
    fun `lays out only the admitted signed sticker after CMI while preserving formatted header and hover`() {
        val playerId = UUID.randomUUID()
        val player =
            mockk<Player>(relaxed = true) {
                every { uniqueId } returns playerId
                every { name } returns "GrocerMC"
            }
        val rawSticker = ":arc:warhammer_one:"
        val signedMessage = mockk<SignedMessage> { every { message() } returns rawSticker }
        val headerHover = HoverEvent.showText(Component.text("chat help"))
        val nameHover = HoverEvent.showText(Component.text("player details"))
        val stickerHover = HoverEvent.showText(Component.text("sticker details"))
        val header = Component.text("CMI header | ").hoverEvent(headerHover)
        val image = ChatStickerFontImage("arc:warhammer_one", "\uE123", height = 48, ascent = 8)
        var renderer: ChatRenderer =
            ChatRenderer { source, _, _, _ ->
                Component.empty().children(
                    listOf(
                        header,
                        Component.text(source.name).hoverEvent(nameHover),
                        Component.space(),
                        Component.text(":arc:warhammer_one:\uE6A2")
                            .color(TextColor.color(0xE8D7B7))
                            .hoverEvent(stickerHover),
                    ),
                )
            }
        val event =
            mockk<AsyncChatEvent>(relaxed = true) {
                every { this@mockk.player } returns player
                every { signedMessage() } returns signedMessage
                every { renderer() } answers { renderer }
                every { renderer(any()) } answers { renderer = firstArg() }
            }
        val listener =
            ChatListener(
                npcMessageHandler = { _, _ -> },
                modeProvider = { ChatMode.LOCAL },
                titleInputProvider = { false },
                messageColorVariationProvider = { ChatMessageColorVariation.DISABLED },
                rejectGlyphs = { false },
                standaloneStickerProvider = { _, message -> image.takeIf { message == rawSticker } },
            )

        listener.applyChatPalette(event)
        val formatted = renderer.render(player, Component.text(player.name), Component.text(rawSticker), player)
        val plain = PlainTextComponentSerializer.plainText().serialize(formatted)

        assertEquals(" | CMI header | GrocerMC \n${image.unicode}\n \n \n \n \n ", plain)
        assertEquals(headerHover, findText(formatted, "CMI header | ")?.hoverEvent())
        assertEquals(nameHover, findText(formatted, "GrocerMC")?.hoverEvent())
        assertEquals(stickerHover, findText(formatted, image.unicode)?.hoverEvent())
        assertEquals(TextColor.color(0xFFFFFF), findText(formatted, image.unicode)?.color())
    }

    private fun findText(component: Component, content: String): TextComponent? {
        if (component is TextComponent && component.content() == content) return component
        component.children().forEach { child -> findText(child, content)?.let { return it } }
        return null
    }
}
