package ru.arc.hooks.ps.guis;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import dev.espi.protectionstones.PSRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class PSMenu extends ChestGui {
    public PSMenu(PSRegion region, Player player) {
        super(3, ChatColor.translateAlternateColorCodes('&', "&8&lМеню региона"));

        OutlinePane bg = new OutlinePane(0, 0, 9, 3);
        ItemStack bgItem = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bgItem.getItemMeta();
        meta.setCustomModelData(11000);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        bg.addItem(new GuiItem(bgItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        bg.setRepeat(true);
        bg.setPriority(Pane.Priority.LOWEST);
        this.addPane(bg);

        StaticPane pane = new StaticPane(0, 1, 9, 1);

        ItemStack memeberListStack = new ItemStack(Material.PAPER);
        ItemMeta meta1 = memeberListStack.getItemMeta();
        meta1.displayName(Component.text("Список участников", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta1.lore(List.of(Component.text("Нажмите, чтобы открыть", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta1.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS);
        memeberListStack.setItemMeta(meta1);
        pane.addItem(new GuiItem(memeberListStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            new PSMembers(region, player, region.isOwner(player.getUniqueId())).show(player);
        }), 2, 0);

        ItemStack addMemberStack = new ItemStack(Material.FLOWER_BANNER_PATTERN);
        meta1 = addMemberStack.getItemMeta();
        meta1.displayName(Component.text("Добавить участника", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        if (region.isOwner(player.getUniqueId()))
            meta1.lore(List.of(Component.text("Нажмите, чтобы открыть добавить игроков", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        else
            meta1.lore(List.of(Component.text("Вы не владелец региона", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        meta1.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS);
        addMemberStack.setItemMeta(meta1);
        pane.addItem(new GuiItem(addMemberStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if (!region.isOwner(player.getUniqueId())) {
                return;
            }
            new PSAddMember(region, player).show(player);
        }), 6, 0);

        ItemStack viewStack = new ItemStack(Material.REDSTONE);
        meta1 = viewStack.getItemMeta();
        meta1.displayName(Component.text("Отобразить границу", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta1.lore(List.of(Component.text("Нажмите, чтобы показать", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        viewStack.setItemMeta(meta1);
        GuiItem viewItem = new GuiItem(viewStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            player.performCommand("ps view");
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
        pane.addItem(viewItem, 4, 0);
        this.addPane(pane);
    }
}
