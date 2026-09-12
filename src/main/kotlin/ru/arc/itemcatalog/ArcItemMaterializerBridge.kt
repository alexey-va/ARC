package ru.arc.itemcatalog

import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcItemMaterializationReference
import ru.arc.paper.api.ArcItemMaterializationRequest
import ru.arc.paper.api.ArcItemMaterializer
import ru.arc.paper.api.ArcItemMaterializerCapabilitySnapshot

/** ServicesManager adapter for the ARC-owned reward catalogue snapshot. */
internal object ArcItemMaterializerBridge : ArcItemMaterializer {
    override fun capability(): ArcItemMaterializerCapabilitySnapshot =
        ItemsCatalogModule.itemMaterializerCapability()

    override fun prepare(request: ArcItemMaterializationRequest): ArcItemMaterializationReference? =
        ItemsCatalogModule.prepareItemMaterialization(request)

    override fun materialize(reference: ArcItemMaterializationReference): List<ItemStack>? =
        ItemsCatalogModule.materializeItem(reference)
}
