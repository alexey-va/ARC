package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.board.Board;
import arc.arc.util.HeadUtil;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class BoardGui extends ChestGui {

    private static final HashSet<UUID> cooldownSet = new HashSet<>();
    Player player;
    PaginatedPane paginatedPane;

    public BoardGui(Player player) {
        super(6, "Доска объявлений");
        this.player = player;

        setupBackground();
        paginatedPane = new PaginatedPane(0, 0, 9, 5);
        populatePane();
        setupNav();

        this.addPane(paginatedPane);

    }

    private void populatePane() {
        List<GuiItem> guiItemList = new ArrayList<>();
        boolean isOp = player.hasPermission("arc.admin");
        paginatedPane.clear();
        for (Board.CachedItem cachedItem : ARC.plugin.board.itemCache) {
            GuiItem guiItem;

            ItemStack stack;
            if (player.getUniqueId().equals(cachedItem.boardEntry.playerUuid)) {
                stack = cachedItem.stack.clone();
                ItemMeta meta = stack.getItemMeta();
                List<Component> lore = meta.lore();
                if (lore == null) lore = new ArrayList<>();
                lore.add(Component.text(" "));
                lore.add(TextUtil.strip(Component.text("Нажмите, чтобы редактировать", NamedTextColor.GREEN)));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                meta.addEnchant(Enchantment.ARROW_DAMAGE, 1, true);
                stack.setItemMeta(meta);
            } else stack = cachedItem.stack;

            guiItem = new GuiItem(stack, inventoryClickEvent -> {
                inventoryClickEvent.setCancelled(true);
                if (inventoryClickEvent.isLeftClick() &&
                        (cachedItem.boardEntry.playerUuid.equals(player.getUniqueId()) || isOp)
                ) {
                    new EditBoardGui(player, cachedItem.boardEntry).show(player);
                }
            });
            guiItemList.add(guiItem);
        }
        paginatedPane.populateWithGuiItems(guiItemList);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 5, 9, 1);
        ItemStack backItem = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta meta2 = backItem.getItemMeta();
        meta2.displayName(Component.text("Назад", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta2.setCustomModelData(11013);
        backItem.setItemMeta(meta2);
        GuiItem backGuiItem = new GuiItem(backItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            ((Player) inventoryClickEvent.getWhoClicked()).performCommand("m");
        });
        pane.addItem(backGuiItem, 0, 0);

        ItemStack refreshItem = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta3 = refreshItem.getItemMeta();
        meta3.displayName(Component.text("Обновить", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta3.setCustomModelData(11010);
        refreshItem.setItemMeta(meta3);
        pane.addItem(new GuiItem(refreshItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if (cooldownSet.contains(player.getUniqueId())) {
                player.sendMessage(Component.text("Не так быстро!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                return;
            }
            tagCooldown(player);
            populatePane();
            this.update();
        }), 4, 0);

        ItemStack addStack = HeadUtil.getSkull(player.getUniqueId());
        ItemMeta addMeta = addStack.getItemMeta();
        addMeta.displayName(TextUtil.strip(Component.text("Опубликовать объявление", NamedTextColor.GREEN)));
        addMeta.lore(List.of(TextUtil.strip(Component.text("Цена: ", NamedTextColor.GRAY)
                .append(Component.text((int)(Config.boardCost),NamedTextColor.GREEN))
                .append(Component.text("\uD83D\uDCB0", NamedTextColor.WHITE))
        )));
        addStack.setItemMeta(addMeta);
        GuiItem addItem = new GuiItem(addStack, inventoryClickEvent -> {
            if(player.hasPermission("arc.board.publish")) new AddBoardGui(player).show(player);
            else{
                player.sendMessage(TextUtil.strip(
                        Component.text("У вас нет на это разрешения!", NamedTextColor.RED)
                ));
            }
        });
        pane.addItem(addItem, 8, 0);
        this.addPane(pane);
    }

    private void tagCooldown(Player player) {
        cooldownSet.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldownSet.remove(player.getUniqueId());
            }
        }.runTaskLater(ARC.plugin, 20L);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 5, 9, 1);
        ItemStack bgItem = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bgItem.getItemMeta();
        meta.setCustomModelData(11000);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        pane.addItem(new GuiItem(bgItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, 5);
        ItemStack bgItem2 = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta2 = bgItem2.getItemMeta();
        meta2.displayName(Component.text(" "));
        bgItem2.setItemMeta(meta2);
        pane2.addItem(new GuiItem(bgItem2, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }
}
