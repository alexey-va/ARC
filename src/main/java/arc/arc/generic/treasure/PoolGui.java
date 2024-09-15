package arc.arc.generic.treasure;

import arc.arc.TitleInput;
import arc.arc.board.guis.Inputable;
import arc.arc.configs.Config;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class PoolGui extends ChestGui implements Inputable {

    private static int rows = 6;
    Player player;
    Config config;
    TreasurePool treasurePool;
    PaginatedPane paginatedPane;
    boolean destroyed = false;

    public PoolGui(Config config, Player player, TreasurePool treasurePool) {
        super(rows, TextUtil.toLegacy(
                config.string("pool.title").replace("%pool%", treasurePool.getId())
        ));

        this.config = config;
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
                    .weight(1)
                    .quantity(1)
                    .gaussData(null)
                    .build();
            treasurePool.add(treasureItem);
            destroyed = true;
            GuiUtils.constructAndShowAsync(() -> new PoolGui(config, player, treasurePool), player);
        });
    }

    private void setupItems() {
        if (paginatedPane == null) {
            paginatedPane = new PaginatedPane(0, 0, 9, rows - 1);
            this.addPane(paginatedPane);
        }
        List<Treasure> treasures = treasurePool.getTreasures().stream()
                .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
                .toList();
        List<GuiItem> guiItems = new ArrayList<>();
        for (Treasure treasure : treasures) {
            GuiItem item = toGuiItem(treasure);
            guiItems.add(item);
        }
        paginatedPane.populateWithGuiItems(guiItems);
    }

    private GuiItem toGuiItem(Treasure treasure) {
        String name = config.string("pool.type-" + treasure.getClass().getSimpleName());
        int weight = treasure.weight();
        ItemStack stack;
        List<String> lore = new ArrayList<>();
        if (treasure instanceof TreasureItem treasureItem) {
            stack = treasureItem.getStack();
            GaussData gaussData = treasureItem.getGaussData();
            lore = config.stringList("pool.treasure-lore").stream()
                    .map(s -> s.replace("%weight%", String.valueOf(weight)))
                    .map(s -> s.replace("%quantity%", treasureItem.getQuantity() + ""))
                    .map(s -> s.replace("%model%", stack.getItemMeta().hasCustomModelData() ? String.valueOf(stack.getItemMeta().getCustomModelData()) : "0"))
                    .map(s -> s.replace("%material%", stack.getType().name()))
                    .map(s -> s.replace("%gauss%", gaussData == null ? "none" : gaussData.toString()))
                    .toList();
        } else if (treasure instanceof TreasureCommand treasureCommand) {
            stack = new ItemStack(Material.PAPER);
            lore = config.stringList("pool.treasure-lore").stream()
                    .map(s -> s.replace("%weight%", String.valueOf(treasure.weight())))
                    .map(s -> s.replace("%command%", treasureCommand.getCommand()))
                    .toList();
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
                    GuiUtils.constructAndShowAsync(() -> new TreasureGui(config, player, treasurePool, treasure), player);
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
                    player.closeInventory();
                    player.performCommand(config.string("main.back-command"));
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
            treasureCommand.setCommand(s);
            treasureCommand.setWeight(1);
            treasurePool.add(treasureCommand);
            treasurePool.setDirty(true);
        }
    }

    @Override
    public void proceed() {
        GuiUtils.constructAndShowAsync(() -> new PoolGui(config, player, treasurePool), player);
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
        return TextUtil.mm(config.string("pool.add-command"), true);
    }
}
