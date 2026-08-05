package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.subcommands.ChatSubCommand
import ru.arc.commands.chat.ChatModeAliasCommand
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.listeners.ChatListener
import java.util.UUID

class ChatModeFeatureTest : FreeSpec({
    val plainText = PlainTextComponentSerializer.plainText()

    beforeTest {
        Tasks.install(TestTaskScheduler())
        mockkObject(CommandConfig)
        every { CommandConfig.get(any(), any()) } returns Component.empty()
    }

    afterTest {
        unmockkObject(CommandConfig)
        Tasks.reset()
    }

    "chat listener" - {
        "runs at the earliest Bukkit priority" {
            val handler =
                ChatListener::class.java
                    .getDeclaredMethod("onPlayerChat", AsyncChatEvent::class.java)
                    .getAnnotation(EventHandler::class.java)

            handler.priority shouldBe EventPriority.LOWEST
        }

        "prefixes an unprefixed message in global mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = chatEvent(player, { message }, { message = it })
            ChatModeService.setMode(playerId, ChatMode.GLOBAL)

            ChatListener { _, _ -> }.onPlayerChat(event)

            plainText.serialize(message) shouldBe "!Привет"
        }

        "does not add a second prefix in global mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("!Привет")
            val event = chatEvent(player, { message }, { message = it })
            ChatModeService.setMode(playerId, ChatMode.GLOBAL)

            ChatListener { _, _ -> }.onPlayerChat(event)

            plainText.serialize(message) shouldBe "!Привет"
        }

        "leaves a message unchanged in local mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = chatEvent(player, { message }, { message = it })

            ChatListener { _, _ -> }.onPlayerChat(event)

            plainText.serialize(message) shouldBe "Привет"
        }
    }

    "commands" - {
        "/arc chat global and local switch the remembered mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)

            ChatSubCommand.execute(player, arrayOf("global")) shouldBe true
            ChatModeService.getMode(playerId) shouldBe ChatMode.GLOBAL

            ChatSubCommand.execute(player, arrayOf("local")) shouldBe true
            ChatModeService.getMode(playerId) shouldBe ChatMode.LOCAL
        }

        "/g and /l switch the same remembered mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            val command = mockk<Command>()

            ChatModeAliasCommand.onCommand(player, command, "g", emptyArray()) shouldBe true
            ChatModeService.getMode(playerId) shouldBe ChatMode.GLOBAL

            ChatModeAliasCommand.onCommand(player, command, "l", emptyArray()) shouldBe true
            ChatModeService.getMode(playerId) shouldBe ChatMode.LOCAL
        }
    }
})

private fun player(playerId: UUID): Player =
    mockk(relaxed = true) {
        every { uniqueId } returns playerId
    }

private fun chatEvent(
    player: Player,
    getMessage: () -> Component,
    setMessage: (Component) -> Unit,
): AsyncChatEvent =
    mockk(relaxed = true) {
        every { isAsynchronous } returns true
        every { this@mockk.player } returns player
        every { message() } answers { getMessage() }
        every { message(any()) } answers { setMessage(firstArg()) }
    }
