package arc.arc.util;

import arc.arc.network.adapters.PolymorphismAdapter;
import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.ItemListSerializer;
import arc.arc.network.repos.ItemStackSerializer;
import arc.arc.xserver.XAction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.inventory.ItemStack;

public class Common {

    public static Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(XAction.class, new PolymorphismAdapter<>())
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();
    public static Gson prettyGson = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();

}
