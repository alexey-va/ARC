package arc.arc.store;

import arc.arc.hooks.HookRegistry;
import de.tr7zw.changeme.nbtapi.NBT;
import lombok.Builder;
import lombok.Data;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Builder
@Data
public class ItemMatcher {

    Material material;
    String materialName;
    Set<String> nbt;
    Boolean hasModelData;
    Boolean isSfItem;

    public boolean matches(ItemStack stack) {
        if (this.material != null && stack.getType() == this.material) return true;
        if (this.nbt != null && !this.nbt.isEmpty()) {
            AtomicBoolean allMatch = new AtomicBoolean(false);
            NBT.get(stack, readableItemNBT -> {
                allMatch.set(nbt.stream().allMatch(readableItemNBT::hasTag));
            });
            if (allMatch.get()) return true;
        }
        if (materialName != null) {
            String mat = stack.getType().name().toLowerCase();
            if (mat.matches(materialName)) return true;
        }
        if (hasModelData != null && stack.getItemMeta() != null) {
            if (stack.getItemMeta().hasCustomModelData() == hasModelData) return true;
        }
        if (isSfItem != null) {
            if (stack.getItemMeta() == null) {

            } else {
                if (HookRegistry.sfHook != null &&
                        HookRegistry.sfHook.isSlimefunItem(stack) == isSfItem) return true;
            }
        }
        return false;
    }

    public static ItemMatcher of(Material material) {
        return ItemMatcher.builder().material(material).build();
    }

    public static ItemMatcher ofNbt(String... strings) {
        return ItemMatcher.builder().nbt(Set.of(strings)).build();
    }

    public static ItemMatcher ofRegex(String regex) {
        return ItemMatcher.builder().materialName(regex).build();
    }

    public static ItemMatcher sfItem(boolean isSfItem) {
        return ItemMatcher.builder().isSfItem(isSfItem).build();
    }

    public static ItemMatcher modelData(boolean hasModelData) {
        return ItemMatcher.builder().hasModelData(hasModelData).build();
    }

}
