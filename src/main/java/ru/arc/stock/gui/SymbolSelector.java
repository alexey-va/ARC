package ru.arc.stock.gui;

import ru.arc.configs.StockConfig;
import ru.arc.stock.Position;
import ru.arc.stock.Stock;
import ru.arc.stock.StockMarket;
import ru.arc.stock.StockPlayer;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import ru.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SymbolSelector extends ChestGui {
    StockPlayer stockPlayer;
    GuiItem back, profile, all, market;
    int rows=4;

    public SymbolSelector(StockPlayer stockPlayer) {
        super(4, TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("symbol-selector.menu-title"))));
        this.stockPlayer = stockPlayer;
        //System.out.println("Symbol selector from " + Thread.currentThread().getName());
        setRows(rows);
        setupBackground();
        setupStocks();
        setupNav();
    }

    private void setupStocks() {
        PaginatedPane paginatedPane = new PaginatedPane(0, 1, 9, rows-2);
        List<GuiItem> guiItemList = new ArrayList<>();
        for (Stock stock : StockMarket.stocks().stream()
                .filter(s -> s.getPrice() > 0.0)
                .filter(StockMarket::isEnabledStock)
                .sorted(Comparator.comparingInt(s -> s.getType() == Stock.Type.STOCK ? 0 : 1)).toList()) {
            GuiItem guiItem = stockItem(stock);
            guiItemList.add(guiItem);
        }
        paginatedPane.populateWithGuiItems(guiItemList);
        this.addPane(paginatedPane);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, rows-1, 9, 1);
        this.addPane(pane);
        TagResolver tagResolver = stockPlayer.tagResolver();

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("symbol-selector.back-display"))
                .tagResolver(tagResolver)
                .lore(StockConfig.stringList("symbol-selector.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    ((Player) click.getWhoClicked()).performCommand(StockConfig.mainMenuBackCommand);
                }).build();
        pane.addItem(back, 0, 0);

        all = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .tagResolver(tagResolver)
                .display(StockConfig.string("symbol-selector.all-positions-display"))
                .lore(StockConfig.stringList("symbol-selector.all-positions-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(
                            () -> new PositionSelector(stockPlayer, null),
                            click.getWhoClicked());
                }).build();
        pane.addItem(all, 4, 0);

        profile = new ItemStackBuilder(Material.PLAYER_HEAD)
                .skull(stockPlayer.getPlayerUuid())
                .tagResolver(tagResolver)
                .display(StockConfig.string("symbol-selector.profile-display"))
                .lore(StockConfig.stringList("symbol-selector.profile-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new ProfileMenu(stockPlayer, 0, null), click.getWhoClicked());
                }).build();
        pane.addItem(profile, 8, 0);


        StaticPane topNavigation = new StaticPane(0, 0, 9, 1);
        this.addPane(topNavigation);
        market = new ItemStackBuilder(Material.BELL)
                .display(StockConfig.string("symbol-selector.market-display"))
                .lore(StockConfig.stringList("symbol-selector.market-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    Player p = (Player) click.getWhoClicked();
                    p.performCommand(StockConfig.string("market-command"));
                    p.closeInventory();
                }).build();
        topNavigation.addItem(market, 4, 0);
    }

    private GuiItem stockItem(Stock stock) {
        List<Position> positions = stockPlayer.positions(stock.getSymbol());
        int size = positions == null ? 0 : positions.size();
        return new ItemStackBuilder(stock.getIcon().stack())
                .display(stock.getDisplay())
                .lore(stock.getLore())
                .tagResolver(stock.tagResolver())
                .appendResolver("positions_in_symbol", size + "")
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new PositionSelector(stockPlayer, stock.getSymbol()), click.getWhoClicked());
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, rows );
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 1, 9, 2);
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOW);
        this.addPane(pane2);
    }
}
