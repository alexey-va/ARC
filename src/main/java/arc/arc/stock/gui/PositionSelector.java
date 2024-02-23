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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static arc.arc.util.GuiUtils.cooldownCheck;
import static arc.arc.util.TextUtil.*;

public class PositionSelector extends ChestGui {
    StockPlayer stockPlayer;
    Player player;
    String symbol;
    List<Position> positions;

    GuiItem back, create, profile;

    BukkitTask refreshTask;
    PaginatedPane paginatedPane;

    public PositionSelector(Player player, String symbol) {
        super(2, TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("position-selector.menu-title"),
                "symbol", symbol)));
        this.stockPlayer = StockPlayerManager.getOrCreate(player);
        this.symbol = symbol;
        this.player = player;
        this.positions = stockPlayer.positions(symbol);
        setupBackground();
        setupPositions();
        setupNav();

        refreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (getViewers().isEmpty()) {
                    this.cancel();
                    return;
                }
                populatePositions();
                update();
            }
        }.runTaskTimer(ARC.plugin, 100L, 100L);
        this.setOnClose(close -> cancelTasks());
    }

    public void cancelTasks() {
        if (refreshTask != null && !refreshTask.isCancelled()) refreshTask.cancel();
    }

    private void setupPositions() {
        paginatedPane = new PaginatedPane(0, 0, 9, 1);
        this.addPane(paginatedPane);
        populatePositions();
    }

    private void populatePositions() {
        paginatedPane.clear();
        List<GuiItem> guiItemList = new ArrayList<>();
        if (positions != null) {
            for (Position position : positions) {
                GuiItem guiItem = positionItem(position);
                guiItemList.add(guiItem);
            }
        }
        paginatedPane.populateWithGuiItems(guiItemList);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 1, 9, 1);
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

        boolean canHaveMore = stockPlayer.isBelowMaxStockAmount(player) && !(positions != null && positions.size()>=9);
        create = new ItemStackBuilder(canHaveMore ?
                Material.GREEN_STAINED_GLASS_PANE :
                Material.RED_STAINED_GLASS_PANE)
                .display(canHaveMore ?
                        StockConfig.string("position-selector.create-display") :
                        StockConfig.string("position-selector.create-display-limit"))
                .lore(canHaveMore ?
                        StockConfig.stringList("position-selector.create-lore") :
                        StockConfig.stringList("position-selector.create-lore-limit"))
                .tagResolver(tagResolver)
                .appendResolver("max_stock_amount", stockPlayer.maxStockAmount(player) + "")
                //.modelData(11010)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (!canHaveMore) return;
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
                .resolver(stockPlayer.tagResolver())
                .build();
    }

    private GuiItem positionItem(Position position) {
        Position.AutoClosePrices autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.isAutoTake());
        return new ItemStackBuilder(position.getIconMaterial())
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
        OutlinePane pane = new OutlinePane(0, 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, 1);
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }
}
