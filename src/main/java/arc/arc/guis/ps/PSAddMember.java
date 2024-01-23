package arc.arc.guis.ps;

import arc.arc.ARC;
import arc.arc.playerlist.PlayerManager;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import dev.espi.protectionstones.PSRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PSAddMember extends ChestGui {

    private static HashSet<UUID> cooldownSet = new HashSet<>();
    private GuiItem backGuiItem;
    private Map<UUID, GuiItem> playerItems = new HashMap<>();
    private PaginatedPane paginatedPane = new PaginatedPane(0,0,9,4);

    public PSAddMember(PSRegion region, Player player) {
        super(5, ChatColor.translateAlternateColorCodes('&', "&8&lДобавить участника"));

        OutlinePane bg = new OutlinePane(0,4,9,1);
        ItemStack bgItem = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bgItem.getItemMeta();
        meta.setCustomModelData(11000);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        bg.addItem(new GuiItem(bgItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        bg.setRepeat(true);
        bg.setPriority(Pane.Priority.LOWEST);
        this.addPane(bg);

        OutlinePane bg2 = new OutlinePane(0,0,9,4);
        ItemStack bgItem2 = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta met2 = bgItem2.getItemMeta();
        met2.displayName(Component.text(" "));
        bgItem2.setItemMeta(met2);
        bg2.addItem(new GuiItem(bgItem2, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
        }));
        bg2.setRepeat(true);
        bg2.setPriority(Pane.Priority.LOW);
        this.addPane(bg2);

        generateGuiItems(region, player);
        this.addPane(paginatedPane);

        StaticPane nav = new StaticPane(0,4,9,1);

        ItemStack backItem = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta meta2 = backItem.getItemMeta();
        meta2.displayName(Component.text("Назад",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta2.setCustomModelData(11013);
        backItem.setItemMeta(meta2);
        backGuiItem = new GuiItem(backItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            new PSMenu(region, player).show(player);
        });
        nav.addItem(backGuiItem, 0, 0);

        ItemStack refreshItem = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta3 = refreshItem.getItemMeta();
        meta3.displayName(Component.text("Обновить",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta3.setCustomModelData(11010);
        refreshItem.setItemMeta(meta3);
        nav.addItem(new GuiItem(refreshItem, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if(cooldownSet.contains(player.getUniqueId())){
                player.sendMessage(Component.text("Не так быстро!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                return;
            }
            tagCooldown(player);
            generateGuiItems(region, player);
            this.update();
        }),4,0);

        this.addPane(nav);

    }

    private void tagCooldown(Player player){
        cooldownSet.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldownSet.remove(player.getUniqueId());
            }
        }.runTaskLater(ARC.plugin, 20L);
    }

    private void generateGuiItems(PSRegion region, Player player){
        playerItems = new HashMap<>();
        /*Set<UUID> uuids = new HashSet<>(PlayerManager.getAllPlayerUuids());
        Set<UUID> toRemove = new HashSet<>();
        for(UUID uuid : uuids){
            if(region.isMember(uuid) || region.isOwner(uuid)){
                toRemove.add(uuid);
                continue;
            }
            generateGuiItem(uuid, player);
        }

        uuids.removeAll(toRemove);
        toRemove.clear();

        for(UUID uuid : uuids){
            if(region.isMember(uuid) || region.isOwner(uuid)){
                toRemove.add(uuid);
                continue;
            }
            generateGuiItem(uuid, player);
        }
        */
        for(var uuid : PlayerManager.getAllPlayerUuids()){
            if(region.isMember(uuid) || region.isOwner(uuid)) continue;
            generateGuiItem(uuid, player);
        }

        paginatedPane.populateWithGuiItems(playerItems.values().stream().toList());
    }

    void generateGuiItem(UUID uuid, Player inventoryHolder){
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer offlinePlayer;

        offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        if(offlinePlayer.getName() == null) return;
        meta.setOwningPlayer(offlinePlayer);
        meta.displayName(Component.text(offlinePlayer.getName(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(

                Component.text("Нажмите ", NamedTextColor.GRAY)
                .append(Component.text("ЛКМ ", NamedTextColor.GREEN))
                .append(Component.text("чтобы добавить участника", NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false),

                Component.text("Нажмите ", NamedTextColor.GRAY)
                        .append(Component.text("ПКМ ", NamedTextColor.GREEN))
                        .append(Component.text("чтобы добавить владельца", NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false)
                ));
        item.setItemMeta(meta);

        GuiItem guiItem = new GuiItem(item, inventoryClickEvent -> {
            inventoryClickEvent.setCancelled(true);
            if(inventoryClickEvent.isLeftClick()) inventoryHolder.performCommand("ps add "+offlinePlayer.getName());
            else if(inventoryClickEvent.isRightClick()) inventoryHolder.performCommand("ps addowner "+offlinePlayer.getName());
            playerItems.remove(uuid);
            paginatedPane.clear();
            paginatedPane.populateWithGuiItems(playerItems.values().stream().toList());
            this.update();
        });

        playerItems.put(uuid, guiItem);
    }
}
