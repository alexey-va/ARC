package ru.arc.common.treasure.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.arc.ARC;
import ru.arc.TitleInput;
import ru.arc.board.guis.Inputable;
import ru.arc.common.treasure.Treasure;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.common.treasure.impl.GaussData;
import ru.arc.common.treasure.impl.SubPoolTreasure;
import ru.arc.common.treasure.impl.TreasureCommand;
import ru.arc.common.treasure.impl.TreasureItem;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.GuiUtils;
import ru.arc.util.ItemStackBuilder;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.error;

public class PoolGui extends ChestGui implements Inputable {

    private static int rows = 6;
    Player player;
    private static final Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");
    TreasurePool treasurePool;
    PaginatedPane paginatedPane;
    boolean destroyed = false;

    public PoolGui(Player player, TreasurePool treasurePool) {
        super(rows, TextHolder.deserialize(TextUtil.toLegacy(
                config.string("pool.title").replace("%pool%", treasurePool.getId())
        )));

        this.player = player;
        this.treasurePool = treasurePool;

        setupBackground();
        setupNavigation();
        setupItems();

        this.setOnGlobalClick(click -> {
            if (destroyed) {
                click.setCancelled(true);
                return;
            }
        });

        this.setOnBottomClick(click -> {
            if (click.isCancelled()) return;
            click.setCancelled(true);
            ItemStack clickedStack = click.getCurrentItem();
            if (clickedStack == null || clickedStack.getType() == Material.AIR) return;
            if (!click.isShiftClick()) return;
            TreasureItem treasureItem = TreasureItem.builder()
                    .stack(clickedStack.clone())
                    .minAmount(1)
                    .maxAmount(1)
                    .gaussData(null)
                    .build();
            treasureItem.setWeight(1);
            treasurePool.add(treasureItem);
            destroyed = true;
            GuiUtils.constructAndShowAsync(() -> new PoolGui(player, treasurePool), player);
        });
    }

    private void setupItems() {
        if (paginatedPane == null) {
            paginatedPane = new PaginatedPane(0, 0, 9, rows - 1);
            this.addPane(paginatedPane);
        }
        List<Treasure> treasures = treasurePool.getTreasures().stream()
                .sorted((a, b) -> Integer.compare(b.getWeight(), a.getWeight()))
                .toList();
        List<GuiItem> guiItems = new ArrayList<>();
        for (Treasure treasure : treasures) {
            try {
                GuiItem item = toGuiItem(treasure);
                guiItems.add(item);
            } catch (Exception e) {
                error("Error creating gui item", e);
            }
        }
        paginatedPane.populateWithGuiItems(guiItems);
    }

