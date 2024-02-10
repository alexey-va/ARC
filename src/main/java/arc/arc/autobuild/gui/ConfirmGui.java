package arc.arc.autobuild.gui;

import arc.arc.ARC;
import arc.arc.autobuild.BuildingManager;
import arc.arc.autobuild.ConstructionSite;
import arc.arc.configs.BuildingConfig;
import arc.arc.util.GuiUtils;
import arc.arc.util.TextUtil;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class ConfirmGui extends ChestGui {
    Player player;
    ConstructionSite site;

    public ConfirmGui(Player player, ConstructionSite site) {
        super(3, TextHolder.deserialize("&8Потверждение строительства"), ARC.plugin);
        this.player = player;
        this.site = site;

        setupBg();
        setupButtons();
    }

    private void setupButtons() {
        StaticPane staticPane = new StaticPane(0, 1, 9, 1);

        ItemStack confirmStack = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemStack cancelStack = new ItemStack(Material.RED_STAINED_GLASS_PANE);

        ItemMeta confirmMeta = confirmStack.getItemMeta();
        confirmMeta.displayName(TextUtil.strip(Component.text("Потвердить постройку", NamedTextColor.DARK_GREEN)));
        if (BuildingConfig.confirmModelData != 0) confirmMeta.setCustomModelData(BuildingConfig.confirmModelData);
        confirmStack.setItemMeta(confirmMeta);

        ItemMeta cancelMeta = cancelStack.getItemMeta();
        cancelMeta.displayName(TextUtil.strip(Component.text("Отменить постройку", NamedTextColor.DARK_RED)));
        if (BuildingConfig.cancelModelData != 0) cancelMeta.setCustomModelData(BuildingConfig.cancelModelData);
        cancelStack.setItemMeta(cancelMeta);

        GuiItem confirmItem = new GuiItem(confirmStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            removeBook();
            BuildingManager.confirmConstruction(player, true);
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
        staticPane.addItem(confirmItem, 2, 0);

        GuiItem cancelItem = new GuiItem(cancelStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if(removeBook()) BuildingManager.confirmConstruction(player, false);
            else{
                player.sendMessage(TextUtil.strip(
                        Component.text("&7\uD83D\uDEE0 ", NamedTextColor.GRAY)
                                .append(Component.text("У вас нет книги в инвентаре!", NamedTextColor.RED))
                ));
            }
            inventoryClickEvent.getWhoClicked().closeInventory();
        });
        staticPane.addItem(cancelItem, 6, 0);
        this.addPane(staticPane);
    }

    private boolean removeBook() {
        ItemStack[] stacks = player.getInventory().getContents();
        for(int i=0;i<stacks.length;i++){
            ItemStack stack = stacks[i];
            if(stack == null) continue;
            if(stack.getType() != Material.BOOK) continue;
            NBTItem nbtItem = new NBTItem(stack);
            if(nbtItem.getString("arc:building_key").equals(site.getBuilding().getFileName())){
                if(stack.getAmount() == 1){
                    player.getInventory().setItem(i, null);
                } else{
                    stack.setAmount(stack.getAmount()-1);
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
