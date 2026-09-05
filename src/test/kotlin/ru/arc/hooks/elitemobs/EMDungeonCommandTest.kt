package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.entity.Player
import ru.arc.config.Config

class EMDungeonCommandTest : FreeSpec({
    val config = mockk<Config>(relaxed = true)
    val command = mockk<Command>()

    "routes only supported player actions to native EliteMobs" {
        val player = mockk<Player>(relaxed = true)
        val executor = EMDungeonCommand(config) { true }

        executor.onCommand(player, command, "dungeon", arrayOf("начать")) shouldBe true
        executor.onCommand(player, command, "dungeon", arrayOf("выйти")) shouldBe true

        verify { player.performCommand("elitemobs:elitemobs start") }
        verify { player.performCommand("elitemobs:elitemobs quit") }
    }

    "fails closed when EliteMobs is unavailable" {
        val player = mockk<Player>(relaxed = true)
        every { config.component(any(), any<String>(), any()) } returns Component.text("unavailable")
        val executor = EMDungeonCommand(config) { false }

        executor.onCommand(player, command, "dungeon", arrayOf("start")) shouldBe true

        verify(exactly = 0) { player.performCommand(any()) }
        verify { player.sendMessage(Component.text("unavailable")) }
    }

    "suggests bounded actions" {
        EMDungeonCommand(config) { true }.onTabComplete(mockk(), command, "dungeon", arrayOf("q")) shouldContainExactly listOf("quit")
    }
})
