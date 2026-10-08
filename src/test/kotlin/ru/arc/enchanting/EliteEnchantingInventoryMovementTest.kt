package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.eliteloot.isEliteEnchantmentBook
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteEnchantingInventoryMovementTest : StringSpec({
    "moving an EliteMobs book to empty slots or another book preserves ordinary inventory handling" {
        MockBukkitTestRuntime.open().use { runtime ->
            ru.arc.core.Tasks.install(ru.arc.core.TestTaskScheduler())
            val player = runtime.addPlayer("Enchanter")
            val book = ItemStack(Material.ENCHANTED_BOOK)
            mockkStatic("ru.arc.eliteloot.EliteEnchantmentBookPresentationKt")
            try {
                every { isEliteEnchantmentBook(book) } returns true
                val config = mockk<EnchantingConfig>()
                val controller = EliteEnchantingController(config)
                for (target in listOf(null, ItemStack(Material.AIR), ItemStack(Material.ENCHANTED_BOOK))) {
                    for (click in listOf(ClickType.LEFT, ClickType.RIGHT)) {
                        val event = mockk<InventoryClickEvent>(relaxed = true)
                        every { event.whoClicked } returns player
                        every { event.clickedInventory } returns player.inventory
                        every { event.cursor } returns book
                        every { event.currentItem } returns target
                        every { event.click } returns click
                        controller.onBookDrop(event)
                        verify(exactly = 0) { event.isCancelled = any() }
                    }
                }
                verify { config wasNot Called }
            } finally {
                unmockkStatic("ru.arc.eliteloot.EliteEnchantmentBookPresentationKt")
                ru.arc.core.Tasks.reset()
            }
        }
    }
})
