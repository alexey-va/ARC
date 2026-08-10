package ru.arc.misc

import io.kotest.core.spec.style.FreeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.InventoryView

class StoreCursorSyncTest : FreeSpec({
    "clears the cursor synchronously for a cancelled store click" {
        val view = mockk<InventoryView>(relaxed = true)
        val click = mockk<InventoryClickEvent>()
        every { click.isCancelled } returns true
        every { click.view } returns view

        commitCancelledStoreCursor(click, null)

        verify(exactly = 1) { view.setCursor(null) }
    }
})
