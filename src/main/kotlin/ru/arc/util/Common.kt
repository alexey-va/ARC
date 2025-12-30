package ru.arc.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.inventory.ItemStack
import ru.arc.network.adapters.PolymorphismAdapter
import ru.arc.network.repos.ItemList
import ru.arc.network.repos.ItemListSerializer
import ru.arc.network.repos.ItemStackSerializer
import ru.arc.xserver.XAction

object Common {
    @JvmField
    val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(XAction::class.java, PolymorphismAdapter<XAction>())
        .registerTypeAdapter(ItemStack::class.java, ItemStackSerializer())
        .registerTypeAdapter(ItemList::class.java, ItemListSerializer())
        .create()

    @JvmField
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting()
        .registerTypeAdapter(ItemStack::class.java, ItemStackSerializer())
        .registerTypeAdapter(ItemList::class.java, ItemListSerializer())
        .create()
}

