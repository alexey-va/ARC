package ru.arc.enchanting

import com.magmaguy.elitemobs.MetadataHandler
import com.magmaguy.elitemobs.items.EliteEnchantmentCatalog
import com.magmaguy.elitemobs.items.ItemTagger
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import com.magmaguy.elitemobs.magmacore.enchantments.EnchantmentDefinition
import com.magmaguy.elitemobs.magmacore.enchantments.EnchantmentDefinitions
import com.magmaguy.elitemobs.magmacore.enchantments.EnchantmentItems
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteGrindstoneControllerTest : StringSpec({
    "native and elite mirrors are removed without changing identity, curses, skin or damage" {
        MockBukkitTestRuntime.open().use {
            withElitePlugin {
                val source = eliteSword().apply {
                    addUnsafeEnchantment(Enchantment.SHARPNESS, 2)
                    addUnsafeEnchantment(Enchantment.UNBREAKING, 2)
                    addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1)
                    editMeta { meta ->
                        meta.displayName(Component.text("Посох Проповедника"))
                        meta.setCustomModelData(1955)
                        (meta as Damageable).damage = 37
                        meta.persistentDataContainer.set(Enchantment.SHARPNESS.key, PersistentDataType.INTEGER, 2)
                        meta.persistentDataContainer.set(Enchantment.VANISHING_CURSE.key, PersistentDataType.INTEGER, 1)
                        meta.persistentDataContainer.set(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING, "owner")
                        meta.persistentDataContainer.set(NamespacedKey("elitemobs", "custom_item_id"), PersistentDataType.STRING, "preacherstaff.yml")
                        meta.persistentDataContainer.set(NamespacedKey("elitemobs", "elitedamage"), PersistentDataType.DOUBLE, 7.5)
                        meta.persistentDataContainer.set(NamespacedKey("elitemobs", "itemsource"), PersistentDataType.STRING, "church")
                        meta.persistentDataContainer.set(NamespacedKey("arc", "elite_skin"), PersistentDataType.STRING, "weapons:staff")
                    }
                }
                val original = source.clone()
                val result = removeEliteGrindstoneEnchantments(source)!!
                val expected = source.clone().apply {
                    removeEnchantment(Enchantment.SHARPNESS)
                    removeEnchantment(Enchantment.UNBREAKING)
                    editMeta { it.persistentDataContainer.remove(Enchantment.SHARPNESS.key) }
                }
                result shouldBe expected
                source shouldBe original
                ItemTagger.getEnchantment(result.itemMeta, Enchantment.SHARPNESS.key) shouldBe 0
                removeEliteGrindstoneEnchantments(result) shouldBe null
            }
        }
    }

    "a PDC-only enchantment is meaningful and a clean item is not" {
        MockBukkitTestRuntime.open().use {
            withElitePlugin {
                val source = eliteSword().apply {
                    editMeta { it.persistentDataContainer.set(Enchantment.SMITE.key, PersistentDataType.INTEGER, 9) }
                }
                source.enchantments shouldBe emptyMap()
                val result = removeEliteGrindstoneEnchantments(source)!!
                result.itemMeta.persistentDataContainer.has(Enchantment.SMITE.key) shouldBe false
                removeEliteGrindstoneEnchantments(result) shouldBe null
            }
        }
    }

    "native custom API removes noncurses, preserves custom curses and owns the new lore" {
        MockBukkitTestRuntime.open().use {
            withElitePlugin {
                withCustomDefinitions {
                    val source = eliteSword()
                    writeCustomFixture(source, mapOf("elitemobs:test_boost" to 2, "elitemobs:test_curse" to 1))
                    val original = source.clone()
                    val result = removeEliteGrindstoneEnchantments(source)!!
                    EliteEnchantmentItems.custom(result) shouldBe mapOf("elitemobs:test_curse" to 1)
                    result.itemMeta.lore.orEmpty().any { "test_curse" in it } shouldBe true
                    result.itemMeta.lore.orEmpty().any { "test_boost" in it } shouldBe false
                    source shouldBe original
                    removeEliteGrindstoneEnchantments(result) shouldBe null

                    val onlyCustom = eliteSword()
                    writeCustomFixture(onlyCustom, mapOf("elitemobs:test_boost" to 1))
                    val clean = removeEliteGrindstoneEnchantments(onlyCustom)!!
                    EliteEnchantmentItems.custom(clean) shouldBe emptyMap()
                    removeEliteGrindstoneEnchantments(clean) shouldBe null
                }
            }
        }
    }

    "books, scrolls and invalid native levels are not turned into outputs" {
        MockBukkitTestRuntime.open().use {
            withElitePlugin {
                val book = eliteSword().apply { type = Material.ENCHANTED_BOOK }
                removeEliteGrindstoneEnchantments(book) shouldBe null
                val scroll = eliteSword().apply {
                    addUnsafeEnchantment(Enchantment.SHARPNESS, 1)
                    editMeta { it.persistentDataContainer.set(NamespacedKey("elitemobs", "elite_scroll"), PersistentDataType.INTEGER, 1) }
                }
                removeEliteGrindstoneEnchantments(scroll) shouldBe null
                val malformed = eliteSword().apply {
                    editMeta { it.persistentDataContainer.set(Enchantment.SHARPNESS.key, PersistentDataType.INTEGER, -1) }
                }
                shouldThrow<IllegalArgumentException> { removeEliteGrindstoneEnchantments(malformed) }
            }
        }
    }

    "either single input produces a result even when vanilla supplied none, and clean inputs do not" {
        MockBukkitTestRuntime.open().use { runtime ->
            withElitePlugin {
                val player = runtime.addPlayer("GrindstonePreview")
                val source = eliteSword().apply {
                    editMeta { it.persistentDataContainer.set(Enchantment.SHARPNESS.key, PersistentDataType.INTEGER, 2) }
                }
                val controller = EliteGrindstoneController { item, _ -> removeEliteGrindstoneEnchantments(item) }
                val clean = removeEliteGrindstoneEnchantments(source)!!
                for (slot in 0..1) {
                    val inventory = grindstone(source.takeIf { slot == 0 }, source.takeIf { slot == 1 })
                    val event = PrepareGrindstoneEvent(view(inventory, player), null)
                    controller.onPrepare(event)
                    event.result shouldBe clean
                }
                val event = PrepareGrindstoneEvent(view(grindstone(clean), player), clean)
                controller.onPrepare(event)
                event.result shouldBe null
                for (inventory in listOf(
                    grindstone(source, ItemStack(Material.DIAMOND_SWORD)),
                    grindstone(ItemStack(Material.DIAMOND_SWORD), source),
                )) {
                    val merged = PrepareGrindstoneEvent(view(inventory, player), clean)
                    controller.onPrepare(merged)
                    merged.result shouldBe null
                }
            }
        }
    }

    "result guard runs before skill XP and leaves valid normal and shift clicks to the server" {
        MockBukkitTestRuntime.open().use { runtime ->
            withElitePlugin {
                val player = runtime.addPlayer("GrindstoneTake")
                val source = eliteSword().apply { addUnsafeEnchantment(Enchantment.UNBREAKING, 2) }
                val result = removeEliteGrindstoneEnchantments(source)!!
                val controller = EliteGrindstoneController { item, _ -> removeEliteGrindstoneEnchantments(item) }
                val priority = EliteGrindstoneController::class.java
                    .getMethod("onTake", InventoryClickEvent::class.java).getAnnotation(EventHandler::class.java)
                priority.priority shouldBe EventPriority.LOWEST
                priority.ignoreCancelled shouldBe true
                val preparedInventory = grindstone(source)
                controller.onPrepare(PrepareGrindstoneEvent(view(preparedInventory, player), null))
                for (click in listOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT)) {
                    val event = click(preparedInventory, player, result, click)
                    controller.onTake(event)
                    verify(exactly = 0) { event.isCancelled = any() }
                }
                for ((inventory, output) in listOf(
                    grindstone(source) to null,
                    grindstone(source) to source,
                    grindstone(source, ItemStack(Material.DIAMOND_SWORD)) to result,
                    grindstone(ItemStack(Material.DIAMOND_SWORD), source) to result,
                    grindstone(result) to result,
                )) {
                    val event = click(inventory, player, output)
                    controller.onTake(event)
                    verify(exactly = 1) { event.isCancelled = true }
                }
                val ordinary = ItemStack(Material.DIAMOND_SWORD)
                val vanillaInventory = grindstone(ordinary)
                controller.onPrepare(PrepareGrindstoneEvent(view(vanillaInventory, player), ordinary))
                val vanilla = click(vanillaInventory, player, ordinary)
                controller.onTake(vanilla)
                verify(exactly = 0) { vanilla.isCancelled = any() }
            }
        }
    }

    "take uses the exact prepared result and rejects changed input, output or inventory sessions" {
        MockBukkitTestRuntime.open().use { runtime ->
            withElitePlugin {
                val player = runtime.addPlayer("GrindstoneSession")
                val source = eliteSword().apply { addUnsafeEnchantment(Enchantment.UNBREAKING, 2) }
                var currentSource: ItemStack? = source
                val inventory = grindstone(source)
                every { inventory.getItem(0) } answers { currentSource }
                var preparations = 0
                val controller = EliteGrindstoneController { item, _ ->
                    removeEliteGrindstoneEnchantments(item)?.apply {
                        preparations++
                        editMeta { it.displayName(Component.text("Migrated spell $preparations")) }
                    }
                }
                val preview = PrepareGrindstoneEvent(view(inventory, player), null)
                controller.onPrepare(preview)
                val output = preview.result!!
                val valid = click(inventory, player, output)
                controller.onTake(valid)
                verify(exactly = 0) { valid.isCancelled = any() }
                preparations shouldBe 1

                currentSource = source.clone().apply { amount = 2 }
                val changedInput = click(inventory, player, output)
                controller.onTake(changedInput)
                verify(exactly = 1) { changedInput.isCancelled = true }
                currentSource = source
                val changedOutput = click(inventory, player, output.clone().apply { amount = 2 })
                controller.onTake(changedOutput)
                verify(exactly = 1) { changedOutput.isCancelled = true }
                val otherSession = click(grindstone(source), player, output)
                controller.onTake(otherSession)
                verify(exactly = 1) { otherSession.isCancelled = true }
                val missingPreview = click(inventory, player, output)
                EliteGrindstoneController { _, _ -> error("A click must not prepare an item") }.onTake(missingPreview)
                verify(exactly = 1) { missingPreview.isCancelled = true }

                controller.onClose(InventoryCloseEvent(view(inventory, player)))
                val closedSession = click(inventory, player, output)
                controller.onTake(closedSession)
                verify(exactly = 1) { closedSession.isCancelled = true }
                preparations shouldBe 1

                controller.onPrepare(PrepareGrindstoneEvent(view(inventory, player), null))
                currentSource = ItemStack(Material.DIAMOND_SWORD)
                controller.onPrepare(PrepareGrindstoneEvent(view(inventory, player), currentSource))
                currentSource = source
                val clearedPreview = click(inventory, player, output)
                controller.onTake(clearedPreview)
                verify(exactly = 1) { clearedPreview.isCancelled = true }
                preparations shouldBe 2
            }
        }
    }
})

