package arc.arc.hooks.elitemobs.guis;

import arc.arc.configs.Config;
import arc.arc.hooks.elitemobs.EMHook;
import com.magmaguy.elitemobs.items.ItemTagger;
import com.magmaguy.elitemobs.items.ItemWorthCalculator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

@RequiredArgsConstructor
public class ShopHolder {

    final EMHook emHook;
    final Config config;

    private Map<UUID, Shop> items = new HashMap<>();


    public Shop getShop(Player player) {
        int gearSize = config.integer("shop.gear-size", 36);
        int trinketSize = config.integer("shop.trinket-size", 36);
        return items.computeIfAbsent(player.getUniqueId(), uuid ->
                new Shop(gearSize, trinketSize, emHook.tier(player), player, emHook));

    }

    public void deleteAll() {
        items.clear();
    }


    public static class Shop {
        EMHook emHook;
        Player player;
        long timestamp;
        List<ShopItem> gear = new ArrayList<>();
        List<ShopItem> trinkets = new ArrayList<>();

        public Shop(int gearSize, int trinketSize, int tier, Player player, EMHook emHook) {
            this.emHook = emHook;
            this.player = player;
            generateGear(gearSize, tier, player);
            generateTrinkets(trinketSize, tier, player);
            timestamp = System.currentTimeMillis();
        }

        public void generateGear(int size, int tier, Player player) {
            gear.clear();

            Random random = new Random();

            for (int i = 0; i < size; i++) {
                double gauss = random.nextGaussian(tier*0.8, tier*0.15);
                int rTier = Math.max(1, Math.min(tier+3, (int) Math.round(gauss)));
                ItemStack stack = emHook.generateDrop(rTier, player, false);
                double price = ItemTagger.getItemValue(stack);
                if (price <= 0) price = ItemWorthCalculator.determineItemWorth(stack, player);
                ShopItem item = new ShopItem(stack, price);
                gear.add(item);
            }
            gear.sort(Comparator.comparingInt(o -> o.stack.getType().ordinal()));
        }

        public void generateTrinkets(int size, int tier, Player player) {
            trinkets.clear();

            Random random = new Random();

            for (int i = 0; i < size; i++) {
                double gauss = random.nextGaussian(tier*0.8, tier*0.15);
                int rTier = Math.max(1, Math.min(tier+3, (int) Math.round(gauss)));
                ItemStack stack = emHook.generateDrop(rTier, player, true);
                double price = ItemTagger.getItemValue(stack);
                if (price <= 0) price = ItemWorthCalculator.determineItemWorth(stack, player);
                ShopItem item = new ShopItem(stack, price);
                trinkets.add(item);
            }
            trinkets.sort(Comparator.comparingInt(o -> o.stack.getType().ordinal()));
        }

        public long timestamp() {
            return timestamp;
        }
    }

    @AllArgsConstructor
    @Getter
    public static class ShopItem {
        ItemStack stack;
        double price;
    }

}
