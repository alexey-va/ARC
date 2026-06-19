package ru.arc.network.repos

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.bukkit.inventory.ItemStack
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder

class ItemStackSerializer : TypeAdapter<ItemStack>() {

    override fun write(out: JsonWriter, value: ItemStack) {
        out.value(String(Base64Coder.encode(value.serializeAsBytes())))
    }

    override fun read(reader: JsonReader): ItemStack =
        ItemStack.deserializeBytes(Base64Coder.decode(reader.nextString()))
}