private fun withElitePlugin(block: () -> Unit) {
    val previous = MetadataHandler.PLUGIN
    MetadataHandler.PLUGIN = mockk<JavaPlugin> {
        every { name } returns "EliteMobs"
        every { namespace() } returns "elitemobs"
    }
    // MockBukkit omits the legacy CustomItemTagContainer checked before modern PDC by EM.
    // Fixtures use only the modern marker; enchantment reads, writes and custom API stay native.
    mockkStatic(ItemTagger::class)
    try {
        every { ItemTagger.isEliteItem(any()) } answers {
            firstArg<ItemStack?>()?.itemMeta?.persistentDataContainer
                ?.has(NamespacedKey("elitemobs", "elitemobsitem"), PersistentDataType.BYTE) == true
        }
        block()
    } finally {
        unmockkStatic(ItemTagger::class)
        MetadataHandler.PLUGIN = previous
    }
}

private fun eliteSword(): ItemStack = ItemStack(Material.DIAMOND_SWORD).apply {
    editMeta { it.persistentDataContainer.set(NamespacedKey("elitemobs", "elitemobsitem"), PersistentDataType.BYTE, 1) }
}

private fun grindstone(upper: ItemStack?, lower: ItemStack? = null): GrindstoneInventory = mockk {
    every { type } returns InventoryType.GRINDSTONE
    every { getItem(0) } returns upper
    every { getItem(1) } returns lower
}

