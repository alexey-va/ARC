package ru.arc.dungeons.api;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
/** Provider-authored book identity used by both book integrations. */
public final class ArcBookIdentity {
    private static final Set<String> AE_KEYS = Set.of("book", "ae_book", "ae_book_level", "ae_book_failure", "ae_book_success");
    private ArcBookIdentity() {}
    public static boolean hasAdvancedEnchantmentsMarker(Set<NamespacedKey> keys) {
        return keys.stream().anyMatch(key -> key.getNamespace().equals("advancedenchantments") && AE_KEYS.contains(key.getKey()));
    }
    public static boolean isAdvancedEnchantmentsBook(ItemStack item) {
        return item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()
            && hasAdvancedEnchantmentsMarker(item.getItemMeta().getPersistentDataContainer().getKeys());
    }
}
