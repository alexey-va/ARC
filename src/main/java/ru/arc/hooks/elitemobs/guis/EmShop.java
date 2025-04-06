package ru.arc.hooks.elitemobs.guis;

import ru.arc.configs.Config;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.GuiItemBuilder;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static ru.arc.util.TextUtil.formatAmount;
import static ru.arc.util.TextUtil.mm;

@Log4j2
public class EmShop extends ChestGui {

    Config config;
    Player player;
    ShopHolder shopHolder;
    boolean isGear;
    GuiItem back, change, update;

    public EmShop(Config config, Player player, ShopHolder shopHolder, boolean isGear) {
        super(config.integer("shop.rows", 6), TextHolder.deserialize(config.string("shop.title", "Shop")));
        this.config = config;
        this.player = player;
        this.shopHolder = shopHolder;
        this.isGear = isGear;
        setupBackground();
        setupItems();
        setupNav();
    }

    private void setupItems() {
        PaginatedPane pane = new PaginatedPane(0, 1, 9, config.integer("shop.rows", 6) - 1, Pane.Priority.HIGHEST);
        this.addPane(pane);
        ShopHolder.Shop shop = shopHolder.getShop(player);
        List<GuiItem> items = new ArrayList<>();

        for (ShopHolder.ShopItem item : (isGear ? shop.gear : shop.trinkets)) {
            final ItemStack stack = item.stack.clone();
            ItemMeta meta = stack.getItemMeta();
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(0, config.componentList("shop.item-price-lore", "<price>", formatAmount(item.price)));
            int removeLast = config.integer("shop.remove-last-lore", 0);
            if (removeLast > 0) {
                for (int i = 0; i < removeLast; i++) {
                    lore.remove(lore.size() - 1);
                }
            }
            meta.lore(lore);
            stack.setItemMeta(meta);
            items.add(new GuiItemBuilder(stack).clickEvent(click -> processClick(click, stack, item)).build());
        }
        pane.populateWithGuiItems(items);
    }

    TagResolver resolver() {
        long resetTimeTicks = config.integer("shop.reset-ticks", 20 * 60 * 5);
        long resetTime = resetTimeTicks * 50;

        long sinceLastReset = System.currentTimeMillis() - shopHolder.getShop(player).timestamp();
        int minsTillReset = (int) ((resetTime - sinceLastReset) / 1000 / 60);
        if (minsTillReset == 0) minsTillReset = 1;

        return TagResolver.builder()
                .resolver(TagResolver.resolver("type", Tag.inserting(mm(isGear ? "Снаряжение" : "Тринкеты", true))))
                .resolver(TagResolver.resolver("balance", Tag.inserting(mm(formatAmount(HookRegistry.emHook.balance(player)), true))))
                .resolver(TagResolver.resolver("player", Tag.inserting(mm(player.getName(), true))))
                .resolver(TagResolver.resolver("update_minutes", Tag.inserting(mm(minsTillReset + "", true))))
                .build();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 0, 9, 1);
        this.addPane(pane);

        TagResolver resolver = resolver();

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(config.string("shop.back-display"))
                .tagResolver(resolver)
                .lore(config.stringList("shop.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ((Player) click.getWhoClicked()).performCommand(config.string("shop.back-command"));
                }).build();
        //pane.addItem(back, 0, 0);

        change = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(config.string("shop.change-display"))
                .lore(config.stringList("shop.change-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new EmShop(config, player, shopHolder, !isGear), click.getWhoClicked());
                }).build();
        pane.addItem(change, 4, 0);

        update = new ItemStackBuilder(Material.PAPER)
                .modelData(31173)
                .display(config.string("shop.update-display"))
                .lore(config.stringList("shop.update-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(click -> click.setCancelled(true)).build();
        pane.addItem(update, 8, 0);
    }

    private void processClick(InventoryClickEvent click, ItemStack stack, ShopHolder.ShopItem item) {
        click.setCancelled(true);
        if (click.getWhoClicked().getInventory().firstEmpty() == -1) {
            GuiUtils.temporaryChange(stack, config.component("shop.not-enough-space"), null, 60, this::update);
            this.update();
            return;
        }

        Player player1 = (Player) click.getWhoClicked();
        double balance = HookRegistry.emHook.balance(player1);
        double cost = item.price;

        if (balance < cost) {
            GuiUtils.temporaryChange(stack, config.component("shop.not-enough-money-display", "<cost>",
                            formatAmount(cost), "<balance>", formatAmount(balance)),
                    config.componentList("shop.not-enough-money-lore", "<cost>",
                            formatAmount(cost), "<balance>", formatAmount(balance)),
                    60, this::update);
            this.update();
            return;
        }

        HookRegistry.emHook.removeBalance(player1, cost);
        player1.getInventory().addItem(item.stack);
    }

    private void setupBackground() {
/*        OutlinePane pane = new OutlinePane(0, 0, 9, config.integer("shop.rows", 6), Pane.Priority.LOWEST);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        this.addPane(pane);*/
    }
}
