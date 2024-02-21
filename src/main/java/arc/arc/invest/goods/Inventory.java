package arc.arc.invest.goods;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@RequiredArgsConstructor
public class Inventory implements ConfigurationSerializable {

    @Getter
    final List<ItemStack> items;
    @Setter @Getter
    volatile boolean inUse = false;

    public void add(ItemStack stack) {
        items.add(stack);
    }

    public void add(Collection<ItemStack> stacks) {
        items.addAll(stacks);
    }

    public void remove(Collection<ItemStack> itemsToRemove) {
        removeItemStacks(items, itemsToRemove);
    }

    public boolean contains(List<ItemStack> list){
        for (ItemStack need : list) {
            int needQuantity = need.getAmount();
            ItemStack one = need.asOne();
            for(ItemStack have : items) {
                if(!have.asOne().equals(one)) continue;
                int haveQuantity = have.getAmount();
                needQuantity-=haveQuantity;
                if(needQuantity <=0) break;
            }
            if(needQuantity > 0) return false;
        }
        return true;
    }

    public void trim(){
        Map<ItemStack, Integer> map = count();
        items.clear();
        for(var entry : map.entrySet()){
            int count = entry.getValue();
            while (count> 0 ){
                int amount = Math.min(64, count);
                ItemStack stack = entry.getKey().asQuantity(amount);
                items.add(stack);
                count-=amount;
            }
        }
        sort();
    }

    public void sort(){
        items.sort(Comparator.comparing(ItemStack::getType));
    }

    private Map<ItemStack, Integer> count(){
        Map<ItemStack, Integer> map = new HashMap<>();
        Iterator<ItemStack> iterator = items.iterator();
        while (iterator.hasNext()){
            ItemStack stack = iterator.next();
            if(stack == null || stack.getType() == Material.AIR){
                iterator.remove();
                continue;
            }
            ItemStack one = stack.asOne();
            map.merge(one, stack.getAmount(), Integer::sum);
        }
        return map;
    }


    private static void removeItemStacks(List<ItemStack> itemList, Collection<ItemStack> itemsToRemove) {
        Iterator<ItemStack> iterator = itemList.iterator();

        for (ItemStack itemToRemove : itemsToRemove) {
            int remainingQuantity = itemToRemove.getAmount();
            ItemStack one = itemToRemove.asOne();

            while (remainingQuantity > 0 && iterator.hasNext()) {
                ItemStack currentItem = iterator.next();
                if(!currentItem.asOne().equals(one)) continue;

                int currentQuantity = currentItem.getAmount();

                if (currentQuantity <= remainingQuantity) {
                    iterator.remove();
                    remainingQuantity -= currentQuantity;
                } else {
                    currentItem.setAmount(currentQuantity - remainingQuantity);
                    remainingQuantity = 0;
                }
            }
        }
    }

    public static Inventory deserialize(List<?> items) {
        List<ItemStack> stacks = new ArrayList<>();
        if (items == null) return new Inventory(stacks);
        for (Object o : items) {
            Map<String, Object> map = (Map<String, Object>) o;
            ItemStack stack = ItemStack.deserialize(map);
            stacks.add(stack);
        }

        return new Inventory(stacks);
    }


    @Override
    public @NotNull Map<String, Object> serialize() {
        return Map.of("storage", items.stream().map(ItemStack::serialize).toList());
    }
}
