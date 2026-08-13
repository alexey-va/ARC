package me.dartanman.duels.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class KitDefinition {
    private final Map<Integer, ItemStack> storage;
    private final ItemStack helmet;
    private final ItemStack chestplate;
    private final ItemStack leggings;
    private final ItemStack boots;
    private final ItemStack offhand;

    public KitDefinition(
            Map<Integer, ItemStack> storage,
            ItemStack helmet,
            ItemStack chestplate,
            ItemStack leggings,
            ItemStack boots,
            ItemStack offhand
    ) {
        this.storage = Collections.unmodifiableMap(new HashMap<>(storage));
        this.helmet = cloneOrNull(helmet);
        this.chestplate = cloneOrNull(chestplate);
        this.leggings = cloneOrNull(leggings);
        this.boots = cloneOrNull(boots);
        this.offhand = cloneOrNull(offhand);
    }

    public void apply(PlayerInventory inventory) {
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        storage.forEach((slot, item) -> inventory.setItem(slot, item.clone()));
        inventory.setHelmet(cloneOrNull(helmet));
        inventory.setChestplate(cloneOrNull(chestplate));
        inventory.setLeggings(cloneOrNull(leggings));
        inventory.setBoots(cloneOrNull(boots));
        inventory.setItemInOffHand(cloneOrNull(offhand));
    }

    public boolean isEmpty() {
        return storage.isEmpty()
                && helmet == null
                && chestplate == null
                && leggings == null
                && boots == null
                && offhand == null;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
