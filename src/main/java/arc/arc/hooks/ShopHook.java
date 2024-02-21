package arc.arc.hooks;

import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.api.events.PreTransactionEvent;
import me.gypopo.economyshopgui.objects.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Damageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShopHook implements Listener {

    @EventHandler
    public void preTransaction(PreTransactionEvent event){
        event.getItems().forEach(
                (i, q) -> {
                    System.out.println(i.getItemPath());
                    System.out.println(q);
                }
        );
        System.out.println(event.getTransactionType());
        System.out.println(event.getPrices());
    }


    public double cost(Material material){
        ShopItem shopItem = EconomyShopGUIHook.getShopItem(new ItemStack(material));
        //System.out.println("Shop item: "+shopItem+" "+material);
        if(shopItem != null && EconomyShopGUIHook.isSellAble(shopItem)){
            return EconomyShopGUIHook.getItemSellPrice(shopItem, new ItemStack(material));
        }


        Recipe recipe = findRecipe(material);
        if(recipe == null) return 0;
        var map = getCraftingMaterials(recipe);
        System.out.println("Ingredients of "+material+" "+map);
        double counter = 0;
        for(var entry : map.entrySet()){
            Double cost = cost(entry.getKey());
            System.out.println(entry+" "+cost);
            counter+=cost*entry.getValue();
        }
        return counter;
    }

    private static Recipe findRecipe(Material material) {
        for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(material))) {
            if (recipe.getResult().getType() == material) {
                return recipe;
            }
        }
        return null;
    }

    private static Map<Material, Double> getCraftingMaterials(Recipe recipe) {
        Map<Material, Double> craftingMaterials = new HashMap<>();

        if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shapedRecipe = (ShapedRecipe) recipe;
            Map<Character, ItemStack> ingredientMap = shapedRecipe.getIngredientMap();

            for (ItemStack ingredient : ingredientMap.values()) {
                if (ingredient != null && ingredient.getType() != Material.AIR) {
                    double amount = ingredient.getAmount();

                    // If the item is damaged, account for its durability
                    if (ingredient.getItemMeta() instanceof Damageable) {
                        amount *= (1.0 - ((Damageable) ingredient.getItemMeta()).getHealth() / ingredient.getType().getMaxDurability());
                    }

                    craftingMaterials.put(ingredient.getType(), craftingMaterials.getOrDefault(ingredient.getType(), 0.0) + amount);
                }
            }
        } else if(recipe instanceof ShapelessRecipe shapelessRecipe){
            List<ItemStack> ingredients = shapelessRecipe.getIngredientList();

            for (ItemStack ingredient : ingredients) {
                if (ingredient != null && ingredient.getType() != Material.AIR) {
                    double amount = ingredient.getAmount();

                    // If the item is damaged, account for its durability
                    if (ingredient.getItemMeta() instanceof Damageable) {
                        amount *= (1.0 - ((Damageable) ingredient.getItemMeta()).getHealth() / ingredient.getType().getMaxDurability());
                    }

                    craftingMaterials.put(ingredient.getType(), craftingMaterials.getOrDefault(ingredient.getType(), 0.0) + amount);
                }
            }
        }

        return craftingMaterials;
    }
}
