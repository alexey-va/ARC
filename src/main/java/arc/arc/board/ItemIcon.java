package arc.arc.board;

import arc.arc.util.HeadUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

public class ItemIcon {

    public ItemStack icon;

    public ItemIcon(UUID uuid){
        icon = HeadUtil.getSkull(uuid);
    }

    public ItemIcon(Material material, int modelData){
        icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        meta.setCustomModelData(modelData);
        icon.setItemMeta(meta);
    }

}
