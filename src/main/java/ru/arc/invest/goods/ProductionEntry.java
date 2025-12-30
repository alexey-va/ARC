package ru.arc.invest.goods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import ru.arc.invest.items.GenericItem;
import ru.arc.util.ItemUtils;

@AllArgsConstructor
@ToString
public class ProductionEntry {

    record Good(GenericItem genericItem, int min, int max, double chance, double durability){}
    public record UnsatisfiedDemand(String name, Map<Good, Integer> map){}

    int minLevel;
    int maxLevel;
    List<Good> produce;
    List<Good> consumption;
    @Getter
    String name;


    public List<ItemStack> production(int level){
        List<ItemStack> stacks = new ArrayList<>();
        if(level<minLevel || level > maxLevel) return stacks;

        for(Good good : produce){
            if(ThreadLocalRandom.current().nextDouble()>good.chance) continue;
            int amount = ThreadLocalRandom.current().nextInt(good.min, good.max+1);
            stacks.addAll(ItemUtils.split(good.genericItem.stack(), amount));
        }
        return stacks;
    }

    public List<ItemStack> reqs(int level){
        List<ItemStack> stacks = new ArrayList<>();
        if(level<minLevel || level > maxLevel) return stacks;

        for(Good good : consumption){
            if(ThreadLocalRandom.current().nextDouble()>good.chance) continue;
            int amount = ThreadLocalRandom.current().nextInt(good.min, good.max+1);
            stacks.addAll(ItemUtils.split(good.genericItem.stack(), amount));
        }
        return stacks;
    }

    public Optional<UnsatisfiedDemand> possible(Collection<ItemStack> stacks){
        List<ItemStack> cloneStacks = stacks.stream()
                .map(ItemStack::asOne)
                .toList();
        UnsatisfiedDemand demand = new UnsatisfiedDemand(name, new HashMap<>());
        for(Good good : consumption){
            if (good.chance < 1.0) continue;
            int need = good.min;

            for (ItemStack stack : cloneStacks) {
                if (good.genericItem.fits(stack)) {
                    need-=stack.getAmount();
                    if(need <=0) break;
                }
            }

            if (need > 0){
                demand.map.put(good, need);
            }
        }
        if(!demand.map.isEmpty()) return Optional.of(demand);
        return Optional.empty();
    }


    public static ProductionEntry deserialize(ConfigurationSection section, String id){
        int minLevel = section.getInt("min-level", 1);
        int maxLevel = section.getInt("max-level", Integer.MAX_VALUE);

        List<Good> produce = getGoodsList(section.getConfigurationSection("produce"));
        System.out.println(produce);
        List<Good> consumption = getGoodsList(section.getConfigurationSection("consumption"));
        System.out.println(consumption);

        return new ProductionEntry(minLevel, maxLevel, produce, consumption, id);
    }

    private static List<Good> getGoodsList(ConfigurationSection goods){
        if(goods == null){
            System.out.println("No goods specified for ");
            return new ArrayList<>();
        }

        List<Good> goodList = new ArrayList<>();

        for(String key : goods.getKeys(false)){
            ConfigurationSection section = goods.getConfigurationSection(key);
            Optional<GenericItem> genericItem = GenericItem.fromString(section.getString("item"));
            if(genericItem.isEmpty()){
                System.out.println("Could not parse item: "+section.getString("item"));
                continue;
            }
            double durability = section.getDouble("durability", 0);

            int min =0, max=0;
            Object q = section.get("count", 1);
            if(q instanceof Integer i){
                min = i;
                max = i;
            } else if(q instanceof String s){
                min = Integer.parseInt(s.split("-")[0]);
                max = Integer.parseInt(s.split("-")[1]);
            }
            double chance = section.getDouble("chance", 1.0);

            if(genericItem == null){
                System.out.println("Could not parse material for production: ");
                continue;
            }
            Good good = new Good( genericItem.get(), min, max, chance, durability);
            goodList.add(good);
        }
        return goodList;
    }

}
