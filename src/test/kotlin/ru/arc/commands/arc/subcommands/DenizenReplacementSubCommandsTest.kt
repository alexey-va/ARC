package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.CommandSender
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.inventory.ItemStack
import ru.arc.commandhide.CommandHideGroupConfig
import ru.arc.commandhide.CommandHideListener
import ru.arc.commandhide.CommandHidePolicyResolver
import ru.arc.commandhide.TestCommandHideModuleConfig
import ru.arc.landsui.ClaimCommandRoute
import ru.arc.landsui.ClaimMenuCommandListener
import ru.arc.landsui.claimCommandRoute
import ru.arc.paper.testing.MockBukkitTestRuntime

class DenizenReplacementSubCommandsTest : FreeSpec({
    "maps server destinations and keeps unauthorized optional targets on the sender" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arc.switch-other") } returns false
        val sends = mutableListOf<Pair<Player, String>>()
        val handler = serverHandler(
            findPlayer = { error("An unauthorized target should not be resolved") },
            transfer = { target, server -> sends += target to server; true },
        )

        handler.execute(player, arrayOf("main", "Other")) shouldBe true
        handler.execute(player, arrayOf("etd")) shouldBe true
        sends.map { it.first to it.second } shouldBe listOf(player to "spawn", player to "etd")
    }

    "only resolves explicit online targets for arc.switch-other" {
        val sender = mockk<CommandSender>(relaxed = true)
        val target = mockk<Player>(relaxed = true)
        every { sender.hasPermission("arc.switch-other") } returns true
        var routed: Pair<Player, String>? = null
        val handler = serverHandler(
            findPlayer = { name -> if (name == "Target") target else null },
            transfer = { player, server -> routed = player to server; true },
        )

        handler.execute(sender, arrayOf("main", "Target")) shouldBe true
        routed shouldBe (target to "spawn")
    }

    "rejects malformed or unknown server routes" {
        val sender = mockk<CommandSender>(relaxed = true)
        var usages = 0
        val handler = serverHandler(usage = { usages++ })

        handler.execute(sender, emptyArray()) shouldBe true
        handler.execute(sender, arrayOf("unknown")) shouldBe true
        handler.execute(sender, arrayOf("main", "target", "extra")) shouldBe true
        usages shouldBe 3
    }

    "charge action validates placeholder values and preserves full-charge reward branch" {
        chargeAction("12", "5") shouldBe ChargesAction.Refill(7)
        chargeAction("12", "12") shouldBe ChargesAction.AlreadyFull
        fullChargeReturnCommand("Target") shouldBe "money Target vault give 50000"
        chargeAction("12", "13") shouldBe null
        chargeAction("-1", "0") shouldBe null
        chargeAction("?", "0") shouldBe null
    }

    "claim-block command accepts a bounded optional amount only for give" {
        parseClaimBlockRequest(arrayOf("give", "Target")) shouldBe ClaimBlockRequest("Target", 1)
        parseClaimBlockRequest(arrayOf("GIVE", "Target", "16")) shouldBe ClaimBlockRequest("Target", 16)
        parseClaimBlockRequest(arrayOf("give", "Target", "0")) shouldBe null
        parseClaimBlockRequest(arrayOf("give", "Target", "2305")) shouldBe null
        parseClaimBlockRequest(arrayOf("give", "Target", "many")) shouldBe null
        parseClaimBlockRequest(arrayOf("other", "Target")) shouldBe null
        MockBukkitTestRuntime.open().use {
            splitStacks(ItemStack(Material.GRASS_BLOCK), 65).map { it.amount } shouldBe listOf(64, 1)
        }
    }

    "redirects historical claim commands to the existing menu and respects bypass" {
        listOf("//wand", "/wand", "/rg", "/&fswand").forEach {
            claimCommandRoute(it) shouldBe ClaimCommandRoute.OPEN_CLAIMS
        }
        claimCommandRoute("/wand extra") shouldBe ClaimCommandRoute.OPEN_CLAIMS
        claimCommandRoute("/home set") shouldBe ClaimCommandRoute.SET_HOME
        claimCommandRoute("/home set cabin") shouldBe null

        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arc.wand-bypass") } returns false
        var opens = 0
        val listener = ClaimMenuCommandListener(
            canOpenClaimMenu = { true },
            openClaimMenu = { opens++ },
            huskHomesEnabled = { true },
        )
        val event = PlayerCommandPreprocessEvent(player, "//wand")
        listener.onPlayerCommand(event)
        event.isCancelled shouldBe true
        opens shouldBe 1

        every { player.hasPermission("arc.wand-bypass") } returns true
        val bypass = PlayerCommandPreprocessEvent(player, "/rg")
        listener.onPlayerCommand(bypass)
        bypass.isCancelled shouldBe false
        opens shouldBe 1
        listener.close()
    }

    "routes home set to native sethome without requiring the claims menu" {
        val player = mockk<Player>(relaxed = true)
        var performed = false
        every { player.performCommand("sethome") } answers { performed = true; true }
        val listener = ClaimMenuCommandListener(
            canOpenClaimMenu = { false },
            openClaimMenu = {},
            huskHomesEnabled = { true },
        )
        val event = PlayerCommandPreprocessEvent(player, "/home set")

        listener.onPlayerCommand(event)

        event.isCancelled shouldBe true
        performed shouldBe true
        listener.close()
    }

    "leaves command routes intact when shortcuts or HuskHomes are unavailable" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arc.wand-bypass") } returns false
        val listener = ClaimMenuCommandListener(
            canOpenClaimMenu = { false },
            openClaimMenu = {},
            huskHomesEnabled = { false },
        )
        val wand = PlayerCommandPreprocessEvent(player, "/wand")
        val home = PlayerCommandPreprocessEvent(player, "/home set")

        listener.onPlayerCommand(wand)
        listener.onPlayerCommand(home)

        wand.isCancelled shouldBe false
        home.isCancelled shouldBe false
        listener.close()
    }

    "redirects a hidden rg alias before the HIGHEST command-hide filter" {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ClaimMenuCommandPriorityTest")
            val player = runtime.addPlayer("ClaimTester")
            player.addAttachment(plugin, "arc.command.hide.player", true)
            var opens = 0
            val redirects = ClaimMenuCommandListener(
                canOpenClaimMenu = { true },
                openClaimMenu = { opens++ },
                huskHomesEnabled = { false },
            )
            val hide = CommandHideListener(
                CommandHidePolicyResolver(
                    TestCommandHideModuleConfig(
                        groups = listOf(CommandHideGroupConfig("player", commands = listOf("rg **"))),
                    ),
                ),
                Runnable::run,
            )
            plugin.server.pluginManager.registerEvents(redirects, plugin)
            plugin.server.pluginManager.registerEvents(hide, plugin)

            val event = runtime.callEvent(PlayerCommandPreprocessEvent(player, "/rg"))

            event.isCancelled shouldBe true
            opens shouldBe 1
            redirects.close()
            org.bukkit.event.HandlerList.unregisterAll(hide)
        }
    }
})

private fun serverHandler(
    findPlayer: (String) -> Player? = { null },
    transfer: (Player, String) -> Boolean = { _, _ -> true },
    usage: (CommandSender) -> Unit = {},
) = ServerCommandHandler(
    findPlayer = findPlayer,
    transfer = transfer,
    usage = usage,
    playerRequired = {},
    playerNotFound = { _, _ -> },
    transferUnavailable = {},
)
