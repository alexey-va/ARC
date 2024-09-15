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

import java.util.ArrayList;
import java.util.List;

public class MainTreasuresGui extends ChestGui implements Inputable {
    private static int rows = 6;
    private final Player player;
    private final Config config;
    PaginatedPane paginatedPane;

    public MainTreasuresGui(Player player, Config config) {
        super(rows, TextUtil.toLegacy(config.string("main.title")));
        this.player = player;
        this.config = config;

        setupBackground();
        setupNavigation();
        setupPools();
    }

    private void setupPools() {
        if (paginatedPane == null) {
            paginatedPane = new PaginatedPane(0, 0, 9, rows - 1);
            this.addPane(paginatedPane);
        }
        List<GuiItem> guiItemList = new ArrayList<>();
        for (TreasurePool pool : TreasurePool.getTreasurePools()) {
            GuiItem item = new ItemStackBuilder(Material.CHEST)
                    .display(pool.getId())
                    .lore(config.stringList("main.pool-lore").stream()
                            .map(s -> s.replace("%size%", String.valueOf(pool.size())))
                            .toList())
                    .toGuiItemBuilder()
                    .clickEvent(click -> {
                        click.setCancelled(true);
                        GuiUtils.constructAndShowAsync(() -> new PoolGui(config, player, pool), player);
                    }).build();
            guiItemList.add(item);
        }
        paginatedPane.populateWithGuiItems(guiItemList);
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

        GuiItem createPoolGuiItem = new ItemStackBuilder(Material.CHEST)
                .display(config.string("main.create-pool"))
                .lore(config.stringList("main.create-pool-lore"))
                .toGuiItemBuilder()
                .clickEvent(click -> {
                    click.setCancelled(true);
                    new TitleInput(player, this, 0);
                    click.getWhoClicked().closeInventory();
                }).build();
        nav.addItem(createPoolGuiItem, 8, 0);

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
            TreasurePool existing = TreasurePool.getOrCreate(s);
            GuiUtils.constructAndShowAsync(() -> new PoolGui(config, player, existing), player);
        }
    }

    @Override
    public void proceed() {
        GuiUtils.constructAndShowAsync(() -> new MainTreasuresGui(player, config), player);
    }

    @Override
    public boolean satisfy(String input, int id) {
        if (id == 0) {
            return TreasurePool.getTreasurePool(input) == null;
        }
        return false;
    }

    @Override
    public Component denyMessage(String input, int id) {
        return TextUtil.mm(config.string("main.create-pool-deny"));
    }

    @Override
    public Component startMessage(int id) {
        return TextUtil.mm(config.string("main.create-pool-start"));
    }
}
