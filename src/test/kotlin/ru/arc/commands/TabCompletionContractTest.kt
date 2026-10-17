package ru.arc.commands

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.LegacySubCommandExecutor
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.subcommands.*
import ru.arc.slimefunmenu.NetworkSpawnCommand
import ru.arc.slimefunmenu.SlimefunMenuAliasCommand
import ru.arc.travelanchors.TravelAnchorSubCommand

class TabCompletionContractTest : TestBase() {
    @Test
    fun `registered command boundaries suppress Bukkit player fallback`() {
        val player = server.addPlayer("CompletionPlayer")
        server.addPlayer("OtherPlayer")
        for (label in listOf("menu", "g", "l", "graves", "arc-reward-issue")) {
            val command = requireNotNull(plugin.getCommand(label))
            for (args in listOf(arrayOf(""), arrayOf("Other"), arrayOf("extra", ""))) {
                assertEquals(emptyList<String>(), command.tabComplete(player, label, args), "$label ${args.toList()}")
            }
        }
        for (command in listOf(NetworkSpawnCommand(), SlimefunMenuAliasCommand())) {
            assertEquals(emptyList<String>(), command.tabComplete(player, command.name, arrayOf("")))
        }
        val arc = requireNotNull(plugin.getCommand("arc"))
        for (args in listOf(emptyArray(), arrayOf("unknown", ""), arrayOf("reload", ""), arrayOf("chat", "local", ""))) {
            assertEquals(emptyList<String>(), arc.tabComplete(player, "arc", args), args.toList().toString())
        }
        for (label in listOf("sound-follow", "give-jobs-boost", "arctest", "eliteloot", "treasure-hunt", "treasure-pool")) {
            assertEquals(emptyList<String>(), requireNotNull(plugin.getCommand(label)).tabComplete(player, label, arrayOf("")), label)
        }
        player.addAttachment(plugin, "arc.sound.follow", true)
        val sound = requireNotNull(plugin.getCommand("sound-follow"))
        assertEquals(listOf("OtherPlayer"), sound.tabComplete(player, "sound-follow", arrayOf("oth")))
        assertEquals(emptyList<String>(), sound.tabComplete(player, "sound-follow", arrayOf("OtherPlayer", "minecraft:music.game", "")))
    }

    @Test
    fun `completion boundaries enforce player-only availability and provider prefixes`() {
        val player = server.addPlayer("CompletionPlayer")
        val bukkitCommand = requireNotNull(plugin.getCommand("arc"))
        val subCommand = object : SubCommand {
            override val configKey = "completion-fixture"
            override val defaultPlayerOnly = true
            override fun execute(sender: org.bukkit.command.CommandSender, args: Array<String>) = true
            override fun tabComplete(sender: org.bukkit.command.CommandSender, args: Array<String>) = listOf("alpha", "beta")
        }
        val bridge = LegacySubCommandExecutor(subCommand)
        assertEquals(listOf("alpha"), bridge.onTabComplete(player, bukkitCommand, "fixture", arrayOf("al")))
        assertEquals(emptyList<String>(), bridge.onTabComplete(server.consoleSender, bukkitCommand, "fixture", arrayOf("")))
        val unavailable = object : SubCommand by subCommand { override fun isAvailable() = false }
        assertEquals(emptyList<String>(), LegacySubCommandExecutor(unavailable).onTabComplete(player, bukkitCommand, "fixture", arrayOf("")))
        assertEquals(emptyList<String>(), ArcCommand().onTabComplete(server.consoleSender, bukkitCommand, "arc", arrayOf("rtp", "")))
    }

    @Test
    fun `public routes remain visible while administrative actions stay hidden`() {
        val player = server.addPlayer("Visitor")
        mockkObject(FurnitureSubCommand, OriginPortalsSubCommand)
        try {
            every { FurnitureSubCommand.isAvailable() } returns true
            every { OriginPortalsSubCommand.isAvailable() } returns true
            every { OriginPortalsSubCommand.permission } returns "arc.admin"
            every { OriginPortalsSubCommand.tabComplete(player, any()) } returns listOf("enter")
            val arc = ArcCommand()
            val command = requireNotNull(plugin.getCommand("arc"))
            assertEquals(listOf("furniture"), arc.onTabComplete(player, command, "arc", arrayOf("furn")))
            assertEquals(listOf("guide", "gallery"), arc.onTabComplete(player, command, "arc", arrayOf("furniture", "")))
            assertEquals(emptyList<String>(), arc.onTabComplete(player, command, "arc", arrayOf("furniture", "cleanup", "")))
            assertEquals(listOf("enter"), arc.onTabComplete(player, command, "arc", arrayOf("originportals", "e")))
            assertEquals(emptyList<String>(), arc.onTabComplete(player, command, "arc", arrayOf("originportals", "move")))
            assertEquals(listOf("cleanup-at"), FurnitureSubCommand.tabComplete(server.consoleSender, arrayOf("")))
        } finally {
            unmockkObject(FurnitureSubCommand, OriginPortalsSubCommand)
        }
    }

