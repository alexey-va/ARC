package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.board.BoardEntry;
import arc.arc.board.BoardItem;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.Config;
import arc.arc.board.Board;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class BoardGui extends ChestGui {

    private static final HashSet<UUID> cooldownSet = new HashSet<>();
    Player player;
    PaginatedPane paginatedPane;

    public BoardGui(Player player) {
        super(6, "Доска объявлений");
        this.player = player;

        paginatedPane = new PaginatedPane(0, 0, 9, 5);
        if (!this.getPanes().contains(paginatedPane)) this.addPane(paginatedPane);

        fillItems();
        setupBackground();
        setupNav();
    }

    private void fillItems() {
        List<GuiItem> guiItemList = Board.instance().items().stream().map(this::toGuiItem).collect(Collectors.toList());
        paginatedPane.clear();
        paginatedPane.populateWithGuiItems(guiItemList);
    }

    private GuiItem toGuiItem(BoardItem boardItem) {
        ItemStack res = boardItem.stack.clone();
        ItemMeta meta = boardItem.stack.getItemMeta();
        if (boardItem.entry.canEdit(player)) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(BoardConfig.editBottom.stream().map(MiniMessage.miniMessage()::deserialize).toList());
            meta.lore(lore);
        }
        if (boardItem.entry.canRate(player)) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(BoardConfig.rateBottom.stream().map(MiniMessage.miniMessage()::deserialize).toList());
            meta.lore(lore);
        }
        res.setItemMeta(meta);
        return new GuiItem(res, click -> {
            click.setCancelled(true);
            if (click.isShiftClick() && click.isLeftClick()) {
                openEditor(boardItem.entry);
            } else if (click.isLeftClick()) {
                openRating(boardItem.entry);
            }
        });
    }

    private void openEditor(BoardEntry entry) {
        player.sendMessage("Opening editor for " + entry.entryUuid);
    }

    private void openRating(BoardEntry entry) {
        player.sendMessage("Opening rating for " + entry.entryUuid);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 5, 9, 1);
        this.addPane(pane);

        pane.addItem(new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display("<gray>Назад")
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ((Player) click.getWhoClicked()).performCommand(BoardConfig.mainMenuBackCommand);
                }).build(), 0, 0);

        pane.addItem(new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE)
                .display("<gray>Обновить")
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (cooldownSet.contains(player.getUniqueId())) {
                        player.sendMessage(TextUtil.mm("<red>Не так быстро!"));
                        return;
                    }
                    tagCooldown(player);
                    fillItems();
                    this.update();
                }).build(), 4, 0);

        pane.addItem(new ItemStackBuilder(Material.PLAYER_HEAD)
                .skull(player.getUniqueId())
                .display("<green>Опубликовать объявление")
                .lore(List.of("<gray>Цена: <green>" + (Config.boardCost) + "<white>\uD83D\uDCB0"))
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (player.hasPermission("arc.board.publish")) new AddBoardGui(player).show(player);
                    else player.sendMessage(TextUtil.noPermissions());
                }).build(), 8, 0);
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
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, 5);
        pane2.addItem(GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }
}