private fun view(inventory: GrindstoneInventory, player: org.bukkit.entity.Player): InventoryView = mockk {
    every { topInventory } returns inventory
    every { this@mockk.player } returns player
}

private fun click(inventory: GrindstoneInventory, player: org.bukkit.entity.Player, output: ItemStack?, type: ClickType = ClickType.LEFT): InventoryClickEvent = mockk(relaxed = true) {
    every { this@mockk.inventory } returns inventory
    every { whoClicked } returns player
    every { rawSlot } returns 2
    every { currentItem } returns output
    every { click } returns type
}

private fun withCustomDefinitions(block: () -> Unit) {
    val definitions = listOf("elitemobs:test_boost", "elitemobs:test_curse").associateWith { id ->
        mockk<EnchantmentDefinition>(relaxed = true) {
            every { id() } returns id
            every { name() } returns id.substringAfter(':')
            every { maxLevel() } returns 3
            every { curse() } returns id.endsWith("curse")
        }
    }
    val resolved = definitions.mapValues { (_, definition) ->
        mockk<EnchantmentItems.Resolved> {
            every { available() } returns true
            every { definition() } returns definition
        }
    }
    mockkStatic(EliteEnchantmentCatalog::class, EnchantmentDefinitions::class)
    try {
        every { EliteEnchantmentCatalog.definition(any()) } answers { definitions[firstArg<String>()] }
        every { EnchantmentDefinitions.resolve(any()) } answers { resolved[firstArg<String>()] }
        block()
    } finally { unmockkStatic(EliteEnchantmentCatalog::class, EnchantmentDefinitions::class) }
}

private fun writeCustomFixture(item: ItemStack, levels: Map<String, Int>) {
    fun key(value: String) = NamespacedKey("magmacore", value)
    item.editMeta { meta ->
        val context = meta.persistentDataContainer.adapterContext
        val root = context.newPersistentDataContainer()
        root.set(key("version"), PersistentDataType.INTEGER, 1)
        root.set(key("entries"), PersistentDataType.TAG_CONTAINER_ARRAY, levels.map { (id, level) ->
            context.newPersistentDataContainer().also {
                it.set(key("id"), PersistentDataType.STRING, id)
                it.set(key("level"), PersistentDataType.INTEGER, level)
            }
        }.toTypedArray())
        meta.persistentDataContainer.set(key("enchantments"), PersistentDataType.TAG_CONTAINER, root)
    }
}
