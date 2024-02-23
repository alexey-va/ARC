package arc.arc.stock.gui;

import arc.arc.configs.StockConfig;
import arc.arc.stock.*;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;

import static arc.arc.util.TextUtil.*;

public class PositionCreator extends ChestGui {
    StockPlayer stockPlayer;
    Player player;
    String symbol;
    Stock stock;
    GuiItem back, amountItem, typeItem, leverageItem, createItem, upperItem, lowerItem;


    double amount = 1;
    int leverage = 1;
    double upper = Double.MAX_VALUE, lower = Double.MAX_VALUE;
    Position.Type type = Position.Type.BOUGHT;

    public PositionCreator(Player player, String symbol) {
        super(2, TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("position-creator.menu-title"),
                "symbol", symbol)));
        this.stockPlayer = StockPlayerManager.getOrCreate(player);
        this.symbol = symbol;
        this.player = player;
        stock = StockMarket.stock(symbol);
        if (stock.getPrice() < 1) leverage = 10000;
        else if (stock.getPrice() < 10) leverage = 1000;
        else if (stock.getPrice() < 100) leverage = 100;
        else if (stock.getPrice() < 1000) leverage = 10;

        setupBackground();
        setupNav();
        setupButtons();
    }


    private void setupButtons() {
        StaticPane staticPane = new StaticPane(0, 0, 9, 1);

        var resolver = resolver(amount, type, leverage);
        amountItem = new ItemStackBuilder(Material.GOLD_INGOT)
                .tagResolver(resolver)
                .display(StockConfig.string("position-creator.amount-display"))
                .lore(StockConfig.stringList("position-creator.amount-lore"))
                .toGuiItemBuilder()
                .clickEvent(this::acceptAmountClick).build();
        staticPane.addItem(amountItem, 0, 0);

        typeItem = new ItemStackBuilder(type == Position.Type.BOUGHT ? Material.LAPIS_LAZULI : Material.COAL)
                .tagResolver(resolver)
                .display(StockConfig.string("position-creator.type-display"))
                .lore(StockConfig.stringList("position-creator.type-lore"))
                .toGuiItemBuilder()
                .clickEvent(this::acceptTypeClick).build();
        staticPane.addItem(typeItem, 2, 0);

        leverageItem = new ItemStackBuilder(Material.LEVER)
                .display(StockConfig.string("position-creator.leverage-display"))
                .lore(StockConfig.stringList("position-creator.leverage-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(this::acceptLeverageClick).build();
        staticPane.addItem(leverageItem, 4, 0);

        upperItem = new ItemStackBuilder(Material.SLIME_BLOCK)
                .display(StockConfig.string("position-creator.upper-display"))
                .lore(StockConfig.stringList("position-creator.upper-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(this::acceptUpperClick).build();
        staticPane.addItem(upperItem, 6, 0);

        lowerItem = new ItemStackBuilder(Material.HONEY_BLOCK)
                .display(StockConfig.string("position-creator.lower-display"))
                .lore(StockConfig.stringList("position-creator.lower-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(this::acceptLowerClick).build();
        staticPane.addItem(lowerItem, 7, 0);

        boolean canHaveMore = stockPlayer.isBelowMaxStockAmount(player);
        createItem = new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(canHaveMore ?
                        StockConfig.string("position-creator.create-display") :
                        StockConfig.string("position-creator.create-display-limit"))
                .lore(canHaveMore ?
                        StockConfig.stringList("position-creator.create-lore") :
                        StockConfig.stringList("position-creator.create-lore-limit"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(!canHaveMore ? c -> c.setCancelled(true) : this::acceptCreateClick).build();
        staticPane.addItem(createItem, 8, 0);

        this.addPane(staticPane);
    }

    private double getNewUpper(InventoryClickEvent click) {
        double newUpper = upper;
        if (upper == Double.MAX_VALUE) newUpper = 1000;
        else {
            if (click.isLeftClick()) {
                if (click.isShiftClick()) {
                    newUpper = Math.min(newUpper + 1000, 100000);
                } else newUpper = Math.min(newUpper + 100, 10000);
            } else if (click.isRightClick()) {
                if (click.isShiftClick()) {
                    newUpper = Math.max(1, newUpper - 1000);
                } else newUpper = Math.max(1, newUpper - 100);
            }
        }
        return newUpper;
    }

    private double getNewLower(InventoryClickEvent click) {
        double newLower = lower;
        if (lower == Double.MAX_VALUE) newLower = 1000;
        else {
            if (click.isLeftClick()) {
                if (click.isShiftClick()) {
                    newLower = Math.min(newLower + 1000, 1000_000);
                } else newLower = Math.min(newLower + 100, 1000_000);
            } else if (click.isRightClick()) {
                if (click.isShiftClick()) {
                    newLower = Math.max(1, newLower - 1000);
                } else newLower = Math.max(1, newLower - 100);
            }
        }
        return newLower;
    }

    private int getNewLeverage(InventoryClickEvent click) {
        int newLeverage = leverage;
        if (click.isLeftClick()) {

            if (click.isShiftClick()) {
                if (newLeverage == 1) newLeverage = 100;
                else newLeverage = Math.min(newLeverage + 100, 1000_000);
            } else {
                if (newLeverage == 1) newLeverage = 10;
                else newLeverage = Math.min(newLeverage + 10, 1000_000);
            }
        } else if (click.isRightClick()) {
            if (click.isShiftClick()) {
                newLeverage = Math.max(1, newLeverage - 100);
            } else newLeverage = Math.max(1, newLeverage - 10);
        }
        return newLeverage;
    }

    private void updateCrateItem() {
        createItem.setItem(new ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-creator.create-display"))
                .lore(StockConfig.stringList("position-creator.create-lore"))
                .tagResolver(resolver(amount, type, leverage)).build());
    }

    private TagResolver resolver(double amount, Position.Type type, int leverage) {
        DecimalFormat decimalFormat = new DecimalFormat();

        decimalFormat.applyPattern(amount >= 1 ? "###,###" : "0.###");
        Stock stock = StockMarket.stock(symbol);
        double commission = StockPlayerManager.commission(stock, amount, leverage);
        double cost = StockPlayerManager.cost(stock, amount);
        double balance = stockPlayer.getBalance();
        Position.AutoClosePrices autoClosePrices = marginCallAtPrice(stockPlayer.getBalance());

        return TagResolver.builder()
                .resolver(TagResolver.resolver("amount", Tag.inserting(
                        strip(MiniMessage.miniMessage().deserialize(decimalFormat.format(amount)))
                )))
                .resolver(TagResolver.resolver("type", Tag.inserting(
                        strip(MiniMessage.miniMessage().deserialize(type.display))
                )))
                .resolver(TagResolver.resolver("leverage", Tag.inserting(
                        strip(Component.text(leverage))
                )))
                .resolver(TagResolver.resolver("total_cost", Tag.inserting(
                        mm(formatAmount(cost + commission), true)
                )))
                .resolver(TagResolver.resolver("commission", Tag.inserting(
                        mm(formatAmount(commission), true)
                )))
                .resolver(TagResolver.resolver("cost", Tag.inserting(
                        mm(formatAmount(cost), true)
                )))
                .resolver(TagResolver.resolver("balance", Tag.inserting(
                        mm(balance >= (cost + commission) ? "<green>" + formatAmount(balance) :
                                "<red>" + formatAmount(balance), true)
                )))
                .resolver(TagResolver.resolver("upper", Tag.inserting(
                        upper > 1_000_000_000 ? mm("<red>Нет") :
                                mm(formatAmount(upper), true)
                )))
                .resolver(TagResolver.resolver("lower", Tag.inserting(
                        lower > 1_000_000_000 ? mm("<red>Нет") :
                                mm(formatAmount(lower), true)
                )))
                .resolver(TagResolver.resolver("close_at_low", Tag.inserting(
                        autoClosePrices.low() == -1 ? mm("<red>Нет") :
                                mm(formatAmount(autoClosePrices.low()), true)
                )))
                .resolver(TagResolver.resolver("close_at_high", Tag.inserting(
                        autoClosePrices.high() == -1 ? mm("<red>Нет") :
                                mm(formatAmount(autoClosePrices.high()), true)
                )))
                .resolver(TagResolver.resolver("position_amount", Tag.inserting(
                        mm(stockPlayer.positions().size()+"", true)
                )))
                .resolver(TagResolver.resolver("max_stock_amount", Tag.inserting(
                        mm(stockPlayer.maxStockAmount(player)+"", true)
                )))
                .build();
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 1, 9, 1);
        this.addPane(pane);

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-creator.back-display"))
                .lore(StockConfig.stringList("position-creator.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new PositionSelector(player, symbol).show(player);
                }).build();
        pane.addItem(back, 0, 0);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }

    private double getNewAmount(InventoryClickEvent click) {
        double newAmount = amount;
        if (click.isLeftClick()) {
            if (newAmount >= 1) {
                if (click.isShiftClick()) {
                    if (newAmount == 1) newAmount += 9;
                    else newAmount += 10;
                } else newAmount += 1;
            } else {
                if (click.isShiftClick()) {
                    newAmount = Math.min(1.0, newAmount + 0.1);
                } else newAmount = Math.min(1.0, newAmount + 0.01);
            }
        } else if (click.isRightClick()) {
            if (newAmount > 1.0) {
                if (click.isShiftClick()) {
                    newAmount = Math.max(1, newAmount - 10);
                } else newAmount = Math.max(1, newAmount - 1);
            } else {
                if (click.isShiftClick()) {
                    newAmount = Math.max(0.0, newAmount - 0.1);
                } else newAmount = Math.max(0.0, newAmount - 0.01);
            }
        }
        return newAmount;
    }

    private void acceptAmountClick(InventoryClickEvent click) {
        click.setCancelled(true);

        amount = getNewAmount(click);
        ItemMeta meta = new ItemStackBuilder(Material.GOLD_INGOT)
                .tagResolver(resolver(amount, type, leverage))
                .display(StockConfig.string("position-creator.amount-display"))
                .lore(StockConfig.stringList("position-creator.amount-lore"))
                .build().getItemMeta();
        amountItem.getItem().setItemMeta(meta);
        updateCrateItem();
        this.update();
    }

    private void acceptTypeClick(InventoryClickEvent click) {
        click.setCancelled(true);
        if (type == Position.Type.BOUGHT) type = Position.Type.SHORTED;
        else type = Position.Type.BOUGHT;
        typeItem.setItem(new ItemStackBuilder(type == Position.Type.BOUGHT ? Material.LAPIS_LAZULI : Material.COAL)
                .tagResolver(resolver(amount, type, leverage))
                .display(StockConfig.string("position-creator.type-display"))
                .lore(StockConfig.stringList("position-creator.type-lore"))
                .build());
        this.update();
    }

    private void acceptUpperClick(InventoryClickEvent click) {
        upper = getNewUpper(click);

        ItemMeta meta = new ItemStackBuilder(Material.SLIME_BLOCK)
                .tagResolver(resolver(amount, type, leverage))
                .display(StockConfig.string("position-creator.upper-display"))
                .lore(StockConfig.stringList("position-creator.upper-lore"))
                .build().getItemMeta();

        upperItem.getItem().setItemMeta(meta);
        update();
    }

    private void acceptLowerClick(InventoryClickEvent click) {
        lower = getNewLower(click);

        ItemMeta meta = new ItemStackBuilder(Material.HONEY_BLOCK)
                .tagResolver(resolver(amount, type, leverage))
                .display(StockConfig.string("position-creator.lower-display"))
                .lore(StockConfig.stringList("position-creator.lower-lore"))
                .build().getItemMeta();

        lowerItem.getItem().setItemMeta(meta);
        update();
    }

    private void acceptCreateClick(InventoryClickEvent click) {
        click.setCancelled(true);
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            player.sendMessage("Stock not found! " + symbol);
            return;
        }
        StockPlayerManager.EconomyCheckResponse response = StockPlayerManager.economyCheck(stockPlayer, stock, amount, leverage);
        if (!response.success()) {
            boolean success = false;
            if (stockPlayer.isAutoTake()) {
                success = StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, response.lack());
            }
            if (!success) {
                GuiUtils.temporaryChange(createItem.getItem(),
                        MiniMessage.miniMessage().deserialize(StockConfig.string("position-creator.create-display-no-money")),
                        StockConfig.stringList("position-creator.create-lore-no-money").stream().map(MiniMessage.miniMessage()::deserialize).toList(),
                        100L, this::update
                );
                this.update();
                return;
            }
        }

        player.performCommand("arc-invest -t:" + type.command + " -s:" + symbol + " -amount:" + amount + " -leverage:" + leverage + " -up:" + upper + " -down:" + lower);
        new PositionSelector(player, symbol).show(player);
    }

    Position.AutoClosePrices marginCallAtPrice(double balance) {
        double bankruptPrice = stockPlayer.isAutoTake() ? -1 : stock.getPrice() - balance / amount / leverage;
        double lowMarginCallPrice = lower > 1_000_000_000 ? -1 : stock.getPrice() - lower / amount / leverage;
        double upperMarginCallPrice = upper > 1_000_000_000 ? -1 : stock.getPrice() + upper / amount / leverage;
        double low = Math.min(bankruptPrice, lowMarginCallPrice);
        return new Position.AutoClosePrices(low, upperMarginCallPrice);
    }


    private void acceptLeverageClick(InventoryClickEvent click) {
        click.setCancelled(true);
        leverage = getNewLeverage(click);
        ItemMeta meta = new ItemStackBuilder(Material.LEVER)
                .tagResolver(resolver(amount, type, leverage))
                .display(StockConfig.string("position-creator.leverage-display"))
                .lore(StockConfig.stringList("position-creator.leverage-lore"))
                .build().getItemMeta();

        leverageItem.getItem().setItemMeta(meta);
        update();
    }
}
