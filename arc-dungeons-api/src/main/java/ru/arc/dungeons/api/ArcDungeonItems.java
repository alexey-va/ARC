package ru.arc.dungeons.api;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
/** ARC-owned presets and atomic inventory application shared with AdvancedEnchantments. */
public interface ArcDungeonItems {
    record MagicDust(int boost, boolean lowerDestroyChance) {}
    List<ItemStack> resolvePreset(String preset, int amount);
    MagicDust readMagicDust(ItemStack item);
    ItemStack presentAdvancedBook(ItemStack item, Player viewer);
    Integer admitCreativeBook(Player player, ItemStack book);
    void applyBook(Player player, int targetSlot, ItemStack expectedTarget, ItemStack expectedBook,
                   ItemStack result, Integer bookInventorySlot);
}