    private GuiItem toGuiItem(Treasure treasure) {
        String name = config.string("pool.type-" + treasure.getClass().getSimpleName());
        int weight = treasure.getWeight();
        ItemStack stack;
        List<String> lore = new ArrayList<>();
        if (treasure instanceof TreasureItem treasureItem) {
            stack = treasureItem.getStack();
            GaussData gaussData = treasureItem.getGaussData();
            lore = config.stringList("pool.treasure-lore-" + treasure.getClass().getSimpleName()).stream()
                    .map(s -> s.replace("%weight%", String.valueOf(weight)))
                    .map(s -> s.replace("%quantity%", treasureItem.getMinAmount() + "-" + treasureItem.getMaxAmount()))
                    .map(s -> s.replace("%model%", stack.getItemMeta().hasCustomModelData() ? String.valueOf(stack.getItemMeta().getCustomModelData()) : "0"))
                    .map(s -> s.replace("%material%", stack.getType().name()))
                    .map(s -> s.replace("%chance%", String.format("%.2f", treasure.chance()*100)))
                    .map(s -> s.replace("%gauss%", gaussData == null ? "none" : gaussData.toString()))
                    .map(s -> s.replace("%announce%", treasureItem.announce() == Boolean.TRUE ? "да" : "нет"))
                    .map(s -> s.replace("%globalMessage%", treasureItem.    globalMessage().orElse("нет")))
                    .flatMap(s -> {
                        if (s.contains("%message%")) {
                            String message = treasure.message().orElse("нет");
                            List<String> split = TextUtil.splitLoreString(message, 80, 2);
                            List<String> result = new ArrayList<>();
                            result.add(s.replace("%message%", split.getFirst()));
                            result.addAll(split.subList(1, split.size()));
                            return result.stream();
                        } else {
                            return Stream.of(s);
                        }
                    }).toList();
        } else if (treasure instanceof TreasureCommand treasureCommand) {
            stack = new ItemStack(Material.PAPER);
            lore = config.stringList("pool.treasure-lore-" + treasure.getClass().getSimpleName()).stream()
                    .map(s -> s.replace("%weight%", String.valueOf(weight)))
                    .map(s -> s.replace("%chance%", String.format("%.2f", treasure.chance()*100)))
                    .map(s -> s.replace("%announce%", treasureCommand.announce() == Boolean.TRUE ? "да" : "нет"))
                    .map(s -> s.replace("%globalMessage%", treasureCommand.globalMessage().orElse("нет")))
                    .flatMap(s -> {
                        if (s.contains("%command%")) {
                            return treasureCommand.getCommands().stream().map(c -> s.replace("%command%", c));
                        } else {
                            return Stream.of(s);
                        }
                    }).toList();
        } else if (treasure instanceof SubPoolTreasure subPoolTreasure) {
            stack = new ItemStack(Material.CHEST);
            lore = config.stringList("pool.treasure-lore-" + treasure.getClass().getSimpleName()).stream()
                    .map(s -> s.replace("%weight%", String.valueOf(weight)))
                    .map(s -> s.replace("%pool%", subPoolTreasure.getSubPoolId()))
                    .map(s -> s.replace("%chance%", String.format("%.2f", treasure.chance()*100))
                    ).toList();
        } else {
            stack = new ItemStack(Material.BARRIER);
        }

        return new ItemStackBuilder(stack)
                .display(TextUtil.mm(name, true))
                .lore(lore)
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    player.closeInventory();
                    GuiUtils.constructAndShowAsync(() -> new TreasureGui(player, treasurePool, treasure), player);
                }).build();
    }


    private void setupNavigation() {
        StaticPane nav = new StaticPane(0, rows - 1, 9, 1);
        nav.addItem(back(), 0, 0);

        GuiItem nextPage = new ItemStackBuilder(Material.ARROW)
                .display(config.string("pool.next-page"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    int currentPage = paginatedPane.getPage();
                    int totalPages = paginatedPane.getPages();
                    if (currentPage + 1 >= totalPages) return;
                    paginatedPane.setPage(currentPage + 1);
                    this.update();
                }).build();
        nav.addItem(nextPage, 5, 0);

        GuiItem prevPage = new ItemStackBuilder(Material.ARROW)
                .display(config.string("pool.prev-page"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    int currentPage = paginatedPane.getPage();
                    if (currentPage - 1 < 0) return;
                    paginatedPane.setPage(currentPage - 1);
                    this.update();
                }).build();
        nav.addItem(prevPage, 3, 0);

        GuiItem addCommand = new ItemStackBuilder(Material.COMMAND_BLOCK)
                .display(config.string("pool.add-command"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 0);
                    click.getWhoClicked().closeInventory();
                }).build();
        nav.addItem(addCommand, 8, 0);

        GuiItem setCommonMessage = new ItemStackBuilder(Material.WRITABLE_BOOK)
                .display(config.string("pool.set-common-message", "<gold>Установить общее сообщение"))
                .lore(config.stringList("pool.set-common-message-lore", List.of(
                                "<gray>Текущее сообщение: <white>%message%"
                        )).stream()
                        .flatMap(s -> {
                            if (s.contains("%message%")) {
                                String message = treasurePool.getCommonMessage();
                                List<String> split = TextUtil.splitLoreString(message, 80, 2);
                                if (split.isEmpty()) return Stream.of(s.replace("%message%", "<red>Нет"));
                                List<String> result = new ArrayList<>();
                                result.add(s.replace("%message%", split.getFirst()));
                                result.addAll(split.subList(1, split.size()));
                                return result.stream();
                            } else {
                                return Stream.of(s);
                            }
                        }).toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 1);
                    click.getWhoClicked().closeInventory();
                }).build();
        nav.addItem(setCommonMessage, 7, 0);

        GuiItem setGlobalCommonMessage = new ItemStackBuilder(Material.WRITABLE_BOOK)
                .display(config.string("pool.set-common-announce-message", "<gold>Установить общее глобальное сообщение"))
                .lore(config.stringList("pool.set-common-announce-message-lore", List.of(
                                "<gray>Текущее сообщение: <white>%message%"
                        )).stream()
                        .flatMap(s -> {
                            if (s.contains("%message%")) {
                                String message = treasurePool.getCommonAnnounceMessage();
                                List<String> split = TextUtil.splitLoreString(message, 80, 2);
                                if (split.isEmpty()) return Stream.of(s.replace("%message%", "<red>Нет"));
                                List<String> result = new ArrayList<>();
                                result.add(s.replace("%message%", split.getFirst()));
                                result.addAll(split.subList(1, split.size()));
                                return result.stream();
                            } else {
                                return Stream.of(s);
                            }
                        }).toList())
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 2);
                    click.getWhoClicked().closeInventory();
                }).build();
        nav.addItem(setGlobalCommonMessage, 6, 0);

        this.addPane(nav);
    }

    private GuiItem back() {
        return new ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
                .modelData(11013)
                .display(config.string("main.back"))
                .lore(config.stringList("main.back-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    GuiUtils.constructAndShowAsync(() -> new MainTreasuresGui(player), player);
                }).build();
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, rows - 1, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }

    @Override
    public void setParameter(int n, String s) {
        if (n == 0) {
            TreasureCommand treasureCommand = new TreasureCommand();
            treasureCommand.setCommands(List.of(s));
            treasureCommand.setWeight(1);
            treasurePool.add(treasureCommand);
            treasurePool.setDirty(true);
        } else if (n == 1) {
            treasurePool.setCommonMessage(s);
            treasurePool.setDirty(true);
        } else if (n == 2) {
            treasurePool.setCommonAnnounceMessage(s);
            treasurePool.setDirty(true);
        }
    }

    @Override
    public void proceed() {
        GuiUtils.constructAndShowAsync(() -> new PoolGui(player, treasurePool), player);
    }

    @Override
    public boolean satisfy(String input, int id) {
        return true;
    }

    @Override
    public Component denyMessage(String input, int id) {
        return TextUtil.mm(config.string("pool.invalid-command"), true);
    }

    @Override
    public Component startMessage(int id) {
        return switch (id) {
            case 0 -> config.componentDef("pool.add-command-input", "<gold>Введите команду (без /)");
            case 1 -> config.componentDef("pool.set-common-message-input",
                    "<gold>Введите общее сообщение <gray>(%amount%, %item% - плейсхолдеры)");
            case 2 -> config.componentDef("pool.set-common-announce-message-input",
                    "<gold>Введите общее глобальное сообщение <gray>(%amount%, %item%, %player_name% - плейсхолдеры)");
            default -> TextUtil.mm("Unknown id");
        };
    }
}
