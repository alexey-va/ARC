package arc.arc.hooks.elitemobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import com.magmaguy.elitemobs.items.ScalableItemConstructor;
import com.magmaguy.elitemobs.items.customitems.CustomItem;
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public class EMHook implements Listener {


    private static EMWormholes emWormholes;
    private static EMListener emListener;

    public EMHook() {
        if (emWormholes == null) {
            emWormholes = new EMWormholes();
            emWormholes.init();
        }

        if (emListener == null) {
            emListener = new EMListener();
            Bukkit.getPluginManager().registerEvents(emListener, ARC.plugin);
        }
    }


    public ItemStack generateDrop(int tier, Player player, int id) {
        if (id == 0)
            return ScalableItemConstructor.randomizeScalableItem(tier, player, null);
        else if(id == 1)
            return ItemConstructor.constructItem(tier, null, player, true);
        return null;
    }



    public void reload() {
        if (emWormholes != null) {
            emWormholes.cancel();
            emWormholes = new EMWormholes();
            emWormholes.init();
        }
    }

    public void cancel() {
        if (emWormholes != null) emWormholes.cancel();
    }


}
