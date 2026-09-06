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
    val command = mockk<Command> { every { name } returns "dungeon" }

    "routes only supported player actions to native EliteMobs" {
        val player = mockk<Player>(relaxed = true)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = EMDungeonCommand(config, { _, action, args -> calls += action to args }, { true })

        executor.onCommand(player, command, "dungeon", arrayOf("начать", "now")) shouldBe true
        executor.onCommand(player, command, "dungeon", arrayOf("выйти")) shouldBe true
        executor.onCommand(player, command, "dungeon", emptyArray()) shouldBe true

        calls shouldBe listOf("начать" to listOf("now"), "выйти" to emptyList(), "menu" to emptyList())
    }

    "canonical command shortcuts dispatch canonical actions" {
        val player = mockk<Player>(relaxed = true)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = EMDungeonCommand(config, { _, action, args -> calls += action to args }, { true })

        every { command.name } returnsMany listOf("dungeonstart", "dungeonsave", "dungeonsaves")
        executor.onCommand(player, command, "dungeonstart", arrayOf("ignored")) shouldBe true
        executor.onCommand(player, command, "dungeonsave", arrayOf("name")) shouldBe true
        executor.onCommand(player, command, "dungeonsaves", emptyArray()) shouldBe true

        calls shouldBe listOf("start" to listOf("ignored"), "save" to listOf("name"), "saves" to emptyList())
    }

    "global menu and portal command remain available without a local EliteMobs runtime" {
        val player = mockk<Player>(relaxed = true)
        val calls = mutableListOf<String>()
        every { command.name } returns "dungeon"
        val executor = EMDungeonCommand(config, { _, _, _ -> error("native dispatch forbidden") }, { false }, { _, action -> calls += action; true })
        executor.onCommand(player, command, "данж", emptyArray())
        executor.onCommand(player, command, "данж", arrayOf("тп"))
        calls shouldBe listOf("menu", "тп")
    }

    "fails closed for unavailable, nonplayers, and supports Russian tab completion" {
        val player = mockk<Player>(relaxed = true)
        every { config.component(any(), any<String>(), any()) } returns Component.text("unavailable")
        val executor = EMDungeonCommand(config, { _, _, _ -> error("must not dispatch") }, { false })
        executor.onCommand(player, command, "dungeon", arrayOf("start")) shouldBe true
        verify { player.sendMessage(Component.text("unavailable")) }

        val sender = mockk<org.bukkit.command.CommandSender>(relaxed = true)
        executor.onCommand(sender, command, "dungeon", emptyArray()) shouldBe true
        verify { sender.sendMessage(any<Component>()) }

        every { command.name } returns "dungeon"
        EMDungeonCommand(config, { _, _, _ -> }, { true }).onTabComplete(player, command, "dungeon", arrayOf("с")) shouldContainExactly listOf("сохраниться", "сохранения", "список")
    }
})
