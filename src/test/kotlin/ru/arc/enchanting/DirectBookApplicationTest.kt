package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class DirectBookApplicationTest : StringSpec({
    "success replaces the target in place, consumes one cursor book and never opens a menu" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK, 3)
            val upgraded = target.clone().apply { addUnsafeEnchantment(Enchantment.SHARPNESS, 1) }
            player.inventory.setItem(11, target.clone())
            player.setItemOnCursor(book.clone())
            val view = player.openInventory
            applyBookInInventory(player, 11, target, book, upgraded)
            player.inventory.getItem(11) shouldBe upgraded
            player.itemOnCursor.amount shouldBe 2
            player.openInventory shouldBe view
            shouldThrow<IllegalStateException> { applyBookInInventory(player, 11, target, book, upgraded) }
            player.itemOnCursor.amount shouldBe 2
        }
    }
    "ordinary failure keeps the exact item and consumes one admitted creative book" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Creative")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK, 2)
            player.inventory.setItem(11, target.clone())
            player.inventory.setItem(12, book.clone())
            applyBookInInventory(player, 11, target, book, target, 12)
            player.inventory.getItem(11) shouldBe target
            player.inventory.getItem(12)?.amount shouldBe 1
            player.itemOnCursor.type shouldBe Material.AIR
        }
    }
    "destruction consumes the target and one book, preserving unrelated inventory" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            player.inventory.setItem(11, target.clone())
            player.inventory.setItem(12, book.clone())
            player.inventory.setItem(13, ItemStack(Material.DIAMOND, 7))
            applyBookInInventory(player, 11, target, book, null, 12)
            player.inventory.getItem(11) shouldBe null
            player.inventory.getItem(12) shouldBe null
            player.inventory.getItem(13)?.amount shouldBe 7
        }
    }
    "synchronous result decoration does not mint a second target or spare the book" {
        MockBukkitTestRuntime.open().use {
            val inventory = mockk<org.bukkit.inventory.PlayerInventory>()
            val player = mockk<org.bukkit.entity.Player>()
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            val upgraded = target.clone().apply { addUnsafeEnchantment(Enchantment.SHARPNESS, 1) }
            var stored: ItemStack? = target.clone()
            var cursor: ItemStack? = book.clone()
            every { player.inventory } returns inventory
            every { player.itemOnCursor } answers { cursor ?: ItemStack(Material.AIR) }
            every { player.setItemOnCursor(any()) } answers { cursor = firstArg(); Unit }
            every { inventory.getItem(11) } answers { stored }
            every { inventory.setItem(11, any()) } answers {
                stored = secondArg<ItemStack?>()?.clone()?.apply {
                    editMeta { it.displayName(net.kyori.adventure.text.Component.text("Decorated")) }
                }
                Unit
            }
            applyBookInInventory(player, 11, target, book, upgraded)
            stored?.getEnchantmentLevel(Enchantment.SHARPNESS) shouldBe 1
            cursor shouldBe null
        }
    }
    "changed input refuses to consume either item" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Enchanter")
            val target = ItemStack(Material.DIAMOND_SWORD)
            val book = ItemStack(Material.ENCHANTED_BOOK)
            player.inventory.setItem(11, target.clone())
            player.inventory.setItem(12, ItemStack(Material.PAPER))
            shouldThrow<IllegalStateException> { applyBookInInventory(player, 11, target, book, null, 12) }
            player.inventory.getItem(11) shouldBe target
            player.inventory.getItem(12)?.type shouldBe Material.PAPER
        }
    }
})
