package ru.arc.bschests;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.arc.ARC;

public class LootGui extends ChestGui {

    Player player;
    CustomLootData customLootData;
    StaticPane pane;
    int rows;

    public LootGui(Player player, CustomLootData customLootData) {
        super(1, "Лут данжа");
        rows = Math.max(1, Math.min(6, customLootData.items.size() / 9));
        setRows(rows);
        this.player = player;
        this.customLootData = customLootData;
        fill();
    }

    private void fill() {
        pane = new StaticPane(0, 0, 9, rows);
        for (int i = 0; i < customLootData.items.size(); i++) {
            ItemStack item = customLootData.items.get(i);
            if (item != null) {
                int finalI = i;
                pane.addItem(new GuiItem(item.clone(), click -> {
                    ItemStack currentItem = click.getCurrentItem();
                    if (currentItem != null) {
                        removeItem(item, finalI);
                    }
                    ItemMeta itemMeta = currentItem.getItemMeta();
                    itemMeta.getPersistentDataContainer().remove(new NamespacedKey(ARC.plugin, "if-uuid"));
                    currentItem.setItemMeta(itemMeta);
                }), i % 9, i / 9);
            }
        }
        this.addPane(pane);


        this.setOnTopClick(click -> {
            switch (click.getAction()) {
                case PLACE_ONE, PLACE_SOME, PLACE_ALL, SWAP_WITH_CURSOR -> click.setCancelled(true);
            }

        });
        this.setOnTopDrag(drag -> drag.setCancelled(true));
        this.setOnBottomClick(click -> {
            switch (click.getAction()) {
                case MOVE_TO_OTHER_INVENTORY -> click.setCancelled(true);
            }
        });
    }

    private void removeItem(ItemStack item, int slot) {
        customLootData.removeItem(item, slot);
    }
}
