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

        "restores the global prefix when CMI expires its formatting package" {
            val player = player(UUID.randomUUID(), "GrocerMC")
            val listener = ChatListener({ _, _ -> }, { ChatMode.LOCAL })
            var decorated: Component = Component.text("!Привет")
            listener.onChatDecorate(decorateEvent(player, { decorated }, { decorated = it }))

            val rendered = applyPalette(listener, player, renderedPrefix = Component.empty())

            plainText.serialize(rendered).startsWith(" | GrocerMC") shouldBe true
            coloredText(rendered, "").single().color() shouldBe TextColor.color(0xFFFFFF)
            coloredText(rendered, "|").single().color() shouldBe TextColor.color(0x555555)
        }

        "restores the local prefix when CMI expires its formatting package" {
            val player = player(UUID.randomUUID(), "GrocerMC")

            val rendered =
                applyPalette(
                    ChatListener({ _, _ -> }, { ChatMode.LOCAL }),
                    player,
                    renderedPrefix = Component.empty(),
                )

            plainText.serialize(rendered).startsWith(" | GrocerMC") shouldBe true
        }

        "does not duplicate an intact CMI channel prefix" {
            val player = player(UUID.randomUUID(), "GrocerMC")
            val cmiPrefix =
                Component.text()
                    .append(Component.text("", TextColor.color(0xFFFFFF)))
                    .append(Component.space())
                    .append(Component.text("|", TextColor.color(0x555555)))
                    .append(Component.space())
                    .build()

            val rendered =
                applyPalette(
                    ChatListener({ _, _ -> }, { ChatMode.GLOBAL }),
                    player,
                    renderedPrefix = cmiPrefix,
                )

            plainText.serialize(rendered).count { it == '' } shouldBe 1
        }

        "uses a warm body for local chat and a cool body for global chat" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")

            val local = applyPalette(ChatListener({ _, _ -> }, { ChatMode.LOCAL }), player)
            val global = applyPalette(ChatListener({ _, _ -> }, { ChatMode.GLOBAL }), player)

            coloredText(local, "Привет").single().color() shouldBe TextColor.color(0xE8D7B7)
            coloredText(global, "Привет").single().color() shouldBe TextColor.color(0xCFE7FF)
        }

        "keeps a non-default player message color while changing the channel default" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")
            val message =
                Component.text()
                    .append(Component.text("Обычно").color(TextColor.color(0xE8D7B7)))
                    .append(Component.text("Особо").color(TextColor.color(0x55FF55)))
                    .build()

            val rendered =
                applyPalette(
                    ChatListener({ _, _ -> }, { ChatMode.GLOBAL }),
                    player,
                    message,
                )

            coloredText(rendered, "Обычно").single().color() shouldBe TextColor.color(0xCFE7FF)
            coloredText(rendered, "Особо").single().color() shouldBe TextColor.color(0x55FF55)
        }

        "applies the configured deterministic speaker tint to the message body" {
            val playerId = UUID.randomUUID()
            val player = player(playerId, "GrocerMC")
            val variation = ChatMessageColorVariation(enabled = true, hueAmplitudeDegrees = 12.0)
            val listener = ChatListener({ _, _ -> }, { ChatMode.GLOBAL }, { false }, variation)

            val rendered = applyPalette(listener, player)

            coloredText(rendered, "Привет").single().color() shouldBe
                ChatMessageColorizer.colorFor(TextColor.color(0xCFE7FF), "GrocerMC", variation)
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
    message: Component = Component.text("Привет").color(TextColor.color(0xE8D7B7)),
    renderedPrefix: Component = Component.text("icon | "),
): Component {
    var renderer =
        ChatRenderer { source, _, renderedMessage, _ ->
            Component.text()
                .append(renderedPrefix)
                .append(Component.text(source.name))
                .append(Component.text(" [KXE] "))
                .append(Component.text(source.name))
                .append(Component.space())
                .append(renderedMessage)
                .build()
        }
    val event =
        mockk<AsyncChatEvent>(relaxed = true) {
            every { this@mockk.player } returns player
            every { renderer() } answers { renderer }
            every { renderer(any()) } answers { renderer = firstArg() }
        }

    listener.applyChatPalette(event)
    return renderer.render(player, Component.text(player.name), message, player)
}

private fun coloredText(
    component: Component,
    text: String,
): List<TextComponent> =
    buildList {
        if (component is TextComponent && component.content() == text) add(component)
        component.children().forEach { addAll(coloredText(it, text)) }
    }
