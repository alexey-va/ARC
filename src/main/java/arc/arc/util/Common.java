package arc.arc.util;

import arc.arc.network.adapters.PolymorphismAdapter;
import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.ItemListSerializer;
import arc.arc.network.repos.ItemStackSerializer;
import arc.arc.xserver.XAction;
import arc.arc.xserver.announcements.ArcCondition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.inventory.ItemStack;

public class Common {

    public static Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(ArcCondition.class, new PolymorphismAdapter<>())
            .registerTypeHierarchyAdapter(XAction.class, new PolymorphismAdapter<>())
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();
    public static Gson prettyGson = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(ArcCondition.class, new PolymorphismAdapter<ArcCondition>())
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(ItemList.class, new ItemListSerializer())
            .create();

}
