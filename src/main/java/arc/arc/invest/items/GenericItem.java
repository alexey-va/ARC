package arc.arc.invest.items;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public abstract class GenericItem {

    public abstract ItemStack stack();

    public abstract boolean fits(ItemStack stack);

    public boolean isTool(){
        return false;
    }

    public static Optional<GenericItem> fromString(String s){
        NamedItem namedItem = NamedItem.find(s);
        System.out.println("Named item from "+s+" = "+namedItem);
        if(namedItem != null) return Optional.of(namedItem);
        SimpleItem simpleItem = SimpleItem.fromMaterial(s);
        if(simpleItem == null) return Optional.empty();

        return Optional.of(simpleItem);
    }

}
