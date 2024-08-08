package arc.arc.eliteloot;

import arc.arc.util.GuiItemBuilder;
import arc.arc.util.GuiUtils;
import arc.arc.util.ItemStackBuilder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class EliteLootGui extends ChestGui {

    Player player;
    PaginatedPane itemPane;
    StaticPane navPane;

    public EliteLootGui(Player player) {
        super(6, "EliteLoot");
        populate();
        setupNavigation();
        setupBackground();
        this.player = player;
    }

    private void populate() {
        if (itemPane == null) itemPane = new PaginatedPane(0, 0, 9, 5);
        else itemPane.clear();

        List<GuiItem> itemList = new ArrayList<>();

        var map = EliteLootManager.getMap();
        for (LootType lootType : map.keySet()) {
            DecorPool pool = map.get(lootType);
            TreeMap<Double, DecorItem> decors = pool.getDecors();
            for (var entry : decors.entrySet()) {
                DecorItem decorItem = entry.getValue();
                GuiItem guiItem = new GuiItemBuilder(decorItem.toItemStack(lootType))
                        .clickEvent(e -> e.setCancelled(true)).build();
                itemList.add(guiItem);
            }
        }

        itemPane.populateWithGuiItems(itemList);

        this.addPane(itemPane);
    }

    private void setupNavigation() {
        StaticPane nav = new StaticPane(0, 5, 9, 1);
        GuiItem nextPageItem = new ItemStackBuilder(Material.ARROW)
                .display("Next Page")
                .toGuiItemBuilder()
                .clickEvent(e -> {
                    e.setCancelled(true);
                }).build();
        this.addPane(nav);
    }

    private void setupBackground() {
        OutlinePane pane = new OutlinePane(0, 5, 9, 1);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
