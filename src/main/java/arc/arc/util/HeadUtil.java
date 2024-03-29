package arc.arc.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

public class HeadUtil {

    public static ItemStack getSkull(UUID uuid){
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        if(uuid == null) return item;
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        if(offlinePlayer.getPlayerProfile().getName() == null) return item;
        try {
            meta.setOwningPlayer(offlinePlayer);
            item.setItemMeta(meta);
            return item;
        } catch (Exception e){
            return item;
        }
    }

}
