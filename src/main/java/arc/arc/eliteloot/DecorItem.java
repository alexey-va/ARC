package arc.arc.eliteloot;

import arc.arc.util.TextUtil;
import de.tr7zw.changeme.nbtapi.NBT;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class DecorItem {
    Material material;
    @EqualsAndHashCode.Exclude
    double weight;
    int modelId;
    Color color;
    String iaNamespace;
    String iaId;

    public ItemStack toItemStack(LootType lootType){
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if(modelId != 0) itemMeta.setCustomModelData(modelId);
        if(color != null && itemMeta instanceof LeatherArmorMeta leatherArmorMeta){
            leatherArmorMeta.setColor(color);
        }
        if(iaNamespace != null && iaId != null){
            NBT.modify(itemStack, nbt -> {
                nbt.getOrCreateCompound("itemsadder");
                nbt.setString("namespace", iaNamespace);
                nbt.setString("id", iaId);
            });
        }
        List<Component> lore = new ArrayList<>();
        // add loot type lore
        lore.add(Component.text("Loot Type: " + lootType.name().toLowerCase(), NamedTextColor.GRAY));
        // add weight lore
        lore.add(Component.text("Weight: " + weight, NamedTextColor.GRAY));
        // add ia nbt data
        if(iaNamespace != null && iaId != null){
            lore.add(Component.text("IA: " + iaNamespace + ":" + iaId, NamedTextColor.GRAY));
        }
        // add model id lore
        if(modelId != 0){
            lore.add(Component.text("Model ID: " + modelId, NamedTextColor.GRAY));
        }
        itemMeta.lore(lore.stream().map(TextUtil::strip).toList());
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }
}
