package ru.arc.network.repos;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.IOException;

public class ItemStackSerializer extends TypeAdapter<ItemStack> {

    @Override
    public void write(JsonWriter out, ItemStack value) throws IOException {
        //System.out.println("Serializing itemstack + "+value);
        String serialized = new String(Base64Coder.encode(value.serializeAsBytes()));
        out.value(serialized);
    }

    @Override
    public ItemStack read(JsonReader in) throws IOException {
        String serialized = in.nextString();
        return ItemStack.deserializeBytes(Base64Coder.decode(serialized));
    }
}
