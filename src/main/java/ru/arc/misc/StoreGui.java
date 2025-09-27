package ru.arc.misc;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.store.Store;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.arc.util.*;

import java.util.ArrayList;
import java.util.List;

import static ru.arc.util.Logging.info;

@Slf4j
public class StoreGui extends ChestGui {

    Store store;
    Player player;
    Config config;
    int rows;

    PaginatedPane paginatedPane;

    List<StoreGuiItem> items = new ArrayList<>();

    public StoreGui(Config config, Player player, Store store) {
        super(6, TextHolder.deserialize(
                TextUtil.toLegacy(config.string("store.title"))
        ));
        this.config = config;
        this.player = player;
        this.store = store;

        this.rows = (int) Math.min(6, Math.ceil((double) store.getSize() / 9) + 1);
        setRows(rows);

        setupBackground();
        setupNavigation();
        setupItems();
        setupListener();

        this.update();
    }

    private void setupListener() {
        this.setOnTopDrag(drag -> drag.setCancelled(true));

        this.setOnBottomClick(click -> {
            //click.setCancelled(true);
            if (!click.isShiftClick()) {
                info("B: Not shift click");
                return;
            }
            if (!store.hasSpace()) {
                info("B: No space");
                return;
            }
            if (click.getCurrentItem() == null) {
                info("B: No item");
                return;
            }
            click.setCancelled(true);

            //System.out.println("Processing bottom click");

            boolean success = store.addItem(click.getCurrentItem().clone());

            if (!success) {
                info("B: Not success");
                return;
            }
            click.setCurrentItem(null);

            setupItems();
            update();
        });

        this.setOnTopClick(click -> {

            ItemStack currentStoreItem = click.getCurrentItem();
            ItemStack cursor = click.getCursor();

            boolean hasCurrentItem = currentStoreItem != null && currentStoreItem.getType() != Material.AIR;
            boolean hasCursorItem = cursor.getType() != Material.AIR;
            boolean hasStoreSpace = store.hasSpace();

            if (hasCurrentItem) {
                info("T: Current item: " + currentStoreItem.getType());
                return;
            }
            if (!hasStoreSpace && hasCursorItem) {
                info("T: No space");
                return;
            }

            click.setCancelled(true);
            System.out.println("Processing top click");

            if (!hasCursorItem) {
                return;
            }

            boolean addedSuccess;
            addedSuccess = store.addItem(cursor.clone());


            if (addedSuccess) {
                info("T: Added success");
                if (currentStoreItem != null) {
                    ItemMeta itemMeta = currentStoreItem.getItemMeta();
                    itemMeta.getPersistentDataContainer().remove(new NamespacedKey(ARC.plugin, "if-uuid"));
                    currentStoreItem.setItemMeta(itemMeta);
                }
                click.setCursor(currentStoreItem);
                setupItems();
                update();
            }
        });
    }

    private void setupItems() {
        if (paginatedPane != null) paginatedPane.clear();
        else {
            this.paginatedPane = new PaginatedPane(0, 0, 9, rows - 1);
            this.addPane(paginatedPane);
        }

        items = store.getItemList().stream()
                .map(this::toStoreGuiItem)
                .toList();

        paginatedPane.populateWithGuiItems(items.stream().map(StoreGuiItem::getGuiItem).toList());
    }

    private StoreGuiItem toStoreGuiItem(ItemStack original) {
        ItemStack storeItem = original.clone();
        ItemStack guiStack = storeItem.clone();
        GuiItem guiItem = new GuiItemBuilder(guiStack)
                .clickEvent(click -> {
                    if (click.isCancelled()) return;
                    click.setCancelled(true);
                    info("Clicked on item: {}", storeItem.getType());
                    if (isOnCooldown()) {
                        info("On cooldown");
                        GuiUtils.temporaryChange(guiStack,
                                TextUtil.mm(config.string("store.cooldown-title"), true),
                                config.stringList("store.cooldown-lore").stream()
                                        .map(s -> TextUtil.mm(s, true))
                                        .toList(),
                                20L,
                                this::update);
                        return;
                    }

                    CooldownManager.addCooldown(player.getUniqueId(), "store", 5L);

                    boolean hasInvSpace = player.getInventory().firstEmpty() != -1;

                    if (!hasInvSpace) {
                        info("No space");
                        GuiUtils.temporaryChange(guiStack,
                                TextUtil.mm(config.string("store.no-space-title"), true),
                                config.stringList("store.no-space-lore").stream()
                                        .map(s -> TextUtil.mm(s, true))
                                        .toList(),
                                60L,
                                this::update);
                        return;
                    }
                    boolean isRightClick = click.isRightClick();
                    int amountToRemove = isRightClick ? (storeItem.getAmount() / 2 + storeItem.getAmount() % 2) : storeItem.getAmount();
                    storeItem.setAmount(amountToRemove);
                    boolean success = store.removeItem(storeItem, amountToRemove);


                    if (success) {
                        info("Success removing {}", storeItem);
                        if (click.getCursor().getType() != Material.AIR || click.isShiftClick()) {
                            info("Cursor not empty");
                            // if shift click add to the slot where it would normally go with shift
                            player.getInventory().addItem(storeItem);
                        } else {
                            info("Cursor empty");
                            click.setCursor(storeItem);
                        }
                    } else {
                        GuiUtils.temporaryChange(guiStack,
                                TextUtil.mm(config.string("store.item-is-gone-display"), true),
                                config.stringList("store.item-is-gone-lore").stream()
                                        .map(s -> TextUtil.mm(s, true))
                                        .toList(),
                                60L,
                                this::update);
                    }
                    setupItems();
                    update();
                }).build();
        return new StoreGuiItem(storeItem, guiItem);
    }

    private boolean isOnCooldown() {
        return CooldownManager.cooldown(player.getUniqueId(), "store") != 0;
    }

    private void setupNavigation() {
        StaticPane nav = new StaticPane(0, rows - 1, 9, 1);
        nav.addItem(back(), 0, 0);
        this.addPane(nav);
    }

    private GuiItem back() {
        return new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11013)
                .display(config.string("store.back"))
                .lore(config.stringList("store.back-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    player.closeInventory();
                    player.performCommand(config.string("store.back-command"));
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }

    @AllArgsConstructor
    @Data
    static class StoreGuiItem {
        ItemStack trueStack;
        GuiItem guiItem;
    }
}
