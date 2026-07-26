package ru.arc.network.repos

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.bukkit.inventory.ItemStack
import java.util.Base64

class ItemStackSerializer : TypeAdapter<ItemStack>() {

    override fun write(out: JsonWriter, value: ItemStack) {
        out.value(Base64.getEncoder().encodeToString(value.serializeAsBytes()))
    }

    override fun read(reader: JsonReader): ItemStack =
        ItemStack.deserializeBytes(Base64.getDecoder().decode(reader.nextString()))
}
