package ru.arc.util;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class GuiItemBuilder {

    Consumer<InventoryClickEvent> clickEvent;
    ItemStack stack;

    public GuiItemBuilder(ItemStack stack){
        this.stack = stack;
    }

    public GuiItemBuilder clickEvent(Consumer<InventoryClickEvent> eventConsumer) {
        this.clickEvent = eventConsumer;
        return this;
    }

    public GuiItemBuilder clickEventWithStack(BiConsumer<InventoryClickEvent, ItemStack> eventConsumer) {
        this.clickEvent = event -> eventConsumer.accept(event, stack);
        return this;
    }

    public GuiItemBuilder stack(ItemStack stack){
        this.stack = stack;
        return this;
    }

    public GuiItem build() {
        return new GuiItem(stack, clickEvent);
    }


}
