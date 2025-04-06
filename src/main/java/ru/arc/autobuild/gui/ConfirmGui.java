package ru.arc.autobuild.gui;

import ru.arc.ARC;
import ru.arc.autobuild.BuildingManager;
import ru.arc.autobuild.ConstructionSite;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.GuiUtils;
import ru.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ConfirmGui extends ChestGui {
    Player player;
    ConstructionSite site;
    Config config = ConfigManager.of(ARC.plugin.getDataPath(), "auto-build.yml");

    public ConfirmGui(Player player, ConstructionSite site) {
        super(3, "", ARC.plugin);
        setTitle(TextHolder.deserialize(TextUtil.toLegacy(config.string("confirm-gui.title", "<dark_gray>Подтверждение постройки"))));
        this.player = player;
        this.site = site;

        setupBg();
        setupButtons();
    }

    private void setupButtons() {
        StaticPane staticPane = new StaticPane(0, 1, 9, 1);

        ItemStack confirmStack = new ItemStack(config.material("confirm-gui.confirm-material", Material.PAPER));
        ItemStack cancelStack = new ItemStack(config.material("confirm-gui.cancel-material", Material.RED_STAINED_GLASS_PANE));

        ItemMeta confirmMeta = confirmStack.getItemMeta();
        confirmMeta.displayName(config.componentDef("confirm-gui.confirm", "<green>Подтвердить постройку"));
        int confirmModelData = config.integer("confirm-gui.confirm-model-data", 0);
        if (confirmModelData != 0) confirmMeta.setCustomModelData(confirmModelData);
        confirmStack.setItemMeta(confirmMeta);

        ItemMeta cancelMeta = cancelStack.getItemMeta();
        cancelMeta.displayName(config.componentDef("confirm-gui.cancel", "<red>Отменить постройку"));
        int cancelModelData = config.integer("confirm-gui.cancel-model-data", 0);
        if (cancelModelData != 0) cancelMeta.setCustomModelData(cancelModelData);
        cancelStack.setItemMeta(cancelMeta);

        GuiItem confirmItem = new GuiItem(confirmStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if (removeBook()) BuildingManager.confirmConstruction(player, true);
            else {
                Component message = config.componentDef("confirm-gui.no-book", "<gray>\uD83D\uDEE0 <red>У вас нет книги в инвентаре!");
                player.sendMessage(message);
            }
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
        staticPane.addItem(confirmItem, 2, 0);

        GuiItem cancelItem = new GuiItem(cancelStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            BuildingManager.confirmConstruction(player, false);
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
        staticPane.addItem(cancelItem, 6, 0);
        this.addPane(staticPane);
    }

    private boolean removeBook() {
        ItemStack[] stacks = player.getInventory().getContents();
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack == null) continue;
            if (stack.getType() != Material.BOOK) continue;
            NBTItem nbtItem = new NBTItem(stack);
            if (nbtItem.getString("arc:building_key").equals(site.getBuilding().getFileName())) {
                if (stack.getAmount() == 1) {
                    player.getInventory().setItem(i, null);
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                }
                return true;
            }
        }
        return false;
    }

    private void setupBg() {
        OutlinePane pane = new OutlinePane(0, 0, 9, 3);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
