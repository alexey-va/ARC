package ru.arc.bschests;

import ru.arc.network.repos.ItemList;
import ru.arc.network.repos.RepoData;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

import static ru.arc.util.Logging.error;

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
        //info("id() called {}", (playerUuid.toString() + ":::" + chestUuid.toString()));
        return playerUuid.toString() + ":::" + chestUuid.toString();
    }

    @Override
    public boolean isRemove() {
        return System.currentTimeMillis() - timestamp > TTL || items == null || (filled && items.isEmpty());
    }

    @Override
    public void merge(CustomLootData other) {
        //info("merge() called {}", other);
        //info("state before merge: {}", this);
        if (other.items != null) {
            if (items == null) {
                items = new ItemList();
            }
            items.clear();
            items.addAll(other.items);
        }
    }

    private int tryRemoveSlotItem(ItemStack item, int slot) {
        if (items.size() <= slot) {
            return item.getAmount();
        }
        ItemStack itemStack = items.get(slot);
        if (itemStack == null) {
            return item.getAmount();
        }
        if (itemStack.isSimilar(item)) {
            int amount = itemStack.getAmount();
            if (amount > item.getAmount()) {
                itemStack.setAmount(amount - item.getAmount());
                items.set(slot, itemStack);
                return 0;
            } else {
                items.set(slot, null);
                return item.getAmount() - amount;
            }
        }
        return item.getAmount();
    }

    public void removeItem(ItemStack item, int slot) {
        //setDirty(true);
        int amountLeft = tryRemoveSlotItem(item, slot);
        if (amountLeft == 0) {
            setDirty(true);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            amountLeft = tryRemoveSlotItem(item, i);
            if (amountLeft == 0) {
                setDirty(true);
                return;
            }
        }
        error("Item not found in chest: {} {}", item, slot);
    }

    public boolean isExhausted() {
        return items != null && filled && items.stream().allMatch(Objects::isNull);
    }
}
