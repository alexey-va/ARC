package arc.arc.util;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static arc.arc.util.TextUtil.strip;

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

    public GuiItemBuilder stack(ItemStack stack){
        this.stack = stack;
        return this;
    }

    public GuiItem build() {
        return new GuiItem(stack, clickEvent);
    }


}
