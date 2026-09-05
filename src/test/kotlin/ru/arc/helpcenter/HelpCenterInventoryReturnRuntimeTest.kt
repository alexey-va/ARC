package ru.arc.helpcenter

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime

class HelpCenterInventoryReturnRuntimeTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "returns one tick after the observed inventory closes" {
        val plugin = paper.createSimplePlugin("HelpCenterReturn")
        val player = paper.addPlayer("Viewer")
        val runtime = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { true })
        var returns = 0

        runtime.arm(player) { returns++ }
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()

        returns shouldBe 0
        paper.performTicks(1)
        returns shouldBe 1
        runtime.close()
    }

    "close all consumes the return without reopening any menu" {
        val plugin = paper.createSimplePlugin("HelpCenterCloseAll")
        val player = paper.addPlayer("Viewer")
        var back = false
        val runtime = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { back })
        var returns = 0
        runtime.arm(player) { returns++ }
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(1)
        back = true
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(1)
        returns shouldBe 0
        runtime.close()
    }

    "does not capture an unrelated inventory after the grace period" {
        val plugin = paper.createSimplePlugin("HelpCenterExpiry")
        val player = paper.addPlayer("Viewer")
        val runtime = HelpCenterInventoryReturnRuntime(plugin, openGraceTicks = 2)
        var returns = 0

        runtime.arm(player) { returns++ }
        paper.performTicks(2)
        player.openInventory(Bukkit.createInventory(null, 9))
        player.closeInventory()
        paper.performTicks(1)

        returns shouldBe 0
        runtime.close()
    }

    "keeps replacement inventories inside the same external flow" {
        val plugin = paper.createSimplePlugin("HelpCenterChain")
        val player = paper.addPlayer("Viewer")
        val runtime = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { true })
        var returns = 0

        runtime.arm(player) { returns++ }
        player.openInventory(Bukkit.createInventory(null, 9))
        player.openInventory(Bukkit.createInventory(null, 18))
        paper.performTicks(1)

        returns shouldBe 0
        player.closeInventory()
        paper.performTicks(1)
        returns shouldBe 1
        runtime.close()
    }
})
