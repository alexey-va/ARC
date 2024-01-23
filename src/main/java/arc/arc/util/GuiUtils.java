package arc.arc.util;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

public class GuiUtils {

    private static Map<BgKey, GuiItem> backgrounds;
    record BgKey(Material material, int model){};

    public static GuiItem background(Material material, int model){
        GuiItem guiItem = backgrounds.get(new BgKey(material, model));
        if(guiItem != null) return guiItem;

        ItemStack bgItem = new ItemStack(material);
        ItemMeta meta = bgItem.getItemMeta();
        if(model != 0) meta.setCustomModelData(model);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        guiItem = new GuiItem(bgItem, inventoryClickEvent -> inventoryClickEvent.setCancelled(true));
        backgrounds.put(new BgKey(material, model), guiItem);

        return guiItem;
    }

    public static GuiItem background(Material material){
        return background(material, 0);
    }

    public static GuiItem background(){
        return background(Material.GRAY_STAINED_GLASS_PANE, 0);
    }

}
