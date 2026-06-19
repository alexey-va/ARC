package ru.arc.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import org.bukkit.inventory.ItemStack
import ru.arc.common.locationpools.LocationPool
import ru.arc.network.adapters.PolymorphismAdapter
import ru.arc.network.repos.ItemList
import ru.arc.network.repos.ItemListSerializer
import ru.arc.network.repos.ItemStackSerializer
import ru.arc.xserver.XAction

object Common {
    // Ensures LocationPool is created via its constructor so _locations is always initialized.
    // Without this Gson uses sun.misc.Unsafe, bypasses the constructor and _locations stays null.
    private val locationPoolCreator = InstanceCreator<LocationPool> { LocationPool("") }

    @JvmField
    val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(XAction::class.java, PolymorphismAdapter<XAction>())
        .registerTypeHierarchyAdapter(ItemStack::class.java, ItemStackSerializer())
        .registerTypeHierarchyAdapter(ItemList::class.java, ItemListSerializer())
        .registerTypeAdapter(LocationPool::class.java, locationPoolCreator)
        .create()

    @JvmField
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting()
        .registerTypeHierarchyAdapter(XAction::class.java, PolymorphismAdapter<XAction>())
        .registerTypeHierarchyAdapter(ItemStack::class.java, ItemStackSerializer())
        .registerTypeHierarchyAdapter(ItemList::class.java, ItemListSerializer())
        .registerTypeAdapter(LocationPool::class.java, locationPoolCreator)
        .create()
}

