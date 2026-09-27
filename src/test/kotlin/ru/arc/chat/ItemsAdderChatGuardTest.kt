package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.chat.ChatMode.LOCAL
import ru.arc.listeners.ChatListener

class ItemsAdderChatGuardTest : FreeSpec({
    val glyph = "\uE123"
    val definitions = listOf(ChatGlyphDefinition("emoji:vip", glyph, "ia.user.image.use.vip", false))
    fun player(vararg permissions: String): Player = mockk(relaxed = true) {
        every { isOp } returns false
        every { isOnline } returns false
        every { hasPermission(any<String>()) } answers { firstArg<String>() in permissions }
    }
    fun event(player: Player, raw: String, rendered: String = raw): AsyncChatEvent {
        var cancelled = false
        return mockk(relaxed = true) {
            every { this@mockk.player } returns player
            every { signedMessage().message() } returns raw
            every { message() } returns Component.text(rendered)
            every { isCancelled } answers { cancelled }
            every { setCancelled(any()) } answers { cancelled = firstArg() }
        }
    }
    fun guard(
        config: ChatModeConfig = TestChatModeConfig(),
        policy: () -> ChatGlyphPolicy?,
    ) = ItemsAdderChatGuard(policy, config) { it() }

    "denied raw glyph cancels before title or NPC processing" {
        val player = player("ia.user.image.chat")
        val event = event(player, "Привет $glyph")
        val guard = guard { ChatGlyphPolicy(definitions) }
        var titleCalls = 0
        var npcCalls = 0
        val listener = ChatListener(
            { _, _ -> npcCalls++ }, { LOCAL }, { titleCalls++; false },
            { ChatMessageColorVariation.DISABLED }, guard::rejectChat,
        )
        listener.onPlayerChat(event)
        event.isCancelled shouldBe true
        titleCalls shouldBe 0
        npcCalls shouldBe 0
    }

    "allowed raw glyph remains unchanged" {
        val event = event(player("ia.user.image.chat", "ia.user.image.use.vip"), glyph)
        guard { ChatGlyphPolicy(definitions) }.rejectChat(event) shouldBe false
        event.isCancelled shouldBe false
        verify(exactly = 0) { event.message(any()) }
    }

    "server generated badge and offset do not affect validation of signed player text" {
        val event = event(player(), "Привет", "\uEFFF Привет")
        guard { ChatGlyphPolicy(definitions) }.rejectChat(event) shouldBe false
        event.isCancelled shouldBe false
    }

    "private message commands cannot carry unauthorized raw or placeholder glyphs" {
        val guard = guard { ChatGlyphPolicy(definitions) }
        listOf("/msg Bob $glyph", "/minecraft:me :vip:", "/cmi msg Bob %img_emoji:vip%", "/r %img_offset_-256%").forEach {
            val event = PlayerCommandPreprocessEvent(player("ia.user.image.command"), it)
            guard.onPlayerCommand(event)
            event.isCancelled shouldBe true
        }
    }

    "commands use their channel permission and allow an authorized glyph" {
        val event = PlayerCommandPreprocessEvent(player("ia.user.image.command", "ia.user.image.use.vip"), "/msg Bob $glyph")
        guard { ChatGlyphPolicy(definitions) }.onPlayerCommand(event)
        event.isCancelled shouldBe false
    }

    "missing or expired catalog fails closed and a refreshed policy resumes chat" {
        var policy: ChatGlyphPolicy? = null
        val guard = guard { policy }
        guard.rejectChat(event(player(), "Привет")) shouldBe true
        policy = ChatGlyphPolicy(definitions)
        guard.rejectChat(event(player(), "Привет")) shouldBe false
        policy = null
        guard.rejectChat(event(player(), glyph)) shouldBe true
    }

    "operator retains native ItemsAdder bypass even before catalog load" {
        val player = player().apply { every { isOp } returns true }
        guard { null }.rejectChat(event(player, glyph)) shouldBe false
    }

    "large sticker guard uses the configured rejection text and exports only active exact IDs" {
        val stickerId = "arc_warhammer:wh_adeptus"
        val stickerUnicode = "\uE6A1"
        val stickerMetrics = mapOf(stickerId to ChatStickerFontMetrics(48, 8))
        val stickerPolicy = ChatGlyphPolicy(
            listOf(ChatGlyphDefinition(stickerId, stickerUnicode, "ia.user.image.use.warhammer", false)),
            stickerMetrics,
        )
        val config = TestChatModeConfig(
            stickerFontImageMetrics = stickerMetrics,
            largeStickerStandaloneMessage = "<red>Отправьте стикер отдельно.",
        )
        val player = player("ia.user.image.chat", "ia.user.image.use.warhammer")
        every { player.isOnline } returns true
        var sentMessage: Component? = null
        every { player.sendMessage(any<Component>()) } answers { sentMessage = firstArg() }
        val guard = guard(config) { stickerPolicy }

        guard.stickerFontImages() shouldBe mapOf(
            stickerId to ChatStickerFontImage(stickerId, stickerUnicode, 48, 8),
        )
        guard.standaloneSticker(player, "! :wh_adeptus:") shouldBe
            ChatStickerFontImage(stickerId, stickerUnicode, 48, 8)
        guard.standaloneSticker(player("ia.user.image.chat"), "! :wh_adeptus:") shouldBe null
        guard.standaloneSticker(player, "caption :wh_adeptus:") shouldBe null
        guard.rejectChat(event(player, "Привет $stickerUnicode")) shouldBe true
        PlainTextComponentSerializer.plainText().serialize(checkNotNull(sentMessage)) shouldBe "Отправьте стикер отдельно."
        guard.rejectChat(event(player, "! :wh_adeptus:")) shouldBe false
    }

    "private CMI routes reject large stickers before dispatch while ordinary chat still passes" {
        val id = "arc_warhammer:wh_emperor"
        val policy = ChatGlyphPolicy(
            listOf(ChatGlyphDefinition(id, glyph, "ia.user.image.use.vip", false)),
            mapOf(id to ChatStickerFontMetrics(48, 8)),
        )
        val player = player("ia.user.image.chat", "ia.user.image.use.vip")
        var publicChat = false
        val guard = ItemsAdderChatGuard(
            { policy }, TestChatModeConfig(),
            stickerChannelAllowed = { _, _ -> publicChat }, runSync = { it() },
        )
        guard.rejectChat(event(player, ":wh_emperor:")) shouldBe true
        guard.rejectChat(event(player, glyph)) shouldBe true
        guard.rejectChat(event(player, "обычный текст")) shouldBe false
        publicChat = true
        guard.rejectChat(event(player, ":wh_emperor:")) shouldBe false
    }
})
