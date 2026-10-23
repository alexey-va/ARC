package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Crafter
import org.bukkit.block.Block
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class StaffCraftingTest : FreeSpec({
    "all staff markers block preview and ordinary or shift-click crafting in both crafting grids" {
        MockBukkitTestRuntime.open().use { paper ->
            val input = StaffSpellInput(mockk(relaxed = true), LifecycleTaskScope(TestTaskScheduler()))
            val player = paper.server.addPlayer()
            paper.server.pluginManager.registerEvents(input, paper.createSimplePlugin("staff-craft-protection"))
            val recipe = powderRecipe()
            try {
                for (type in listOf(InventoryType.CRAFTING, InventoryType.WORKBENCH)) {
                    val matrix = arrayOfNulls<ItemStack>(if (type == InventoryType.CRAFTING) 4 else 9)
                    var result: ItemStack? = null
                    val inventory = mockk<CraftingInventory>(relaxed = true) {
                        every { getMatrix() } returns matrix
                        every { getResult() } answers { result }
                        every { setResult(any()) } answers { result = firstArg() }
                    }
                    val view = mockk<InventoryView>(relaxed = true) {
                        every { topInventory } returns inventory
                        every { bottomInventory } returns player.inventory
                        every { getPlayer() } returns player
                        every { convertSlot(any()) } answers { firstArg() }
                    }
                    for ((ingredient, protected) in craftingIngredients()) {
                        matrix[matrix.lastIndex] = ingredient.clone()
                        inventory.result = recipe.result
                        paper.server.pluginManager.callEvent(PrepareItemCraftEvent(inventory, view, false))
                        inventory.result?.type shouldBe if (protected) null else Material.BLAZE_POWDER

                        for (click in listOf(ClickType.LEFT, ClickType.SHIFT_LEFT)) {
                            // Recheck the final mutation even if another listener restores a preview.
                            inventory.result = recipe.result
                            val event = CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, 0,
                                click, if (click.isShiftClick) InventoryAction.MOVE_TO_OTHER_INVENTORY
                                else InventoryAction.PICKUP_ALL)
                            paper.server.pluginManager.callEvent(event)
                            event.isCancelled shouldBe protected
                            inventory.result?.type shouldBe if (protected) null else Material.BLAZE_POWDER
                            inventory.matrix.last()?.isSimilar(ingredient) shouldBe true
                            inventory.matrix.last()?.amount shouldBe ingredient.amount
                        }
                    }
                }
            } finally { input.close() }
        }
    }

    "automatic crafters preserve staff ingredients while ordinary rods remain craftable" {
        MockBukkitTestRuntime.open().use { paper ->
            val input = StaffSpellInput(mockk(relaxed = true), LifecycleTaskScope(TestTaskScheduler()))
            paper.server.pluginManager.registerEvents(input, paper.createSimplePlugin("staff-auto-craft-protection"))
            val inventory = paper.server.createInventory(null, 9)
            val crafter = mockk<Crafter> { every { getInventory() } returns inventory }
            val block = mockk<Block> { every { state } returns crafter }
            try {
                for ((ingredient, protected) in craftingIngredients()) {
                    inventory.setItem(8, ingredient.clone())
                    val event = CrafterCraftEvent(block, powderRecipe(), ItemStack(Material.BLAZE_POWDER, 2))
                    paper.server.pluginManager.callEvent(event)
                    event.isCancelled shouldBe protected
                    inventory.getItem(8)?.isSimilar(ingredient) shouldBe true
                    inventory.getItem(8)?.amount shouldBe ingredient.amount
                }
            } finally { input.close() }
        }
    }
})

private fun powderRecipe() = ShapelessRecipe(NamespacedKey.minecraft("blaze_powder"),
    ItemStack(Material.BLAZE_POWDER, 2)).addIngredient(Material.BLAZE_ROD)

private fun staffIngredients(): List<ItemStack> = buildList {
    for (key in listOf(StaffSpell.itemKey, StaffEliteItems.itemKey)) {
        for (spell in StaffSpell.entries) add(ItemStack(Material.BLAZE_ROD).apply {
            editMeta { it.persistentDataContainer.set(key, PersistentDataType.STRING, spell.id) }
        })
    }
    // A stale/unknown assignment and duel-only prototype must not lose protection.
    add(ItemStack(Material.BLAZE_ROD).apply {
        editMeta { it.persistentDataContainer.set(StaffEliteItems.itemKey, PersistentDataType.STRING, "retired-id") }
    })
    add(ItemStack(Material.BLAZE_ROD).apply {
        editMeta {
            it.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, StaffSpell.CHAIN.id)
            it.persistentDataContainer.set(StaffSpell.duelOnlyKey, PersistentDataType.BYTE, 1)
        }
    })
}

private fun craftingIngredients(): List<Pair<ItemStack, Boolean>> =
    staffIngredients().map { it to true } + listOf(
        ItemStack(Material.BLAZE_ROD, 8) to false,
        ItemStack(Material.BLAZE_ROD).apply {
            editMeta { it.displayName(net.kyori.adventure.text.Component.text("Обычный стержень")) }
        } to false,
    )
