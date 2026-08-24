package ru.arc.itemcatalog

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.nio.file.Path

interface ItemsAdderCatalogGateway {
    val available: Boolean

    fun contentsRoot(): Path?

    fun registeredItemIds(): Set<String>

    fun itemStack(namespacedId: String): ItemStack?
}

class BukkitItemsAdderCatalogGateway(
    private val itemsAdder: Plugin,
) : ItemsAdderCatalogGateway {
    override val available: Boolean
        get() = itemsAdder.isEnabled

    override fun contentsRoot(): Path = itemsAdder.dataFolder.toPath().resolve("contents")

    override fun registeredItemIds(): Set<String> =
        if (available) CustomStack.getNamespacedIdsInRegistry().toSet() else emptySet()

    override fun itemStack(namespacedId: String): ItemStack? =
        if (available) CustomStack.getInstance(namespacedId)?.itemStack?.clone() else null
}