    @Test
    fun `terminal and invalid actions never suggest another argument`() {
        val player = server.addPlayer("CompletionPlayer")
        val cases = listOf(
            AuditSubCommand to arrayOf("clearall", ""),
            AuditSubCommand to arrayOf("CompletionPlayer", "clear", ""),
            AuditSubCommand to arrayOf("reset-period", "status", ""),
            CommandHideSubCommand() to arrayOf("unknown", ""),
            DecorInteractionSubCommand to arrayOf("unknown", ""),
            EmshopSubCommand to arrayOf("reset", ""),
            GiveSubCommand to arrayOf("list", "", ""),
            PouchSubCommand to arrayOf("list", "", ""),
            GiveBoostSubCommand to arrayOf("CompletionPlayer", "all", "1.0", "EXP", "1h", ""),
            RtpSubCommand to arrayOf("unknown", ""),
            ServerSubCommand to arrayOf("unknown", ""),
            StaffTestSubCommand to arrayOf("unknown", ""),
            TravelAnchorSubCommand to arrayOf("unknown", ""),
            TravelAnchorSubCommand to arrayOf("unknown", "CompletionPlayer", ""),
            TreasuresSubCommand to arrayOf("list", ""),
            TreasuresSubCommand to arrayOf("reload", "add", ""),
        )
        for ((subCommand, args) in cases) {
            assertEquals(emptyList<String>(), subCommand.tabComplete(player, args).orEmpty(), "${subCommand.configKey} ${args.toList()}")
        }
        assertEquals(listOf("-weight:1", "-quantity:1"), TreasuresSubCommand.tabComplete(player, arrayOf("pool", "add", "")))
        assertEquals(listOf("-quantity:1"), TreasuresSubCommand.tabComplete(player, arrayOf("pool", "add", "-weight:1", "-q")))
        assertEquals(listOf("-quantity:1"), TreasuresSubCommand.tabComplete(player, arrayOf("pool", "add", "-WEIGHT:1", "")))
    }

    @Test
    fun `nested administrative suggestions require their execution permissions`() {
        val player = server.addPlayer("CompletionPlayer")
        val command = requireNotNull(plugin.getCommand("x"))
        assertEquals(emptyList<String>(), XCommand.onTabComplete(player, command, "x", arrayOf("")))
        assertEquals(emptyList<String>(), SchedulesSubCommand.tabComplete(player, arrayOf("")))
        assertEquals(emptyList<String>(), SchedulesSubCommand.tabComplete(player, arrayOf("run", "")))
        assertEquals(emptyList<String>(), JobsboostsSubCommand.tabComplete(player, arrayOf("reset", "")).orEmpty())
        assertEquals(emptyList<String>(), AuditSubCommand.tabComplete(player, arrayOf("reset-period", "")))
        player.addAttachment(plugin, "arc.x", true)
        player.addAttachment(plugin, "arc.schedules.run", true)
        player.addAttachment(plugin, "arc.admin", true)
        player.addAttachment(plugin, "arc.audit.reset", true)
        assertTrue(XCommand.onTabComplete(player, command, "x", arrayOf("-sender:p")).contains("-sender:player"))
        assertEquals(listOf("run"), SchedulesSubCommand.tabComplete(player, arrayOf("r")))
        assertEquals(listOf("CompletionPlayer"), JobsboostsSubCommand.tabComplete(player, arrayOf("reset", "Comp")))
        assertEquals(listOf("status", "confirm"), AuditSubCommand.tabComplete(player, arrayOf("reset-period", "")))
    }

    @Test
    fun `restart completion preserves flag prefixes and respects used flags and remote authority`() {
        val player = server.addPlayer("CompletionPlayer")
        assertEquals(emptyList<String>(), RestartSubCommand.tabComplete(player, arrayOf("")))
        player.addAttachment(plugin, "arc.restart", true)
        fun complete(vararg args: String) = RestartSubCommand.tabComplete(player, arrayOf(*args))
        assertFalse(complete("").any { it.startsWith("-servers") })
        assertEquals(listOf("-delay:30", "-delay:3m", "-delay:30s"), complete("-delay:3"))
        assertEquals(listOf("30", "3m", "30s"), complete("-delay", "3"))
        assertTrue(complete("-delay", "").contains("3m"))
        assertEquals(emptyList<String>(), complete("-delay:3m", ""))
        assertEquals(emptyList<String>(), complete("cancel", "-delay"))
        assertEquals(emptyList<String>(), complete("nonsense"))
        player.addAttachment(plugin, "arc.restart.all", true)
        assertEquals(listOf("-servers:spawn"), complete("-servers:sp"))
        assertEquals(listOf("spawn"), complete("-servers", "sp"))
        assertTrue(complete("-servers", "").contains("spawn"))
        assertEquals(listOf("-delay", "-delay:3m"), complete("-servers:spawn", ""))
        assertEquals(emptyList<String>(), complete("-servers:spawn", "-servers:"))
        assertFalse(complete("-servers:spawn,").any { it.endsWith(",spawn") || it.endsWith(",all") })
        assertEquals(emptyList<String>(), complete("-servers:all,"))
    }
}
