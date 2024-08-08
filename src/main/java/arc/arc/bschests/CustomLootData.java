package arc.arc.bschests;

import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.RepoData;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class CustomLootData extends RepoData<CustomLootData> {

    private static final long TTL = 1000 * 60 * 60 * 24 * 7; // 7 days

    UUID playerUuid;
    UUID chestUuid;
    @Builder.Default
    long timestamp = System.currentTimeMillis();
    @Builder.Default
    ItemList items = new ItemList();
    boolean filled;

    @Override
    public String id() {
        return playerUuid.toString() + ":::" + chestUuid.toString();
    }

    @Override
    public boolean isRemove() {
        return System.currentTimeMillis() - timestamp > TTL || items == null || (isFilled() && items.isEmpty());
    }

    @Override
    public void merge(CustomLootData other) {
        if (other.items != null) {
            if (items == null) {
                items = new ItemList();
            }
            items.clear();
            items.addAll(other.items);
        }
    }

    public void removeItem(ItemStack item) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack itemStack = items.get(i);
            if (itemStack == null) continue;
            if (itemStack.isSimilar(item) && itemStack.getAmount() == item.getAmount()) {
                items.set(i, null);
                setDirty(true);
                return;
            }
        }
        log.error("Item not found in chest: " + item);
    }
}
