package ru.arc.commandhide

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.TabCompleteEvent
import java.util.UUID

class CommandHideListenerTest :
    FreeSpec({
        "blocks execution only for players assigned to a hide group" {
            val restricted = playerWithPermissions("arc.command.hide.player")
            val unrestricted = playerWithPermissions()
            val listener = listener("plugins **")
            val blocked = PlayerCommandPreprocessEvent(restricted, "/bukkit:plugins extra")
            val allowed = PlayerCommandPreprocessEvent(unrestricted, "/plugins")

            listener.onPlayerCommand(blocked)
            listener.onPlayerCommand(allowed)

            blocked.isCancelled shouldBe true
            allowed.isCancelled shouldBe false
        }

        "filters matching server completions" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("example admin **")
            val event = TabCompleteEvent(player, "/example ", listOf("admin", "help"))

            listener.onTabComplete(event)

            event.completions shouldContainExactly listOf("help")
        }

        "filters namespaced root completions but keeps ordinary aliases" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener()
            val event =
                TabCompleteEvent(
                    player,
                    "/",
                    listOf("pwarp", "playerwarps:pwarp", "rediseconomy:pay"),
                )

            listener.onTabComplete(event)

            event.completions shouldContainExactly listOf("pwarp")
        }

        "removes completely blocked roots from the player command list" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("plugins **", "pl **", "world **")
            val commands = linkedSetOf("plugins", "pl", "world create", "world delete", "help")
            val event = PlayerCommandSendEvent(player, commands)

            listener.onPlayerCommandSend(event)

            event.commands shouldContainExactly listOf("help")
        }

        "removes namespaced roots from the player command list" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener()
            val commands = linkedSetOf("pwarp", "playerwarps:pwarp", "rediseconomy:pay")
            val event = PlayerCommandSendEvent(player, commands)

            listener.onPlayerCommandSend(event)

            event.commands shouldContainExactly listOf("pwarp")
        }

        "prunes a generated command tree only on Paper's synchronous pass" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("plugins **")
            val root = RootCommandNode<CommandSourceStack>()
            root.addChild(LiteralArgumentBuilder.literal<CommandSourceStack>("plugins").build())
            root.addChild(LiteralArgumentBuilder.literal<CommandSourceStack>("help").build())
            val asyncEvent = commandTreeEvent(player, root, asynchronous = true)
            val syncEvent = commandTreeEvent(player, root, asynchronous = false)

            listener.onBrigadierCommandTree(asyncEvent)
            root.getChild("plugins").shouldNotBeNull()

            listener.onBrigadierCommandTree(syncEvent)
            root.getChild("plugins").shouldBeNull()
            root.getChild("help").shouldNotBeNull()
        }

        "also prunes Paper's async tree pass when a safe cached policy exists" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("plugins **")
            listener.onPlayerCommandSend(PlayerCommandSendEvent(player, linkedSetOf("plugins", "help")))
            val root = RootCommandNode<CommandSourceStack>()
            root.addChild(LiteralArgumentBuilder.literal<CommandSourceStack>("plugins").build())
            root.addChild(LiteralArgumentBuilder.literal<CommandSourceStack>("help").build())

            listener.onBrigadierCommandTree(commandTreeEvent(player, root, asynchronous = true))

            root.getChild("plugins").shouldBeNull()
            root.getChild("help").shouldNotBeNull()
        }

        "rebuilds the command tree after the player join lifecycle" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("world **")

            listener.onPlayerJoin(PlayerJoinEvent(player, "joined"))

            verify(exactly = 1) { player.updateCommands() }
        }

        "runs visibility filters after other command tree contributors" {
            listOf(
                "onTabComplete" to arrayOf(TabCompleteEvent::class.java),
                "onPlayerCommandSend" to arrayOf(PlayerCommandSendEvent::class.java),
                "onBrigadierCommandTree" to arrayOf(AsyncPlayerSendCommandsEvent::class.java),
            ).forEach { (methodName, parameterTypes) ->
                CommandHideListener::class.java
                    .getDeclaredMethod(methodName, *parameterTypes)
                    .getAnnotation(EventHandler::class.java)
                    .priority shouldBe EventPriority.MONITOR
            }
        }
    })

private fun commandTreeEvent(
    player: Player,
    root: RootCommandNode<CommandSourceStack>,
    asynchronous: Boolean,
): AsyncPlayerSendCommandsEvent<CommandSourceStack> =
    mockk {
        every { this@mockk.player } returns player
        every { commandNode } returns root
        every { isAsynchronous } returns asynchronous
    }

private fun listener(vararg patterns: String): CommandHideListener {
    val config =
        TestCommandHideModuleConfig(
            blockedMessage = "",
            groups =
                listOf(
                    CommandHideGroupConfig(
                        id = "player",
                        commands = patterns.toList(),
                    ),
                ),
        )
    return CommandHideListener(CommandHidePolicyResolver(config), Runnable::run)
}

private fun playerWithPermissions(vararg permissions: String): Player {
    val player = mockk<Player>(relaxed = true)
    val granted = permissions.toSet()
    every { player.uniqueId } returns UUID.randomUUID()
    every { player.isOnline } returns true
    every { player.hasPermission(any<String>()) } answers { firstArg<String>() in granted }
    return player
}
