package arc.arc.stock.gui;

import arc.arc.ARC;
import arc.arc.board.guis.AddBoardGui;
import arc.arc.board.guis.BoardGui;
import arc.arc.configs.StockConfig;
import arc.arc.configs.StockConfig;
import arc.arc.stock.*;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static arc.arc.util.GuiUtils.cooldownCheck;
import static arc.arc.util.TextUtil.*;

public class PositionSelector extends ChestGui {
    StockPlayer stockPlayer;
    Player player;
    String symbol;

    GuiItem back, refresh, create, profile;

    public PositionSelector(Player player, String symbol) {
        super(3,  TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("position-selector.menu-title"),
                "symbol", symbol)));
        this.stockPlayer = StockPlayerManager.getOrCreate(player);
        this.symbol = symbol;
        this.player = player;
        setupBackground();
        setupPositions();
        setupNav();
    }

    private void setupPositions() {
        PaginatedPane paginatedPane = new PaginatedPane(0, 0, 9, 2);
        List<GuiItem> guiItemList = new ArrayList<>();
        List<Position> positions = stockPlayer.positions(symbol);
        if (positions != null) {
            for (Position position : positions) {
                GuiItem guiItem = positionItem(position);
                guiItemList.add(guiItem);
            }
        }
        paginatedPane.populateWithGuiItems(guiItemList);
        this.addPane(paginatedPane);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 2, 9, 1);
        this.addPane(pane);
        TagResolver tagResolver = customResolver();

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-selector.back-display"))
                .lore(StockConfig.stringList("position-selector.back-lore"))
                .tagResolver(tagResolver)
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new SymbolSelector(player).show(player);
                }).build();
        pane.addItem(back, 0, 0);

        refresh = new ItemStackBuilder(Material.BLACK_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-selector.refresh-display"))
                .lore(StockConfig.stringList("position-selector.refresh-lore"))
                .tagResolver(tagResolver)
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (!cooldownCheck(back, player, PositionSelector.this)) return;
                    new PositionSelector(player, symbol).show(player);
                }).build();
        pane.addItem(refresh, 3, 0);

        create = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-selector.create-display"))
                .lore(StockConfig.stringList("position-selector.create-lore"))
                .tagResolver(tagResolver)
                .modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (!cooldownCheck(back, player, PositionSelector.this)) return;
                    if (player.hasPermission("arc.stock.buy")) new PositionCreator(player, symbol).show(player);
                    else player.sendMessage(TextUtil.noPermissions());
                }).build();
        pane.addItem(create, 4, 0);

        profile = new ItemStackBuilder(Material.PLAYER_HEAD)
                .skull(player.getUniqueId())
                .tagResolver(tagResolver)
                .display(StockConfig.string("position-selector.profile-display"))
                .lore(StockConfig.stringList("position-selector.profile-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new ProfileMenu(player, 1, symbol).show(player);
                }).build();
        pane.addItem(profile, 8, 0);
    }

    private TagResolver customResolver() {
        return TagResolver.builder()
                .resolver(TagResolver.resolver("balance", Tag.inserting(
                        mm(TextUtil.formatAmount(stockPlayer.getBalance()), true)
                )))
                .resolver(TagResolver.resolver("total_balance", Tag.inserting(
                        mm(TextUtil.formatAmount(stockPlayer.totalBalance()), true)
                )))
                .resolver(TagResolver.resolver("positions_count", Tag.inserting(
                        mm(stockPlayer.positions().size() + "", true)
                )))
                .build();
    }

    private GuiItem positionItem(Position position) {
        Position.AutoClosePrices autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.isAutoTake());
        return new ItemStackBuilder(Material.PAPER)
                .display(StockConfig.string("position-selector.position-display"))
                .lore(StockConfig.stringList("position-selector.position-lore"))
                .tagResolver(position.resolver())
                .appendResolver("close_at_low", autoClosePrices.low() == -1 ? "<red>Нет" :
                        formatAmount(autoClosePrices.low()))
                .appendResolver("close_at_high", autoClosePrices.high() == -1 ? "<red>Нет" :
                        formatAmount(autoClosePrices.high()))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new PositionMenu(player, position).show(click.getWhoClicked());
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 2, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, 2);
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }
}
