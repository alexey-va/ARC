package ru.arc.helpcenter

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class HelpCenterInventoryReturnStoreTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "returns only after the inventory that actually opened has closed" {
        val playerId = UUID.randomUUID()
        val inventory = Bukkit.createInventory(null, 9)
        val store = HelpCenterInventoryReturnStore()
        var returns = 0

        val nonce = store.arm(playerId) { returns++ }

        store.observeOpen(playerId, inventory) shouldBe true
        store.beginClose(playerId, inventory) shouldBe nonce
        store.consumeClose(playerId, nonce, inventory)?.invoke()
        store.consumeClose(playerId, nonce, inventory) shouldBe null
        returns shouldBe 1
    }

    "follows a replacement inventory without returning from the stale screen" {
        val playerId = UUID.randomUUID()
        val first = Bukkit.createInventory(null, 9)
        val second = Bukkit.createInventory(null, 18)
        val store = HelpCenterInventoryReturnStore()
        var returns = 0

        val nonce = store.arm(playerId) { returns++ }
        store.observeOpen(playerId, first) shouldBe true
        store.observeOpen(playerId, second) shouldBe true

        store.beginClose(playerId, first) shouldBe null
        store.beginClose(playerId, second) shouldBe nonce
        store.consumeClose(playerId, nonce, second)?.invoke()
        returns shouldBe 1
    }

    "expires a command that never opened an inventory" {
        val playerId = UUID.randomUUID()
        val inventory = Bukkit.createInventory(null, 9)
        val store = HelpCenterInventoryReturnStore()

        val nonce = store.arm(playerId) {}

        store.expireAwaitingOpen(playerId, nonce) shouldBe true
        store.observeOpen(playerId, inventory) shouldBe false
        store.beginClose(playerId, inventory) shouldBe null
    }

    "does not expire an inventory that opened inside the grace period" {
        val playerId = UUID.randomUUID()
        val inventory = Bukkit.createInventory(null, 9)
        val store = HelpCenterInventoryReturnStore()

        val nonce = store.arm(playerId) {}
        store.observeOpen(playerId, inventory) shouldBe true

        store.expireAwaitingOpen(playerId, nonce) shouldBe false
        store.beginClose(playerId, inventory) shouldBe nonce
    }

    "rearming invalidates delayed close work from the previous command" {
        val playerId = UUID.randomUUID()
        val first = Bukkit.createInventory(null, 9)
        val second = Bukkit.createInventory(null, 9)
        val store = HelpCenterInventoryReturnStore()
        var returns = 0

        val staleNonce = store.arm(playerId) { returns += 10 }
        store.observeOpen(playerId, first)
        val currentNonce = store.arm(playerId) { returns++ }
        store.observeOpen(playerId, second)

        store.consumeClose(playerId, staleNonce, first) shouldBe null
        store.consumeClose(playerId, currentNonce, second)?.invoke()
        returns shouldBe 1
    }
})
