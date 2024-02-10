package arc.arc.board;

import arc.arc.network.ArcSerializable;
import arc.arc.util.HeadUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class ItemIcon extends ArcSerializable {

    Material material;
    UUID headUuid;
    int modelData;

    public static ItemIcon of(UUID uuid){
        ItemIcon icon = new ItemIcon();
        icon.setMaterial(Material.PLAYER_HEAD);
        icon.setHeadUuid(uuid);
        return icon;
    }


    public static ItemIcon of(Material material, int modelData){
        ItemIcon icon = new ItemIcon();
        icon.setMaterial(material);
        icon.setModelData(modelData);
        return icon;
    }

    public ItemStack stack(){
        if(material == Material.PLAYER_HEAD) return HeadUtil.getSkull(headUuid);
        ItemStack stack = new ItemStack(material);
        if(modelData != 0) {
            ItemMeta meta = stack.getItemMeta();
            meta.setCustomModelData(modelData);
            stack.setItemMeta(meta);
        }
        return stack;
    }

}
