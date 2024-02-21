package arc.arc.stock.gui;

import arc.arc.configs.StockConfig;
import arc.arc.stock.Position;
import arc.arc.stock.StockPlayer;
import arc.arc.stock.StockPlayerManager;
import arc.arc.stock.StockPlayerMessager;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static arc.arc.util.TextUtil.*;

public class PositionMenu extends ChestGui {
    Player player;
    StockPlayer stockPlayer;
    Position position;

    GuiItem back, close;
    boolean confirm = false;

    public PositionMenu(Player player, Position position) {
        super(2, TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("position-menu.menu-title"),
                "uuid", position.getPositionUuid().toString().split("-")[0])));
        this.player = player;
        this.stockPlayer = StockPlayerManager.getOrCreate(player);
        this.position = position;

        setupBackground();
        setupButtons();
        setupNav();
    }

    private void setupButtons() {
        TagResolver resolver = position.resolver();

        StaticPane staticPane = new StaticPane(0, 0, 9, 1);
        staticPane.addItem(infoItem(resolver), 1, 0);
        staticPane.addItem(closeItem(resolver), 7, 0);
        this.addPane(staticPane);
    }

    private void setupNav() {
        StaticPane pane = new StaticPane(0, 1, 9, 1);
        this.addPane(pane);

        back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-menu.back-display"))
                .lore(StockConfig.stringList("position-menu.back-lore"))
                .modelData(11013)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new PositionSelector(player, position.getSymbol()).show(player);
                }).build();
        pane.addItem(back, 0, 0);
    }

    private GuiItem closeItem(TagResolver resolver) {
        close = new ItemStackBuilder(Material.RED_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-menu.close-button-display"))
                .lore(StockConfig.stringList("position-menu.close-button-lore"))
                .tagResolver(resolver)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);

                    if (!confirm) {
                        GuiUtils.temporaryChange(close.getItem(), mm(StockConfig.string("position-menu.close-button-display"), resolver),
                                StockConfig.stringList("position-menu.close-button-lore").stream().map(s -> mm(s, resolver)).toList(),
                                100L, () -> {
                                    this.confirm = false;
                                    this.update();
                                });
                        confirm = true;
                        return;
                    }

                    player.performCommand("arc-invest -t:close -s:" + position.getSymbol() + " -uuid:" + position.getPositionUuid()+" -reason:2");
                    new PositionSelector(player, position.getSymbol()).show(player);
                }).build();
        return close;
    }

    private GuiItem infoItem(TagResolver resolver) {
        Position.AutoClosePrices autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.isAutoTake());
        return new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .display(StockConfig.string("position-menu.info-button-display"))
                .lore(StockConfig.stringList("position-menu.info-button-lore"))
                .tagResolver(resolver)
                .appendResolver("close_at_low", autoClosePrices.low() == -1 ? "<red>Нет" :
                        formatAmount(autoClosePrices.low()))
                .appendResolver("close_at_high", autoClosePrices.high() == -1 ? "<red>Нет" :
                        formatAmount(autoClosePrices.high()))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                }).build();
    }


    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 2);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
