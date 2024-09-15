package arc.arc.network.repos;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.IOException;

public class ItemListSerializer extends TypeAdapter<ItemList> {
    @Override
    public void write(JsonWriter out, ItemList value) throws IOException {
        out.beginArray();
        if (value == null || value.isEmpty()) {
            out.endArray();
            return;
        }
        for (ItemStack stack : value) {
            if (stack == null) out.nullValue();
            else {
                String serialized = new String(Base64Coder.encode(stack.serializeAsBytes()));
                out.value(serialized);
            }
        }
        out.endArray();
    }

    @Override
    public ItemList read(JsonReader in) throws IOException {
        ItemList itemList = new ItemList();
        in.beginArray();
        while (in.hasNext()) {
            in.peek();
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                itemList.add(null);
                continue;
            }
            String serialized = in.nextString();
            itemList.add(ItemStack.deserializeBytes(Base64Coder.decode(serialized)));
        }
        in.endArray();
        return itemList;
    }
}
