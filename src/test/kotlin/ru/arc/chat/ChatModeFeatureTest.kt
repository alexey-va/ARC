package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
import java.util.concurrent.CompletableFuture

class ChatModeFeatureTest : FreeSpec({
    val plainText = PlainTextComponentSerializer.plainText()
    lateinit var scheduler: TestTaskScheduler

    beforeTest {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
        mockkObject(CommandConfig)
        every { CommandConfig.get(any(), any()) } returns Component.empty()
    }

    afterTest {
        unmockkObject(ChatModeService)
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

        "does not restore the proxy prefix after CMI consumed it" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = chatEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }).onPlayerChat(event)

            plainText.serialize(message) shouldBe "Привет"
        }

        "does not add a second prefix in global mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("!Привет")
            val event = chatEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }).onPlayerChat(event)

            plainText.serialize(message) shouldBe "!Привет"
        }

        "leaves a message unchanged in local mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = chatEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.LOCAL }).onPlayerChat(event)

            plainText.serialize(message) shouldBe "Привет"
        }
    }

    "commands" - {
        "reports when global mode is already selected" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            mockkObject(ChatModeService)
            every {
                ChatModeService.selectMode(playerId, ChatMode.GLOBAL)
            } returns CompletableFuture.completedFuture(ChatModeSelection.ALREADY_SELECTED)
            every {
                CommandConfig.get("chat.global-already", any())
            } returns Component.text("already-global")

            ChatSubCommand.selectMode(player, ChatMode.GLOBAL) shouldBe true
            scheduler.executeImmediate()

            verify { player.sendMessage(Component.text("already-global")) }
        }

        "/arc chat global and local report the changed mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            mockkObject(ChatModeService)
            every {
                ChatModeService.selectMode(playerId, any())
            } returns CompletableFuture.completedFuture(ChatModeSelection.CHANGED)
            every { CommandConfig.get("chat.global", any()) } returns Component.text("global")
            every { CommandConfig.get("chat.local", any()) } returns Component.text("local")

            ChatSubCommand.execute(player, arrayOf("global")) shouldBe true
            ChatSubCommand.execute(player, arrayOf("local")) shouldBe true
            scheduler.executeImmediate()

            verify { player.sendMessage(Component.text("global")) }
            verify { player.sendMessage(Component.text("local")) }
        }

        "/g and /l report the same changed modes" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            val command = mockk<Command>()
            mockkObject(ChatModeService)
            every {
                ChatModeService.selectMode(playerId, any())
            } returns CompletableFuture.completedFuture(ChatModeSelection.CHANGED)
            every { CommandConfig.get("chat.global", any()) } returns Component.text("global")
            every { CommandConfig.get("chat.local", any()) } returns Component.text("local")

            ChatModeAliasCommand.onCommand(player, command, "g", emptyArray()) shouldBe true
            ChatModeAliasCommand.onCommand(player, command, "l", emptyArray()) shouldBe true
            scheduler.executeImmediate()

            verify { player.sendMessage(Component.text("global")) }
            verify { player.sendMessage(Component.text("local")) }
        }
    }
})

private fun player(playerId: UUID): Player =
    mockk(relaxed = true) {
        every { uniqueId } returns playerId
        every { isOnline } returns true
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
