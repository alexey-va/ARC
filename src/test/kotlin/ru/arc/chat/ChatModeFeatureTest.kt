package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatCommandDecorateEvent
import io.papermc.paper.event.player.AsyncChatDecorateEvent
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
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
        "decorates before the regular chat event at the earliest Bukkit priority" {
            val handler =
                ChatListener::class.java
                    .getDeclaredMethod("onChatDecorate", AsyncChatDecorateEvent::class.java)
                    .getAnnotation(EventHandler::class.java)

            handler.priority shouldBe EventPriority.LOWEST
        }

        "applies the player palette at the final formatting priority after CMI" {
            val handler =
                ChatListener::class.java
                    .getDeclaredMethod("applyChatPalette", AsyncChatEvent::class.java)
                    .getAnnotation(EventHandler::class.java)

            handler.priority shouldBe EventPriority.HIGHEST
            handler.ignoreCancelled shouldBe false
        }

        "does not replace the renderer for cancelled chat" {
            val player = player(UUID.randomUUID())
            val event =
                mockk<AsyncChatEvent>(relaxed = true) {
                    every { this@mockk.player } returns player
                    every { isCancelled } returns true
                }

            ChatListener({ _, _ -> }, { ChatMode.LOCAL }).applyChatPalette(event)

            verify(exactly = 0) { event.renderer(any()) }
        }

        "prefixes an unprefixed message in global mode during decoration" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            val original = Component.text("Привет")
            var message: Component = original
            val event = decorateEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }).onChatDecorate(event)

            message shouldBe Component.text("!").append(original)
        }

        "does not add a second prefix in global mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("!Привет")
            val event = decorateEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }).onChatDecorate(event)

            plainText.serialize(message) shouldBe "!Привет"
        }

        "leaves a message unchanged in local mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = decorateEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.LOCAL }).onChatDecorate(event)

            plainText.serialize(message) shouldBe "Привет"
        }

        "does not decorate messages sent through commands" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event =
                mockk<AsyncChatCommandDecorateEvent>(relaxed = true) {
                    every { this@mockk.player() } returns player
                    every { result() } answers { message }
                    every { result(any()) } answers { message = firstArg() }
                }

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }).onChatDecorate(event)

            plainText.serialize(message) shouldBe "Привет"
        }

        "does not decorate input captured by a title form" {
            val playerId = UUID.randomUUID()
            val player = player(playerId)
            var message: Component = Component.text("Привет")
            val event = decorateEvent(player, { message }, { message = it })

            ChatListener({ _, _ -> }, { ChatMode.GLOBAL }, { true }).onChatDecorate(event)

            plainText.serialize(message) shouldBe "Привет"
        }

        "colors only the first sender name for local chat" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")
            val listener = ChatListener({ _, _ -> }, { ChatMode.LOCAL })
            var decorated: Component = Component.text("Привет")
            listener.onChatDecorate(decorateEvent(player, { decorated }, { decorated = it }))
            val rendered = applyPalette(listener, player)

            coloredText(rendered, "GrocerMC").first().color() shouldBe TextColor.color(0xD6A85F)
            coloredText(rendered, "GrocerMC").drop(1).all { it.color() == null } shouldBe true
        }

        "uses the global name color for an explicit shout from local mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")
            val listener = ChatListener({ _, _ -> }, { ChatMode.LOCAL })
            var decorated: Component = Component.text("!Привет")
            listener.onChatDecorate(decorateEvent(player, { decorated }, { decorated = it }))

            coloredText(applyPalette(listener, player), "GrocerMC").first().color() shouldBe
                TextColor.color(0x72B8E6)
        }

        "uses the global name color for persisted global mode" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")
            val listener = ChatListener({ _, _ -> }, { ChatMode.GLOBAL })
            var decorated: Component = Component.text("Привет")
            listener.onChatDecorate(decorateEvent(player, { decorated }, { decorated = it }))

            coloredText(applyPalette(listener, player), "GrocerMC").first().color() shouldBe
                TextColor.color(0x72B8E6)
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

private fun player(
    playerId: UUID,
    playerName: String = "Player",
): Player =
    mockk(relaxed = true) {
        every { uniqueId } returns playerId
        every { name } returns playerName
        every { isOnline } returns true
    }

private fun decorateEvent(
    player: Player,
    getResult: () -> Component,
    setResult: (Component) -> Unit,
): AsyncChatDecorateEvent =
    mockk(relaxed = true) {
        every { this@mockk.player() } returns player
        every { result() } answers { getResult() }
        every { result(any()) } answers { setResult(firstArg()) }
    }

private fun applyPalette(
    listener: ChatListener,
    player: Player,
): Component {
    var renderer =
        ChatRenderer { source, _, _, _ ->
            Component.text("icon | ${source.name} [KXE] ${source.name}")
        }
    val event =
        mockk<AsyncChatEvent>(relaxed = true) {
            every { this@mockk.player } returns player
            every { renderer() } answers { renderer }
            every { renderer(any()) } answers { renderer = firstArg() }
        }

    listener.applyChatPalette(event)
    return renderer.render(player, Component.text(player.name), Component.text("Привет"), player)
}

private fun coloredText(
    component: Component,
    text: String,
): List<TextComponent> =
    buildList {
        if (component is TextComponent && component.content() == text) add(component)
        component.children().forEach { addAll(coloredText(it, text)) }
    }
