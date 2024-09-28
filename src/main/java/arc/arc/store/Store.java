package arc.arc.store;

import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.RepoData;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@EqualsAndHashCode(callSuper = false)
@Data
public class Store extends RepoData<Store> {

    private static List<ItemMatcher> forbidden = List.of(
            ItemMatcher.ofRegex(".*shulker.*"),
            ItemMatcher.ofRegex(".*dragon.*"),
            ItemMatcher.sfItem(true),
            ItemMatcher.of(Material.BARRIER),
            ItemMatcher.of(Material.COMMAND_BLOCK),
            ItemMatcher.of(Material.COMMAND_BLOCK_MINECART),
            ItemMatcher.of(Material.STRUCTURE_BLOCK),
            ItemMatcher.of(Material.STRUCTURE_VOID),
            ItemMatcher.of(Material.JIGSAW),
            ItemMatcher.of(Material.DEBUG_STICK)
    );

    UUID uuid;
    ItemList itemList;
    int size = 9;

    transient Lock lock;

    public Store(UUID uuid) {
        this.uuid = uuid;
    }

    public Store() {
        this.lock = new ReentrantLock();
    }

    public List<ItemStack> getItemList() {
        if (itemList == null) {
            itemList = new ItemList();
        }
        return itemList;
    }

    public boolean hasSpace() {
        try {
            lock.lock();
            return itemList.size() < size;
        } finally {
            lock.unlock();
        }
    }

    public List<ItemStack> toItemStacks() {
        try {
            lock.lock();
            return itemList;
        } finally {
            lock.unlock();
        }
    }

    public boolean addItem(ItemStack item) {
        if (item == null) {
            System.out.println("Item is null");
            return false;
        }
        if (item.getType() == Material.AIR) {
            System.out.println("Item is air");
            return true;
        }
        if (forbidden.stream().anyMatch(matcher -> matcher.matches(item))) {
            System.out.println("Item is forbidden");
            return false;
        }
        try {
            lock.lock();
            if (!canFit(item)) return false;
            addAndDistribute(item);
            setDirty(true);
            return true;
        } finally {
            lock.unlock();
        }
    }


    public boolean removeItem(ItemStack item, int amount) {
        try {
            lock.lock();
            for (ItemStack stack : toItemStacks()) {
                if (stack.isSimilar(item)) {
                    if (stack.getAmount() > amount) {
                        stack.setAmount(stack.getAmount() - amount);
                        compact();
                        setDirty(true);
                        return true;
                    } else if (stack.getAmount() == amount) {
                        itemList.remove(stack);
                        compact();
                        setDirty(true);
                        return true;
                    } else {
                        amount -= stack.getAmount();
                        itemList.remove(stack);
                        compact();
                    }
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void compact() {
        List<ItemStack> current = new ArrayList<>(toItemStacks());
        itemList.clear();
        for (ItemStack stack : current) {
            addAndDistribute(stack);
        }
    }

    private void addAndDistribute(ItemStack item) {
        if (item == null) return;
        if (item.getType() == Material.AIR) return;
        int leftToFit = item.getAmount();
        for (ItemStack stack : toItemStacks()) {
            if (stack.isSimilar(item)) {
                int toAdd = Math.min(stack.getMaxStackSize() - stack.getAmount(), leftToFit);
                stack.setAmount(stack.getAmount() + toAdd);
                leftToFit -= toAdd;
            }
            if (leftToFit <= 0) return;
        }
        if (leftToFit > 0) {
            ItemStack toAdd = item.clone();
            toAdd.setAmount(leftToFit);
            itemList.add(toAdd);
        }
    }

    private boolean canFit(ItemStack stack) {
        if (stack == null) return false;
        if (stack.getType() == Material.AIR) return true;
        if (itemList.size() < size) return true;
        int leftToFit = stack.getAmount();
        for (ItemStack item : toItemStacks()) {
            if (item.isSimilar(stack)) {
                leftToFit -= item.getMaxStackSize() - item.getAmount();
            }
            if (leftToFit <= 0) return true;
        }
        return false;
    }


    @Override
    public String id() {
        return uuid.toString();
    }

    @Override
    public boolean isRemove() {
        return false;
    }

    @Override
    public void merge(Store other) {
        try {
            lock.lock();
            if (other.itemList != null) {
                this.itemList = other.itemList;
            }
        } finally {
            lock.unlock();
        }
    }
}
