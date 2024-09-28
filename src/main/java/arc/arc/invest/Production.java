package arc.arc.invest;

import arc.arc.invest.goods.ProductionEntry;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.*;

@RequiredArgsConstructor
public class Production {

    final List<ProductionEntry> productionEntries;

    public List<ItemStack> produce(Set<String> productionIds, int level) {
        List<ItemStack> stacks = new ArrayList<>();
        for(ProductionEntry entry : productionEntries){
            if(!productionIds.contains(entry.getName())) continue;
            stacks.addAll(entry.production(level));
        }
        return stacks;
    }

    public Map<String, List<ItemStack>> reqs(int level){
        Map<String, List<ItemStack>> reqs = new HashMap<>();
        for(var entry : productionEntries){
            reqs.put(entry.getName(), entry.reqs(level));
        }
        return reqs;
    }

    public static Production deserialize(ConfigurationSection section) {
        if(section == null) return null;
        List<ProductionEntry> productionEntryList = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ProductionEntry en = ProductionEntry.deserialize(section.getConfigurationSection(key), key);
            productionEntryList.add(en);
        }
        return new Production(productionEntryList);
    }

}
