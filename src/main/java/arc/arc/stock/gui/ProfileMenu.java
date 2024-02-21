package arc.arc.stock.gui;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.stock.StockPlayer;
import arc.arc.stock.StockPlayerManager;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.Nullable;

import static arc.arc.util.TextUtil.mm;

public class ProfileMenu extends ChestGui {
    StockPlayer stockPlayer;
    Player player;
    GuiItem back, balance, statistic, auto;
    int previous;
    String symbol;

    public ProfileMenu(Player player, int previous, @Nullable String symbol) {
        super(2, TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("profile-menu.menu-title"),
                "name", player.getName())));
        stockPlayer = StockPlayerManager.getOrCreate(player);
        this.player = player;
        this.previous = previous;
        this.symbol = symbol;

        setupBackground();
        setupNav();
        setupButtons();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 1, 9, 1);
        this.addPane(pane);

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("profile-menu.back-display"))
                .lore(StockConfig.stringList("profile-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (previous == 0) new SymbolSelector(player).show(player);
                    else if (previous == 1) new PositionSelector(player, symbol).show(player);
                }).build();
        pane.addItem(back, 0, 0);
    }

    private void setupButtons() {
        StaticPane staticPane = new StaticPane(0, 0, 9, 1);
        this.addPane(staticPane);
        TagResolver tagResolver = stockPlayer.tagResolver();

        statistic = new ItemStackBuilder(Material.PAPER)
                .display(StockConfig.string("profile-menu.statistic-display"))
                .lore(StockConfig.stringList("profile-menu.statistic-lore"))
                .tagResolver(tagResolver)
                .toGuiItemBuilder()
                .clickEvent(click -> click.setCancelled(true)).build();
        staticPane.addItem(statistic, 1, 0);

        auto = new ItemStackBuilder(Material.LEVER)
                .display(StockConfig.string("profile-menu.auto-take-display"))
                .lore(StockConfig.stringList("profile-menu.auto-take-lore"))
                .tagResolver(tagResolver)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    stockPlayer.setAutoTake(!stockPlayer.isAutoTake());
                    auto.setItem(new ItemStackBuilder(Material.LEVER)
                            .display(StockConfig.string("profile-menu.auto-take-display"))
                            .lore(StockConfig.stringList("profile-menu.auto-take-lore"))
                            .tagResolver(tagResolver).build());
                    update();
                }).build();

        balance = new ItemStackBuilder(Material.STICK)
                .modelData(11138)
                .display(StockConfig.string("profile-menu.balance-display"))
                .lore(StockConfig.stringList("profile-menu.balance-lore"))
                .tagResolver(tagResolver)
                .toGuiItemBuilder()
                .clickEvent(this::acceptBalanceClick).build();
        staticPane.addItem(balance, 3, 0);
    }

    private double getNewBalance(InventoryClickEvent click) {
        double newBalance = stockPlayer.getBalance();
        if (click.isLeftClick()) {
            if (click.isShiftClick()) {
                newBalance += 10000;
            } else newBalance += 1000;
        } else if (click.isRightClick()) {
            if (click.isShiftClick()) {
                newBalance = Math.max(1, newBalance - 10000);
            } else newBalance = Math.max(1, newBalance - 1000);
        }
        return newBalance;
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }

    private void acceptBalanceClick(InventoryClickEvent click) {
        double newBalance = getNewBalance(click);
        double diff = newBalance - stockPlayer.getBalance();
        double totalGains = stockPlayer.totalGains();

        // check for bankruptcy
        if (totalGains < 0 && Math.abs(totalGains) > newBalance) {
            TagResolver resolver = TagResolver.resolver("total_gains", Tag.inserting(
                    mm(TextUtil.formatAmount(totalGains), true)
            ));
            GuiUtils.temporaryChange(balance.getItem(),
                    mm(StockConfig.string("profile-menu.will-go-bankrupt-display"), resolver),
                    StockConfig.stringList("profile-menu.will-go-bankrupt-lore").stream()
                            .map(s -> mm(s, resolver)).toList(),
                    100L, this::update);
            this.update();
            return;
        }

        // check for players balance
        double playerBalance = ARC.getEcon().getBalance(player);
        if (diff > 0 && diff > playerBalance) {
            TagResolver resolver = TagResolver.resolver("player_balance", Tag.inserting(
                    mm(TextUtil.formatAmount(playerBalance), true)
            ));
            GuiUtils.temporaryChange(balance.getItem(),
                    mm(StockConfig.string("profile-menu.not-enough-money-display"), resolver),
                    StockConfig.stringList("profile-menu.not-enough-money-lore").stream()
                            .map(s -> mm(s, resolver)).toList(),
                    100L, this::update);
            this.update();
            return;
        }

        StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, diff);
        balance.setItem(new ItemStackBuilder(Material.STICK)
                .modelData(11138)
                .display(StockConfig.string("profile-menu.balance-display"))
                .lore(StockConfig.stringList("profile-menu.balance-lore"))
                .tagResolver(stockPlayer.tagResolver()).build());
        this.update();
    }
}
