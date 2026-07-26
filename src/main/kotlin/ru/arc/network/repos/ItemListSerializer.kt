package ru.arc.network.repos

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.bukkit.inventory.ItemStack
import java.util.Base64

class ItemListSerializer : TypeAdapter<ItemList>() {

    override fun write(out: JsonWriter, value: ItemList?) {
        out.beginArray()
        if (value == null || value.isEmpty()) {
            out.endArray()
            return
        }
        for (stack in value) {
            if (stack == null) out.nullValue()
            else out.value(Base64.getEncoder().encodeToString(stack.serializeAsBytes()))
        }
        out.endArray()
    }

    override fun read(reader: JsonReader): ItemList {
        val itemList = ItemList()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                itemList.add(null)
            } else {
                itemList.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(reader.nextString())))
            }
        }
        reader.endArray()
        return itemList
    }
}
