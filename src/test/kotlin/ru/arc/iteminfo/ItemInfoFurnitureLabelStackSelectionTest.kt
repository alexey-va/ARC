package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class ItemInfoFurnitureLabelStackSelectionTest : StringSpec({
    "current ItemsAdder furniture definition supplies the renamed label" {
        val savedStack = stackWithDisplayName("Old chair")
        val currentStack = stackWithDisplayName("Renamed chair")

        val selected = ItemInfoFurnitureLabelStackSelection.currentOrSaved(savedStack) { currentStack }

        selected?.itemMeta?.displayName() shouldBe Component.text("Renamed chair")
    }

    "missing or failing current definition lookup falls back to the saved furniture label" {
        val savedStack = stackWithDisplayName("Saved chair")

        val missingDefinition = ItemInfoFurnitureLabelStackSelection.currentOrSaved(savedStack) { null }
        val failedLookup = ItemInfoFurnitureLabelStackSelection.currentOrSaved(savedStack) {
            error("ItemsAdder lookup failed")
        }

        missingDefinition?.itemMeta?.displayName() shouldBe Component.text("Saved chair")
        failedLookup?.itemMeta?.displayName() shouldBe Component.text("Saved chair")
    }
})

private fun stackWithDisplayName(name: String): ItemStack {
    val stack = mockk<ItemStack>()
    val meta = mockk<ItemMeta>()
    every { stack.itemMeta } returns meta
    every { meta.hasDisplayName() } returns true
    every { meta.displayName() } returns Component.text(name)
    return stack
}
