package arc.arc.board.guis;

import arc.arc.ARC;
import arc.arc.board.BoardEntry;
import arc.arc.board.BoardItem;
import arc.arc.configs.BoardConfig;
import arc.arc.board.Board;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.stream.Collectors;

import static arc.arc.util.GuiUtils.cooldownCheck;
import static arc.arc.util.TextUtil.strip;

@Slf4j
public class BoardGui extends ChestGui {

    Player player;
    PaginatedPane paginatedPane;
    GuiItem refresh, back, publish;

    public BoardGui(Player player) {
        super(6, TextHolder.deserialize(BoardConfig.boardGuiName));
        this.player = player;

        paginatedPane = new PaginatedPane(0, 0, 9, 5);
        if (!this.getPanes().contains(paginatedPane)) this.addPane(paginatedPane);

        fillItems();
        setupBackground();
        setupNav();
    }

    private void fillItems() {
        log.info("Filling items");
        List<GuiItem> guiItemList = Board.items()
                .stream()
                .map(this::toGuiItem)
                .collect(Collectors.toList());
        paginatedPane.clear();
        paginatedPane.populateWithGuiItems(guiItemList);
    }

    private GuiItem toGuiItem(BoardItem boardItem) {
        ItemStack res = boardItem.stack.clone();
        ItemMeta meta = boardItem.stack.getItemMeta();
        if (boardItem.entry.canEdit(player)) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(BoardConfig.editBottom.stream()
                    .map(MiniMessage.miniMessage()::deserialize)
                    .map(TextUtil::strip)
                    .toList());
            meta.lore(lore);
        }
        if (boardItem.entry.canRate(player)) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            lore.addAll(BoardConfig.rateBottom.stream()
                    .map(MiniMessage.miniMessage()::deserialize)
                    .map(TextUtil::strip)
                    .toList());
            meta.lore(lore);
        }
        res.setItemMeta(meta);
        final GuiItem guiItem = new GuiItem(res);
        guiItem.setAction(click -> {
            click.setCancelled(true);
            if (click.isShiftClick() && click.isLeftClick()) {
                openEditor(boardItem.entry, guiItem);
            } else if (click.isLeftClick()) {
                openRating(boardItem.entry, guiItem);
            }
        });

        return guiItem;
    }

    private void openEditor(BoardEntry entry, GuiItem guiItem) {
        if (entry.canEdit(player)) {
            new AddBoardGui(player, entry).show(player);
        } else {
            GuiUtils.temporaryChange(guiItem.getItem(),
                    Component.text("Вы не можете это редактировать", NamedTextColor.RED),
                    null, 60L, this::update);
            update();
        }
    }

    private void openRating(BoardEntry entry, GuiItem guiItem) {
        if (entry.canEdit(player)) {
            new RateBoardGui(player, entry).show(player);
        } else {
            GuiUtils.temporaryChange(guiItem.getItem(),
                    Component.text("Вы не можете это оценить", NamedTextColor.RED),
                    null, 60L, this::update);
            update();
        }
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 5, 9, 1);
        this.addPane(pane);

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(BoardConfig.getString("board-menu.back-display"))
                .lore(BoardConfig.getStringList("board-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (!cooldownCheck(back, player.getUniqueId(), BoardGui.this)) return;
                    ((Player) click.getWhoClicked()).performCommand(BoardConfig.mainMenuBackCommand);
                }).build();

        pane.addItem(back, 0, 0);

        refresh = new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE)
                .display(BoardConfig.getString("board-menu.refresh-display"))
                .lore(BoardConfig.getStringList("board-menu.refresh-lore"))
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (!cooldownCheck(back, player.getUniqueId(), BoardGui.this)) return;
                    fillItems();
                    this.update();
                }).build();
        pane.addItem(refresh, 4, 0);


        publish = new ItemStackBuilder(Material.PLAYER_HEAD)
                .skull(player.getUniqueId())
                .tagResolver(TagResolver.resolver("cost",
                        Tag.inserting(strip(Component.text(TextUtil.formatAmount(BoardConfig.publishCost))))))
                .display(BoardConfig.getString("board-menu.publish-display"))
                .lore(BoardConfig.getStringList("board-menu.publish-lore"))
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);

                    if (!ARC.getEcon().has(player, BoardConfig.publishCost)) {
                        notEnoughMoneyDisplay(publish);
                        return;
                    }

                    if (!cooldownCheck(back, player.getUniqueId(), BoardGui.this)) return;
                    if (player.hasPermission("arc.board.publish")) new AddBoardGui(player).show(player);
                    else player.sendMessage(TextUtil.noPermissions());
                }).build();
        pane.addItem(publish, 8, 0);
    }

    private void notEnoughMoneyDisplay(GuiItem guiItem){
        GuiUtils.temporaryChange(guiItem.getItem(),
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("not-enough-money")),
                null, 60L, this::update);
        update();
    }


    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 5, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, 5);
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }
}
