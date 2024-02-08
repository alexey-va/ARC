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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BuildingGui extends ChestGui {
    Player player;
    ConstructionSite site;
    boolean youSure = false;
    BukkitTask renameTask;
    public BuildingGui(Player player, ConstructionSite site) {
        super(3, TextHolder.deserialize("&8Потверждение строительства"), ARC.plugin);
        this.player = player;
        this.site = site;

        setupBg();
        setupButtons();
    }

    private void setupButtons(){
        StaticPane staticPane = new StaticPane(0,1, 9, 1);

        ItemStack confirmStack = new ItemStack(Material.PAPER);
        ItemStack cancelStack = new ItemStack(Material.RED_STAINED_GLASS_PANE);

        ItemMeta confirmMeta = confirmStack.getItemMeta();
        confirmMeta.displayName(TextUtil.strip(Component.text("Прогресс строительства", NamedTextColor.GOLD)));
        double progress = site.getProgress();
        int percentage = (int)(progress*100);
        confirmMeta.lore(List.of(TextUtil.strip(Component.text("> "+percentage+"%", NamedTextColor.GRAY))));
        new BukkitRunnable() {
            @Override
            public void run() {
                if(site.getState() != ConstructionSite.State.BUILDING){
                    this.cancel();
                    getViewers().forEach(HumanEntity::closeInventory);
                    return;
                }
                double progress = site.getProgress();
                int percentage = (int)(progress*100);
                ItemMeta meta = confirmStack.getItemMeta();
                meta.lore(List.of(TextUtil.strip(Component.text("> "+percentage+"%", NamedTextColor.GRAY))));
                confirmStack.setItemMeta(meta);
                update();
            }
        }.runTaskTimer(ARC.plugin, 20L, 60L);
        confirmStack.setItemMeta(confirmMeta);

        ItemMeta cancelMeta = cancelStack.getItemMeta();
        cancelMeta.displayName(TextUtil.strip(Component.text("Отменить постройку", NamedTextColor.DARK_RED)));
        if(BuildingConfig.cancelModelData != 0) cancelMeta.setCustomModelData(BuildingConfig.cancelModelData);
        cancelStack.setItemMeta(cancelMeta);

        GuiItem confirmItem = new GuiItem(confirmStack, inventoryClickEvent -> inventoryClickEvent.setCancelled(true));
        staticPane.addItem(confirmItem,2, 0);

        GuiItem cancelItem = new GuiItem(cancelStack, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);

            if(youSure){
                if(renameTask != null && !renameTask.isCancelled()) renameTask.cancel();
                BuildingManager.cancelConstruction(site);
                inventoryClickEvent.getWhoClicked().closeInventory();
            } else{
                youSure = true;
                ItemMeta meta = confirmStack.getItemMeta();
                meta.displayName(TextUtil.strip(Component.text("Вы уверены?", NamedTextColor.DARK_RED)));
                meta.lore(List.of(TextUtil.strip(Component.text("Вы не вернете книгу после отмены"))));
                cancelStack.setItemMeta(meta);

                renameTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        ItemMeta cancelMeta = cancelStack.getItemMeta();
                        cancelMeta.displayName(TextUtil.strip(Component.text("Отменить постройку", NamedTextColor.DARK_RED)));
                        cancelStack.setItemMeta(cancelMeta);
                    }
                }.runTaskLater(ARC.plugin, 5*20L);

                this.update();
            }

        });
        staticPane.addItem(cancelItem,6, 0);
        this.addPane(staticPane);
    }

    private void setupBg(){
        OutlinePane pane = new OutlinePane(0, 0, 9, 3);
        pane.addItem(GuiUtils.background());
        pane.setRepeat(true);
        pane.setPriority(Pane.Priority.LOWEST);
        this.addPane(pane);
    }
}
