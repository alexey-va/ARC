package ru.arc.enchanting

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.advancedplugins.ae.api.AEAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.inventory.PrepareInventoryResultEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.listeners.PickupListener
import ru.arc.paper.testing.MockBukkitTestRuntime

class EquipmentEnchantmentPresentationTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val aeKey = NamespacedKey("advancedenchantments", "ae_enchantment-lightweight")

    @Test
    fun `ordinary gear keeps native labels stats counters and authored spacing across refresh and disenchantment`() {
        MockBukkitTestRuntime.open().use {
            val body = listOf("", "История клинка", "", "", "Мобов: 2540", "").map(Component::text)
            val item = ItemStack(Material.NETHERITE_SWORD).apply {
                // MockBukkit displayName indexes only vanilla levels; native over-level labels are not simulated.
                addUnsafeEnchantment(Enchantment.SHARPNESS, 3)
                addUnsafeEnchantment(Enchantment.MENDING, 1)
                editMeta { it.lore(body) }
            }
            val original = item.clone()
            val presented = EquipmentEnchantmentPresentation.ordinary(item)
            assertEquals(original, item)
            assertEquals(original.enchantments, presented.enchantments)
            assertFalse(presented.itemMeta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES))
            assertTrue(presented.itemMeta.hasItemFlag(ItemFlag.HIDE_ENCHANTS))
            // The pinned ItemStackMock drops ItemMeta.tooltipStyle. Frame persistence requires Paper.
            assertEquals(body, presented.lore()!!.takeLast(body.size))
            assertEquals(presented, EquipmentEnchantmentPresentation.ordinary(presented))

            val renamed = presented.clone().apply { addUnsafeEnchantment(Enchantment.SHARPNESS, 4) }
            val preview = PrepareInventoryResultEvent(mockk<InventoryView>(relaxed = true), renamed)
            PickupListener().onPrepareResult(preview)
            assertEquals(4, preview.result!!.getEnchantmentLevel(Enchantment.SHARPNESS))
            assertNotEquals(presented.lore(), preview.result!!.lore())
            val cleared = preview.result!!.clone().apply { enchantments.keys.forEach(::removeEnchantment) }
            val cleaned = EquipmentEnchantmentPresentation.ordinary(cleared)
            val expected = original.clone().apply { enchantments.keys.forEach(::removeEnchantment) }
            assertEquals(expected, cleaned)
        }
    }

    @Test
    fun `AE display preserves original components metadata and native prefixes after legacy rewrite and upgrade`() {
        MockBukkitTestRuntime.open().use {
            val native = Component.text("Лёгкий III", NamedTextColor.GREEN).hoverEvent(Component.text("Подсказка"))
                .decoration(TextDecoration.ITALIC, false)
            val counters = listOf(Component.text("Убийств: 0"), Component.empty(), Component.empty(), Component.text("Собрано душ: 2926"))
            val item = ItemStack(Material.NETHERITE_SWORD).apply {
                editMeta {
                    it.lore(listOf(native) + counters)
                    it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 3)
                }
            }
            val vanilla = listOf(Component.text(" Острота VIII", NamedTextColor.GRAY))
            val first = EquipmentEnchantmentPresentation.render(item, vanilla, listOf(native), true, null)
            assertTrue(first.lore()!!.contains(native))
            assertEquals(counters, first.lore()!!.takeLast(counters.size))
            assertTrue(first.itemMeta.getLore()!!.contains("§aЛёгкий III"))
            assertEquals(3, first.itemMeta.persistentDataContainer.get(aeKey, PersistentDataType.INTEGER))
            assertEquals(first, EquipmentEnchantmentPresentation.render(first, vanilla, listOf(native), true, null))

            // AE flattens the existing lore and prepends its replacement, retaining ARC's old heading.
            val nativeUpgrade = first.clone().apply {
                editMeta { meta ->
                    meta.setLore(listOf("§aЛёгкий IV") + meta.getLore()!!.filterNot { it.startsWith("§aЛёгкий III") })
                    meta.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 4)
                }
            }
            val upgraded = Component.text("Лёгкий IV", NamedTextColor.GREEN)
            val second = EquipmentEnchantmentPresentation.render(nativeUpgrade, vanilla, listOf(upgraded), true, null)
            assertEquals(1, second.lore()!!.count { plain.serialize(it) == "Дополнительные чары" })
            assertEquals(1, second.lore()!!.count { plain.serialize(it) == "Лёгкий IV" })
            assertEquals(counters.map(plain::serialize), second.lore()!!.takeLast(counters.size).map(plain::serialize))
            assertEquals(second, EquipmentEnchantmentPresentation.render(second, vanilla, listOf(upgraded), true, null))
        }
    }

    @Test
    fun `native AE metadata restores its current rows after EliteMobs rebuild without changing price or binding`() {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("AdvancedEnchantments")
            if (!plugin.isEnabled) runtime.server.pluginManager.enablePlugin(plugin)
            Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
            mockkStatic(AEAPI::class)
            try {
                every { AEAPI.getEnchantmentsOnItem(any()) } answers {
                    hashMapOf("lightweight" to firstArg<ItemStack>().itemMeta.persistentDataContainer.get(aeKey, PersistentDataType.INTEGER)!!)
                }
                every { AEAPI.getEnchantLore("lightweight", 3) } returns listOf("§aЛёгкий III")
                val owner = NamespacedKey("elitemobs", "soulbind")
                val price = NamespacedKey("elitemobs", "itemvalue")
                val item = ItemStack(Material.TRIDENT).apply {
                    editMeta {
                        it.lore(listOf("Уровень: 15", "", "Описание", "", "", "Эффекты", "Слабость 2", "").map(Component::text))
                        it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 3)
                        it.persistentDataContainer.set(owner, PersistentDataType.STRING, "owner")
                        it.persistentDataContainer.set(price, PersistentDataType.DOUBLE, 206.4)
                        it.tooltipStyle = NamespacedKey("lzblocks", "tooltip/rare")
                        it.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
                    }
                }
                val output = EquipmentEnchantmentPresentation.advanced(item, "Эффекты")
                assertEquals(listOf("Уровень: 15", "", "Описание", "", "", "Дополнительные чары", "Лёгкий III", "", "Эффекты", "Слабость 2", ""),
                    output.lore()!!.map(plain::serialize))
                assertEquals(output, EquipmentEnchantmentPresentation.advanced(output, "Эффекты"))
                assertEquals(item.itemMeta.itemFlags, output.itemMeta.itemFlags)
                assertEquals(item.itemMeta.tooltipStyle, output.itemMeta.tooltipStyle)
                assertEquals("owner", output.itemMeta.persistentDataContainer.get(owner, PersistentDataType.STRING))
                assertEquals(206.4, output.itemMeta.persistentDataContainer.get(price, PersistentDataType.DOUBLE))
                assertEquals(3, output.itemMeta.persistentDataContainer.get(aeKey, PersistentDataType.INTEGER))
                assertFalse(item.lore()!!.any { plain.serialize(it) == "Лёгкий III" })
            } finally { unmockkStatic(AEAPI::class) }
        }
    }

    @Test
    fun `does not restyle stackable items or foreign hidden equipment or fabricate absent AE enchants`() {
        MockBukkitTestRuntime.open().use {
            val stacked = ItemStack(Material.DIAMOND, 2).apply { addUnsafeEnchantment(Enchantment.MENDING, 1) }
            val hidden = ItemStack(Material.DIAMOND_SWORD).apply {
                addUnsafeEnchantment(Enchantment.SHARPNESS, 5)
                editMeta { it.addItemFlags(ItemFlag.HIDE_ENCHANTS) }
            }
            val nativeElite = ItemStack(Material.TRIDENT).apply { editMeta { it.lore(listOf(Component.text("Эффекты"))) } }
            assertSame(stacked, EquipmentEnchantmentPresentation.ordinary(stacked))
            assertSame(hidden, EquipmentEnchantmentPresentation.ordinary(hidden))
            assertSame(nativeElite, EquipmentEnchantmentPresentation.advanced(nativeElite, "Эффекты"))
            nativeElite.editMeta { it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 3) }
            assertSame(nativeElite, EquipmentEnchantmentPresentation.advanced(nativeElite, "Эффекты"))
        }
    }
}
