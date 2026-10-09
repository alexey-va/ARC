package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteEnchantmentOutcomeTest : StringSpec({
    "destruction is a separate one percent roll only after failure" {
        val chances = BookApplicationChances(85, 1)
        bookApplicationOutcome(chances, .84999, 0.0) shouldBe EliteEnchantmentOutcome.SUCCESS
        bookApplicationOutcome(chances, .85, .00999) shouldBe EliteEnchantmentOutcome.DESTROYED
        bookApplicationOutcome(chances, .85, .01) shouldBe EliteEnchantmentOutcome.FAILURE
        (0 until 100).flatMap { success ->
            (0 until 100).map { destroy -> bookApplicationOutcome(chances, (success + .5) / 100, (destroy + .5) / 100) }
        }.groupingBy { it }.eachCount() shouldBe mapOf(
            EliteEnchantmentOutcome.SUCCESS to 8500,
            EliteEnchantmentOutcome.DESTROYED to 15,
            EliteEnchantmentOutcome.FAILURE to 1485,
        )
    }
    "guaranteed books always succeed and zero destruction remains safe" {
        bookApplicationOutcome(BookApplicationChances(100, 1), .99999, 0.0) shouldBe EliteEnchantmentOutcome.SUCCESS
        bookApplicationOutcome(BookApplicationChances(0, 0), 0.0, 0.0) shouldBe EliteEnchantmentOutcome.FAILURE
    }
    "invalid random samples cannot silently consume a book" {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.001, 1.0).forEach { roll ->
            shouldThrow<IllegalArgumentException> { bookApplicationOutcome(BookApplicationChances(85, 1), roll, .5) }
            shouldThrow<IllegalArgumentException> { bookApplicationOutcome(BookApplicationChances(85, 1), .5, roll) }
        }
    }
    "book rates are assigned once and preserve unrelated metadata on refresh" {
        MockBukkitTestRuntime.open().use {
            val book = ItemStack(Material.ENCHANTED_BOOK)
            val custom = NamespacedKey("elitemobs", "test-marker")
            book.editMeta { it.persistentDataContainer.set(custom, PersistentDataType.STRING, "native") }
            val chances = prepareEliteBookChances(book)
            (chances.success in 40..80) shouldBe true
            chances.destroyOnFailure shouldBe 1
            repeat(5) { prepareEliteBookChances(book) shouldBe chances }
            readEliteBookChances(book.clone()) shouldBe chances
            book.itemMeta.persistentDataContainer.get(custom, PersistentDataType.STRING) shouldBe "native"
        }
    }
    "existing success and zero risk survive migration while excessive risk is capped" {
        MockBukkitTestRuntime.open().use {
            val book = ItemStack(Material.ENCHANTED_BOOK)
            val success = NamespacedKey("arc", "elite_book_success")
            val destroy = NamespacedKey("arc", "elite_book_destroy")
            book.editMeta {
                it.persistentDataContainer.set(success, PersistentDataType.INTEGER, 35)
                it.persistentDataContainer.set(destroy, PersistentDataType.INTEGER, 0)
            }
            prepareEliteBookChances(book) shouldBe BookApplicationChances(35, 0)
            book.editMeta { it.persistentDataContainer.set(destroy, PersistentDataType.INTEGER, 99) }
            prepareEliteBookChances(book) shouldBe BookApplicationChances(35, 1)
            book.itemMeta.persistentDataContainer.get(destroy, PersistentDataType.INTEGER) shouldBe 1
        }
    }
    "dust increases the persisted success with a hard cap and follows the native risk setting" {
        boostedBookChances(BookApplicationChances(40, 1), 15, true) shouldBe BookApplicationChances(55, 0)
        boostedBookChances(BookApplicationChances(95, 1), 15, false) shouldBe BookApplicationChances(100, 1)
        boostedBookChances(BookApplicationChances(100, 0), 15, true) shouldBe BookApplicationChances(100, 0)
        shouldThrow<IllegalArgumentException> { boostedBookChances(BookApplicationChances(40, 1), 0, true) }
        MockBukkitTestRuntime.open().use {
            val book = ItemStack(Material.ENCHANTED_BOOK)
            writeEliteBookChances(book, BookApplicationChances(40, 1))
            writeEliteBookChances(book, boostedBookChances(readEliteBookChances(book)!!, 15, true))
            prepareEliteBookChances(book) shouldBe BookApplicationChances(55, 0)
        }
    }
})
