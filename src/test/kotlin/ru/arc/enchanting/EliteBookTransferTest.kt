package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteBookTransferTest : StringSpec({
    "successful opening moves exactly one book and one item into native custody" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK, 3)
            player.inventory.setItem(0, target.clone())
            player.setItemOnCursor(book.clone())
            val menu = runtime.server.createInventory(player, 54)
            transferEliteBookToConfirmation(player, 0, target, book, 29, 31) { menu } shouldBe menu
            player.inventory.getItem(0) shouldBe null
            player.itemOnCursor.amount shouldBe 2
            menu.getItem(29) shouldBe target
            menu.getItem(31) shouldBe book.clone().apply { amount = 1 }
        }
    }
    "cancelled native open returns inputs without consuming anything" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            player.inventory.setItem(0, target.clone())
            player.setItemOnCursor(book.clone())
            shouldThrow<IllegalStateException> {
                transferEliteBookToConfirmation(player, 0, target, book, 29, 31) { error("open cancelled") }
            }
            player.inventory.getItem(0) shouldBe target
            player.itemOnCursor shouldBe book
            player.inventory.contents.filterNotNull().sumOf { it.amount } shouldBe 1
        }
    }
    "failed opening preserves another listener's slot and returns the displaced target once" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK, 2)
            player.inventory.setItem(0, target.clone())
            player.setItemOnCursor(book.clone())
            shouldThrow<IllegalStateException> {
                transferEliteBookToConfirmation(player, 0, target, book, 29, 31) {
                    player.inventory.setItem(0, ItemStack(Material.EMERALD))
                    error("open redirected")
                }
            }
            player.inventory.getItem(0)?.type shouldBe Material.EMERALD
            player.inventory.contents.filterNotNull().filter { it.type == Material.DIAMOND_SWORD }.sumOf { it.amount } shouldBe 1
            player.itemOnCursor shouldBe book
        }
    }
    "stale or duplicate gesture never opens a menu or moves another input" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            val changed = ItemStack(Material.IRON_SWORD)
            player.inventory.setItem(0, changed)
            player.setItemOnCursor(book.clone())
            shouldThrow<IllegalStateException> {
                transferEliteBookToConfirmation(player, 0, target, book, 29, 31) { error("must not open") }
            }
            player.inventory.getItem(0) shouldBe changed
            player.itemOnCursor shouldBe book
        }
    }
    "occupied native input slots reject handover without overwriting native state" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            player.inventory.setItem(0, target.clone())
            player.setItemOnCursor(book.clone())
            val menu = runtime.server.createInventory(player, 54)
            menu.setItem(31, ItemStack(Material.EMERALD))
            shouldThrow<IllegalStateException> {
                transferEliteBookToConfirmation(player, 0, target, book, 29, 31) { menu }
            }
            menu.getItem(29) shouldBe null
            menu.getItem(31)?.type shouldBe Material.EMERALD
            player.inventory.getItem(0) shouldBe target
            player.itemOnCursor shouldBe book
        }
    }
    "creative inventory source transfers one book without reading or changing the cursor" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("CreativeEnchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK, 3)
            val cursor = ItemStack(Material.EMERALD)
            player.inventory.setItem(0, target.clone())
            player.inventory.setItem(1, book.clone())
            player.setItemOnCursor(cursor.clone())
            val menu = runtime.server.createInventory(player, 54)
            transferEliteBookToConfirmation(player, 0, target, book, 29, 31, 1) { menu }
            player.inventory.getItem(0) shouldBe null
            player.inventory.getItem(1)?.amount shouldBe 2
            player.itemOnCursor shouldBe cursor
            menu.getItem(29) shouldBe target
            menu.getItem(31) shouldBe book.clone().apply { amount = 1 }
        }
    }
    "creative failed opening restores the admitted book and target once" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("CreativeEnchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            player.inventory.setItem(0, target.clone())
            player.inventory.setItem(1, book.clone())
            shouldThrow<IllegalStateException> {
                transferEliteBookToConfirmation(player, 0, target, book, 29, 31, 1) { error("open cancelled") }
            }
            player.inventory.getItem(0) shouldBe target
            player.inventory.getItem(1) shouldBe book
            player.inventory.contents.filterNotNull().sumOf { it.amount } shouldBe 2
            player.itemOnCursor.type.isAir shouldBe true
        }
    }
})
