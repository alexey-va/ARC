package ru.arc.enchanting

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.advancedplugins.ae.api.AEAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
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
    fun `counter icon has an empty server fallback and keeps the existing row after AE updates`() {
        MockBukkitTestRuntime.open().use {
            val killsKey = NamespacedKey("advancedenchantments", "stattrak")
            val initial = Component.text("Убийств: 0", NamedTextColor.YELLOW)
            val authored = Component.text("Мобов: 13", NamedTextColor.AQUA)
            val item = ItemStack(Material.NETHERITE_SWORD).apply {
                editMeta {
                    it.lore(listOf(initial, authored))
                    it.persistentDataContainer.set(killsKey, PersistentDataType.STRING, "0")
                }
            }
            fun render(input: ItemStack) =
                EquipmentEnchantmentPresentation.render(input, emptyList(), emptyList(), true, null)

            val decorated = render(item)
            val row = decorated.lore()!![1]
            val icon = row.children().first() as TranslatableComponent
            assertEquals("ruscrafting.item_lore.icon", icon.key())
            assertEquals("", icon.fallback())
            assertEquals("", plain.serialize(icon.args().single()))
            assertEquals(initial, row.children().last())
            assertEquals(authored, decorated.lore()!!.last())
            assertEquals(decorated, render(decorated))

            val updated = Component.text("Убийств: 1", NamedTextColor.YELLOW)
            decorated.editMeta {
                it.lore(listOf(updated, authored))
                it.persistentDataContainer.set(killsKey, PersistentDataType.STRING, "1")
            }
            assertEquals(updated, render(decorated).lore()!![1].children().last())
        }
    }

    @Test
    fun `old items group statistics once and keep native enchant strings through upgrade and removal`() {
        MockBukkitTestRuntime.open().use {
            val legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
            val kills = NamespacedKey("advancedenchantments", "stattrak")
            val mobs = NamespacedKey("advancedenchantments", "mobtrak")
            val oldEnchant = "§fЛегкий III"
            val nativeEnchant = "§f Легкий III"
            val nextEnchant = "§f Легкий IV"
            val poison = "§f Отрава II"
            val authorLine = "§7Надпись мастера"
            val item = ItemStack(Material.NETHERITE_SWORD).apply {
                editMeta {
                    it.setLore(listOf(oldEnchant, "§3Мобов: §f12", authorLine, "§eУбийств: §60"))
                    it.persistentDataContainer.set(kills, PersistentDataType.STRING, "0")
                    it.persistentDataContainer.set(mobs, PersistentDataType.STRING, "12")
                }
            }
            fun render(input: ItemStack, enchants: List<String>, icons: Map<Int, String>, migrate: Boolean = false) =
                EquipmentEnchantmentPresentation.render(input, emptyList(), enchants.map(legacy::deserialize), true, null,
                    legacyDisplays = if (migrate) mapOf(nativeEnchant to oldEnchant) else emptyMap(), iconOverrides = icons)
            val heading = "Состояние предмета"

            val decorated = render(item, listOf(nativeEnchant), mapOf(0 to ""), migrate = true)
            assertEquals(1, decorated.itemMeta.getLore()!!.count { it == nativeEnchant })
            assertFalse(oldEnchant in decorated.itemMeta.getLore()!!)
            val enchantRow = decorated.lore()!!.single { plain.serialize(it) == " Легкий III" }
            val prefix = (enchantRow as? TranslatableComponent) ?: enchantRow.children().first() as TranslatableComponent
            assertEquals("ruscrafting.item_lore.enchantment_icon", prefix.key())
            assertEquals("", plain.serialize(prefix.args().first()))
            assertEquals(listOf(heading, "Убийств: 0", "Мобов: 12", "Надпись мастера"),
                decorated.lore()!!.takeLast(4).map(plain::serialize))
            assertSame(decorated, render(decorated, listOf(nativeEnchant), mapOf(0 to ""), migrate = true))

            val changed = decorated.clone().apply {
                editMeta { meta ->
                    // AE copies legacy rows, changes one level, adds a spell, then appends its updated counter.
                    val body = meta.getLore()!!.filterNot { it == nativeEnchant || it.contains("Мобов: ") }
                    meta.setLore(listOf(poison, nextEnchant) + body + "§3Мобов: §f13")
                    meta.persistentDataContainer.set(mobs, PersistentDataType.STRING, "13")
                }
            }
            val updated = render(changed, listOf(nextEnchant, poison), mapOf(0 to "", 1 to ""))
            val text = updated.lore()!!.map(plain::serialize)
            assertEquals(1, text.count { it == heading })
            assertEquals(1, text.count { it == "Мобов: 13" })
            assertTrue(updated.itemMeta.getLore()!!.containsAll(listOf(nextEnchant, poison)))
            assertSame(updated, render(updated, listOf(nextEnchant, poison), mapOf(0 to "", 1 to "")))

            updated.editMeta { meta ->
                meta.setLore(meta.getLore()!!.filterNot { it.contains("Убийств:") || it.contains("Мобов:") })
                meta.persistentDataContainer.remove(kills)
                meta.persistentDataContainer.remove(mobs)
            }
            val cleaned = render(updated, listOf(poison), mapOf(0 to ""))
            assertFalse(heading in cleaned.lore()!!.map(plain::serialize))
            assertTrue(authorLine in cleaned.itemMeta.getLore()!!)
            assertFalse(nextEnchant in cleaned.itemMeta.getLore()!!)
            assertTrue(poison in cleaned.itemMeta.getLore()!!)
        }
    }

    @Test
    fun `native display icon migrates an existing AE row once and preserves its component and unrelated lore`() {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("AdvancedEnchantments")
            Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
            mockkStatic(AEAPI::class)
            try {
                every { AEAPI.getEnchantmentsOnItem(any()) } returns hashMapOf("lightweight" to 3)
                every { AEAPI.getEnchantLore("lightweight", 3) } returns listOf("§f §aЛегкий III", "§7Ускоряет атаку.")
                val original = Component.text("Легкий III", NamedTextColor.GREEN)
                    .append(Component.text(" [метка]", NamedTextColor.AQUA))
                    .hoverEvent(Component.text("Подсказка"))
                    .decoration(TextDecoration.ITALIC, false)
                val body = listOf(Component.text("Легкий III — цитата"), Component.empty(), Component.text("Мобов: 2540"))
                val authoredSameName = Component.text("Легкий III", NamedTextColor.GRAY)
                val item = ItemStack(Material.NETHERITE_SWORD).apply {
                    editMeta {
                        it.lore(listOf(authoredSameName, original, Component.text("Ускоряет атаку.", NamedTextColor.GRAY)) + body)
                        it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 3)
                    }
                }
                val output = EquipmentEnchantmentPresentation.ordinary(item)
                val expected = Component.text(" ", NamedTextColor.WHITE).append(original)
                    .decoration(TextDecoration.ITALIC, false)
                assertTrue(output.lore()!!.contains(expected))
                assertTrue(output.lore()!!.contains(authoredSameName))
                assertEquals(body, output.lore()!!.takeLast(body.size))
                assertEquals(1, output.lore()!!.count { plain.serialize(it) == "Ускоряет атаку." })
                assertEquals(3, output.itemMeta.persistentDataContainer.get(aeKey, PersistentDataType.INTEGER))
                assertEquals(output, EquipmentEnchantmentPresentation.ordinary(output))
                assertEquals(original, item.lore()!![1])
            } finally { unmockkStatic(AEAPI::class) }
        }
    }

    @Test
    fun `first refresh after native upgrade removes only the exact old AE name style and valid Roman level`() {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("AdvancedEnchantments")
            Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
            mockkStatic(AEAPI::class)
            try {
                every { AEAPI.getEnchantmentsOnItem(any()) } returns hashMapOf("lightweight" to 4)
                every { AEAPI.getEnchantLore("lightweight", 4) } returns listOf("§f §aЛегкий IV")
                val authored = listOf(
                    Component.text("Легкий III — история", NamedTextColor.GREEN),
                    Component.text("Легкий III", NamedTextColor.GRAY),
                    Component.text("Легкий IIV", NamedTextColor.GREEN),
                    Component.text("Легкий iii", NamedTextColor.GREEN),
                    Component.text("легкий III", NamedTextColor.GREEN),
                )
                val item = ItemStack(Material.NETHERITE_SWORD).apply {
                    editMeta {
                        it.lore(listOf(Component.text(" ", NamedTextColor.WHITE).append(Component.text("Легкий IV", NamedTextColor.GREEN)),
                            Component.text("Легкий III", NamedTextColor.GREEN)) + authored)
                        it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 4)
                    }
                }
                val output = EquipmentEnchantmentPresentation.ordinary(item)
                assertEquals(authored, output.lore()!!.takeLast(authored.size))
                assertEquals(1, output.lore()!!.count { plain.serialize(it) == " Легкий IV" })
                assertFalse(output.lore()!!.contains(Component.text("Легкий III", NamedTextColor.GREEN)))
                assertEquals(output, EquipmentEnchantmentPresentation.ordinary(output))
                assertEquals(4, output.itemMeta.persistentDataContainer.get(aeKey, PersistentDataType.INTEGER))
            } finally { unmockkStatic(AEAPI::class) }
        }
    }

    @Test
    fun `icon migration requires the current PDC display and does not reinterpret descriptions or default names`() {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("AdvancedEnchantments")
            Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
            mockkStatic(AEAPI::class)
            try {
                every { AEAPI.getEnchantmentsOnItem(any()) } returns hashMapOf("lightweight" to 3)
                every { AEAPI.getEnchantLore("lightweight", 3) } returns listOf("§aЛегкий III", "§f §7Справка II")
                val authored = Component.text("Справка II", NamedTextColor.GRAY)
                val item = ItemStack(Material.NETHERITE_SWORD).apply {
                    editMeta {
                        it.lore(listOf(Component.text("Легкий III", NamedTextColor.GREEN), authored))
                        it.persistentDataContainer.set(aeKey, PersistentDataType.INTEGER, 3)
                    }
                }
                val output = EquipmentEnchantmentPresentation.ordinary(item)
                assertEquals(1, output.lore()!!.count { plain.serialize(it) == "Легкий III" })
                assertTrue(output.lore()!!.contains(authored))
                assertTrue(output.lore()!!.any { plain.serialize(it) == " Справка II" })
                every { AEAPI.getEnchantmentsOnItem(any()) } returns hashMapOf()
                val removedBeforeRefresh = item.clone().apply { editMeta { it.persistentDataContainer.remove(aeKey) } }
                // With no PDC or previous ARC ownership an old row is indistinguishable from authored text.
                assertEquals(removedBeforeRefresh, EquipmentEnchantmentPresentation.ordinary(removedBeforeRefresh))
            } finally { unmockkStatic(AEAPI::class) }
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
