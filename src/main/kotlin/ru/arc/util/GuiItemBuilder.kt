package ru.arc.util

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import java.util.function.BiConsumer
import java.util.function.Consumer

class GuiItemBuilder(private var stack: ItemStack) {

    private var clickEvent: Consumer<InventoryClickEvent>? = null

    fun clickEvent(eventConsumer: Consumer<InventoryClickEvent>): GuiItemBuilder {
        this.clickEvent = eventConsumer
        return this
    }

    fun clickEventWithStack(eventConsumer: BiConsumer<InventoryClickEvent, ItemStack>): GuiItemBuilder {
        this.clickEvent = Consumer { event -> eventConsumer.accept(event, stack) }
        return this
    }

    fun stack(stack: ItemStack): GuiItemBuilder {
        this.stack = stack
        return this
    }

    fun build(): GuiItem {
        return GuiItem(stack, clickEvent)
    }
}

