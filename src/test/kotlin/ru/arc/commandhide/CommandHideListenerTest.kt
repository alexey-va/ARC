package ru.arc.commandhide

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
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

        "removes completely blocked roots from the player command list" {
            val player = playerWithPermissions("arc.command.hide.player")
            val listener = listener("plugins **", "pl **")
            val commands = linkedSetOf("plugins", "pl", "help")
            val event = PlayerCommandSendEvent(player, commands)

            listener.onPlayerCommandSend(event)

            event.commands shouldContainExactly listOf("help")
        }
    })

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
    return CommandHideListener(CommandHidePolicyResolver(config))
}

private fun playerWithPermissions(vararg permissions: String): Player {
    val player = mockk<Player>(relaxed = true)
    val granted = permissions.toSet()
    every { player.uniqueId } returns UUID.randomUUID()
    every { player.hasPermission(any<String>()) } answers { firstArg<String>() in granted }
    return player
}
