package arc.arc.hooks.elitemobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.elitemobs.guis.EmShop;
import arc.arc.hooks.elitemobs.guis.ShopHolder;
import arc.arc.util.GuiUtils;
import com.Zrips.CMI.CMI;
import com.magmaguy.elitemobs.api.utils.EliteItemManager;
import com.magmaguy.elitemobs.economy.EconomyHandler;
import com.magmaguy.elitemobs.items.ScalableItemConstructor;
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor;
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class EMHook implements Listener {


    private static EMWormholes emWormholes;
    private static EMListener emListener;
    private static ShopHolder shopHolder;
    Config config;
    BukkitTask resetShopTask;
    public long lastShopReset = System.currentTimeMillis();


    public EMHook() {
        config = ConfigManager.getOrCreate(ARC.plugin.getDataFolder().toPath(), "elitemobs.yml", "elitemobs");

        if (emWormholes == null) {
            emWormholes = new EMWormholes(config);
            emWormholes.init();
        }

        if (emListener == null) {
            emListener = new EMListener(config);
            Bukkit.getPluginManager().registerEvents(emListener, ARC.plugin);
        }

        if (shopHolder == null) {
            shopHolder = new ShopHolder(this, config);
        }

        startTasks();
    }

    private void cancelTasks(){
        if(resetShopTask != null && !resetShopTask.isCancelled()) resetShopTask.cancel();
    }

    private void startTasks(){
        cancelTasks();
        long resetTime = config.integer("shop.reset-ticks", 20*60*5);
        resetShopTask = Bukkit.getScheduler().runTaskTimer(ARC.plugin, () -> shopHolder.deleteAll(), resetTime, resetTime);
    }


    public ItemStack generateDrop(int tier, Player player, boolean trinket) {
        if (trinket)
            return ScalableItemConstructor.randomizeScalableItem(tier, player, null);
        else
            return ItemConstructor.constructItem(tier, null, player, true);
    }

    public int tier(Player player){
        return ElitePlayerInventory.playerInventories.get(player.getUniqueId()).getFullPlayerTier(false);
    }



    public void reload() {
        if (emWormholes != null) {
            emWormholes.cancel();
            emWormholes = new EMWormholes(config);
            emWormholes.init();
        }

        resetShop();
        startTasks();
    }

    public void resetShop(){
        lastShopReset = System.currentTimeMillis();
        shopHolder.deleteAll();
    }

    public void cancel() {
        if (emWormholes != null) emWormholes.cancel();
    }


    public void openShopGui(Player player, boolean isGear) {
        GuiUtils.constructAndShowAsync(() -> new EmShop(config, player, shopHolder, isGear), player);
    }

    public double balance(Player player){
        return EconomyHandler.checkCurrency(player.getUniqueId());
    }

    public void addBalance(Player player, double amount){
        EconomyHandler.addCurrency(player.getUniqueId(), amount);
    }

    public void removeBalance(Player player, double amount){
        EconomyHandler.subtractCurrency(player.getUniqueId(), amount);
    }
}
