package arc.arc.bschests;

import arc.arc.ARC;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

@Slf4j
public class LootGui extends ChestGui {

    Player player;
    CustomLootData customLootData;
    StaticPane pane;

    public LootGui(Player player, CustomLootData customLootData) {
        super(Math.min(6, customLootData.items.size() / 9), "Лут данжа");
        this.player = player;
        this.customLootData = customLootData;
        fill();
    }

    private void fill() {
        pane = new StaticPane(0, 0, 9, customLootData.items.size() / 9);
        for (int i = 0; i < customLootData.items.size(); i++) {
            ItemStack item = customLootData.items.get(i);
            if (item != null) {
                pane.addItem(new GuiItem(item.clone(), click -> {
                    ItemStack currentItem = click.getCurrentItem();
                    if (currentItem != null) {
                        removeItem(item);
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

    private void removeItem(ItemStack item) {
        customLootData.removeItem(item);
    }
}
