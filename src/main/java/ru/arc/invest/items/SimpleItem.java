package ru.arc.invest.items;

import lombok.AllArgsConstructor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

@AllArgsConstructor

public class SimpleItem extends GenericItem {

    Material material;

    public static SimpleItem deserialize(String type){
        return new SimpleItem(Material.matchMaterial(type.toUpperCase()));
    }

    public static SimpleItem fromMaterial(String s){
        Material material = Material.matchMaterial(s.toUpperCase());
        if(material == null) return null;
        return new SimpleItem(material);
    }

    @Override
    public String toString() {
        return "SimpleItem{" +
                "material=" + material +
                '}';
    }

    @Override
    public ItemStack stack() {
        return new ItemStack(material);
    }

    @Override
    public boolean fits(ItemStack stack) {
        return this.material == stack.getType();
    }
}
