package ru.arc.enchanting

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime

class AdvancedBookPresentationTest {
    @Test
    fun `uses native presentation and migrates legacy rates without losing metadata`() {
        MockBukkitTestRuntime.open().use {
            val source = ItemStack(Material.ENCHANTED_BOOK, 3)
            val sourceMeta = source.itemMeta as EnchantmentStorageMeta
            sourceMeta.displayName(Component.text("Old book name").decoration(TextDecoration.ITALIC, true))
            sourceMeta.lore(listOf(Component.text("Old lore").decoration(TextDecoration.ITALIC, true)))
            sourceMeta.addStoredEnchant(Enchantment.SHARPNESS, 2, true)
            sourceMeta.setCustomModelData(8432)
            sourceMeta.addItemFlags(ItemFlag.HIDE_STORED_ENCHANTS)

            val bookData = NamespacedKey("advancedenchantments", "ae_book")
            val legacyBook = NamespacedKey("advancedenchantments", "book")
            val level = NamespacedKey("advancedenchantments", "ae_book_level")
            val success = NamespacedKey("advancedenchantments", "ae_book_success")
            val failure = NamespacedKey("advancedenchantments", "ae_book_failure")
            val custom = NamespacedKey("server", "custom_book_data")
            sourceMeta.persistentDataContainer.set(bookData, PersistentDataType.STRING, "sharpness")
            sourceMeta.persistentDataContainer.set(legacyBook, PersistentDataType.STRING, "ae_valid;sharpness;2;12;88")
            sourceMeta.persistentDataContainer.set(level, PersistentDataType.INTEGER, 2)
            sourceMeta.persistentDataContainer.set(success, PersistentDataType.INTEGER, 99)
            sourceMeta.persistentDataContainer.set(failure, PersistentDataType.INTEGER, 99)
            sourceMeta.persistentDataContainer.set(custom, PersistentDataType.STRING, "keep-exactly")
            source.itemMeta = sourceMeta

            val original = source.clone()
            val nativeBook = ItemStack(Material.ENCHANTED_BOOK)
            val nativeOnly = NamespacedKey("advancedenchantments", "factory_only")
            nativeBook.editMeta { meta ->
                meta.displayName(Component.text("Native AE title").decoration(TextDecoration.ITALIC, true))
                meta.lore(
                    listOf(
                        Component.text("Зачарование II").decoration(TextDecoration.ITALIC, true),
                        Component.text("Успех: 88%").decoration(TextDecoration.ITALIC, true),
                    ),
                )
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                meta.persistentDataContainer.set(nativeOnly, PersistentDataType.STRING, "must-not-copy")
                meta.persistentDataContainer.set(bookData, PersistentDataType.STRING, "sharpness")
                meta.persistentDataContainer.set(level, PersistentDataType.INTEGER, 2)
                meta.persistentDataContainer.set(success, PersistentDataType.INTEGER, 88)
                meta.persistentDataContainer.set(failure, PersistentDataType.INTEGER, 1)
            }

            val presented = requireNotNull(copyNativeAdvancedBookPresentation(source, nativeBook))
            val presentedMeta = presented.itemMeta as EnchantmentStorageMeta
            val originalMeta = original.itemMeta as EnchantmentStorageMeta
            val presentedLore = requireNotNull(presentedMeta.lore())
            val presentedPdc = presentedMeta.persistentDataContainer
            val riskNormalized = requireNotNull(copyNativeAdvancedBookMetadata(source, nativeBook))
            val normalizedMeta = riskNormalized.itemMeta as EnchantmentStorageMeta

            assertNotSame(source, presented)
            assertEquals(Material.ENCHANTED_BOOK, presented.type)
            assertEquals(3, presented.amount)
            val presentedName = requireNotNull(presentedMeta.displayName())
            assertEquals("Native AE title", plain(presentedName))
            assertEquals(TextDecoration.State.FALSE, presentedName.decoration(TextDecoration.ITALIC))
            assertEquals(listOf("Зачарование II", "Успех: 88%"), presentedLore.map(::plain))
            assertTrue(presentedLore.all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE })

            assertEquals(sourceMeta.persistentDataContainer.keys - legacyBook, presentedPdc.keys)
            assertFalse(presentedPdc.has(legacyBook, PersistentDataType.STRING))
            assertEquals("sharpness", presentedPdc.get(bookData, PersistentDataType.STRING))
            assertEquals(2, presentedPdc.get(level, PersistentDataType.INTEGER))
            assertEquals(88, presentedPdc.get(success, PersistentDataType.INTEGER))
            assertEquals(1, presentedPdc.get(failure, PersistentDataType.INTEGER))
            assertEquals("keep-exactly", presentedPdc.get(custom, PersistentDataType.STRING))
            assertFalse(presentedPdc.has(nativeOnly, PersistentDataType.STRING))

            assertEquals(sourceMeta.itemFlags, presentedMeta.itemFlags)
            assertEquals(sourceMeta.customModelData, presentedMeta.customModelData)
            assertEquals(sourceMeta.storedEnchants, presentedMeta.storedEnchants)
            assertEquals(originalMeta.displayName(), source.itemMeta.displayName())
            assertEquals(originalMeta.lore(), source.itemMeta.lore())
            assertEquals(originalMeta.displayName(), normalizedMeta.displayName())
            assertEquals(originalMeta.lore(), normalizedMeta.lore())
            assertFalse(normalizedMeta.persistentDataContainer.has(legacyBook, PersistentDataType.STRING))
            assertEquals("sharpness", normalizedMeta.persistentDataContainer.get(bookData, PersistentDataType.STRING))
            assertEquals(2, normalizedMeta.persistentDataContainer.get(level, PersistentDataType.INTEGER))
            assertEquals(1, normalizedMeta.persistentDataContainer.get(failure, PersistentDataType.INTEGER))
            assertEquals(88, normalizedMeta.persistentDataContainer.get(success, PersistentDataType.INTEGER))
        }
    }

    private fun plain(component: Component): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
}
