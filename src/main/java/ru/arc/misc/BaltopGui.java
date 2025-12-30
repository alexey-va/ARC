package ru.arc.misc;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.CooldownManager;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.info;
import static ru.arc.util.TextUtil.formatAmount;
import static ru.arc.util.TextUtil.mm;

public class BaltopGui extends ChestGui {

    record BaltopGuiItem(GuiItem item, double balance, double bank, double total) {
    }

    private static List<BaltopGuiItem> cachedItems = new CopyOnWriteArrayList<>();
    private static long lastUpdate = 0;

    final Config config;
    final Player player;

    int rows;

    GuiItem nextItem, prevItem, sortItem;
    PaginatedPane pane;
    Sort sort = Sort.TOTAL;

    public BaltopGui(Config config, Player player) {
        super(6, TextHolder.deserialize(
                TextUtil.toLegacy(config.string("baltop.title"))
        ));
        this.config = config;
        this.player = player;

        rows = config.integer("baltop.rows", 6);
        setRows(rows);

        setupBackground();
        setupPlayers();
        setupNavigation();
    }

    private void setupNavigation() {
        StaticPane nav = new StaticPane(0, rows - 1, 9, 1);
        nextItem = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11008)
                .display(config.string("baltop.next.name"))
                .lore(config.stringList("baltop.next.lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (pane.getPage() == pane.getPages() - 1) return;
                    pane.setPage(pane.getPage() + 1);
                    this.update();
                }).build();

        prevItem = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11009)
                .display(config.string("baltop.previous.name"))
                .lore(config.stringList("baltop.previous.lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    if (pane.getPage() == 0) return;
                    pane.setPage(pane.getPage() - 1);
                    this.update();
                }).build();

        sortItem = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11021)
                .display(config.string("baltop.sort.name")
                        .replace("<sort>", switch (sort) {
                            case BALANCE -> config.string("baltop.sort.balance");
                            case BANK -> config.string("baltop.sort.bank");
                            case TOTAL -> config.string("baltop.sort.total");
                        }))
                .lore(config.stringList("baltop.sort.lore").stream()
                        .map(s -> s.replace("<sort>", switch (sort) {
                            case BALANCE -> config.string("baltop.sort.balance");
                            case BANK -> config.string("baltop.sort.bank");
                            case TOTAL -> config.string("baltop.sort.total");
                        })).toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);

                    if (CooldownManager.cooldown(player.getUniqueId(), "baltop_sort") != 0) {
                        GuiUtils.temporaryChange(sortItem.getItem(),
                                mm(config.string("baltop.sort.cooldown"), true),
                                null, 20 * 3, this::update);
                        return;
                    }

                    CooldownManager.addCooldown(player.getUniqueId(), "baltop_sort", 1000);

                    switch (sort) {
                        case BALANCE -> sort = Sort.BANK;
                        case BANK -> sort = Sort.TOTAL;
                        case TOTAL -> sort = Sort.BALANCE;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(ARC.plugin, () -> {
                        setupPlayers();
                        this.update();
                    });
                }).build();


        GuiItem back = new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11013)
                .display(config.string("baltop.back.name"))
                .lore(config.stringList("baltop.back.lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    player.closeInventory();
                    player.performCommand(config.string("baltop.back-command"));
                }).build();
        nav.addItem(nextItem, 5, 0);
        nav.addItem(prevItem, 3, 0);
        nav.addItem(sortItem, 4, 0);
        nav.addItem(back, 0, 0);
        this.addPane(nav);
    }

    @SneakyThrows
    private void setupPlayers() {
        var future = updateCache(this::update);
        if (!Bukkit.isPrimaryThread()) future.get();
        pane = new PaginatedPane(0, 0, 9, rows - 1);
        Comparator<BaltopGuiItem> comparator = switch (sort) {
            case BALANCE -> Comparator.comparingDouble(BaltopGuiItem::balance).reversed();
            case BANK -> Comparator.comparingDouble(BaltopGuiItem::bank).reversed();
            default -> Comparator.comparingDouble(BaltopGuiItem::total).reversed();
        };
        pane.populateWithGuiItems(cachedItems.stream().sorted(comparator).map(BaltopGuiItem::item).toList());
        this.addPane(pane);
    }


    private CompletableFuture<Void> updateCache(Runnable callback) {
        if (HookRegistry.redisEcoHook == null) return CompletableFuture.completedFuture(null);
        if (System.currentTimeMillis() - lastUpdate <= 60000) return CompletableFuture.completedFuture(null);
        info("Updating baltop cache");
        return HookRegistry.redisEcoHook.getTopAccounts(224)
                .thenAccept(accounts -> {
                    record Account(String name, UUID uuid, double balance, double bank) {
                    }
                    var list = accounts.stream().map(account -> {
                                double bank = HookRegistry.bankHook == null ? 0 :
                                        HookRegistry.bankHook.offlineBalance(account.uuid.toString());
                                return new Account(account.name, account.uuid,
                                        account.balance + bank, bank);
                            })
                            .filter(account -> account.balance() > 0.0)
                            //.sorted(Comparator.comparing(Account::balance).reversed())
                            .map(account -> generateGuiitem(account.name(), account.uuid(), account.balance(), account.bank()))
                            .toList();
                    cachedItems.clear();
                    cachedItems.addAll(list);
                }).thenAccept(v -> callback.run());
    }

    private BaltopGuiItem generateGuiitem(String name, UUID uuid, double total, double bank) {
        GuiItem guiItem = new ItemStackBuilder(Material.PLAYER_HEAD)
                .skull(uuid)
                .display(config.string("baltop.item.name")
                        .replace("<player>", name)
                        .replace("<balance>", formatAmount(total - bank))
                        .replace("<total>", formatAmount(total))
                        .replace("<bank>", formatAmount(bank)))
                .lore(config.stringList("baltop.item.lore").stream()
                        .map(s -> s.replace("<balance>", formatAmount(total - bank))
                                .replace("<bank>", formatAmount(bank))
                                .replace("<total>", formatAmount(total))
                                .replace("<player>", name))
                        .collect(Collectors.toList()))
                .toGuiItemBuilder()
                .clickEvent(click -> click.setCancelled(true))
                .build();
        return new BaltopGuiItem(guiItem, total - bank, bank, total);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);

        OutlinePane pane2 = new OutlinePane(0, 0, 9, rows - 1);
        pane2.addItem(
                new ItemStackBuilder(Material.GRAY_STAINED_GLASS_PANE)
                        .display(" ")
                        .toGuiItemBuilder()
                        .clickEvent(click -> {
                            click.setCancelled(true);
                        }).build()
        );
        pane2.setRepeat(true);
        pane2.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane2);
    }


    enum Sort {
        BALANCE, BANK, TOTAL
    }
}
