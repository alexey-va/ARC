package ru.arc.board;

import ru.arc.util.HeadUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Objects;
import java.util.UUID;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class ItemIcon {

    Material material;
    UUID headUuid;
    int modelData = 0;

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

    public static ItemIcon of(ItemStack stack){
        ItemIcon icon = new ItemIcon();
        icon.setMaterial(stack.getType());
        if(stack.getItemMeta().hasCustomModelData()) icon.setModelData(stack.getItemMeta().getCustomModelData());
        if(stack.getType() == Material.PLAYER_HEAD){
            System.out.println(stack.serialize());
            SkullMeta skullMeta = (SkullMeta)stack.getItemMeta();
            if(skullMeta.getOwningPlayer() != null) {
                icon.setHeadUuid(skullMeta.getOwningPlayer().getUniqueId());
            }
        }
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemIcon icon)) return false;

        if (modelData != icon.modelData) return false;
        if (material != icon.material) return false;
        return Objects.equals(headUuid, icon.headUuid);
    }

    @Override
    public int hashCode() {
        int result = material != null ? material.hashCode() : 0;
        result = 31 * result + (headUuid != null ? headUuid.hashCode() : 0);
        result = 31 * result + modelData;
        return result;
    }
}
