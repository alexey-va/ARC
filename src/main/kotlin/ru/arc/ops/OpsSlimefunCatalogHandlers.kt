package ru.arc.ops

import com.google.gson.GsonBuilder
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

private val slimefunCatalogGson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

internal fun slimefunCatalogJson(data: Map<String, Any?>): String =
    slimefunCatalogGson.toJson(linkedMapOf("ok" to true) + data)

internal data class SlimefunCatalogStack(
    val sfId: String?,
    val material: String,
    val amount: Int,
) {
    fun asMap(): Map<String, Any?> =
        linkedMapOf(
            "sfId" to sfId,
            "material" to material,
            "amount" to amount,
        )
}

internal data class SlimefunCatalogEntry(
    val id: String,
    val addon: String,
    val plugin: String,
    val enabled: Boolean,
    val material: String,
    val recipeType: String?,
    val inputs: List<SlimefunCatalogStack>,
    val output: SlimefunCatalogStack?,
    val displayRecipes: List<SlimefunCatalogStack>?,
) {
    fun asMap(): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "addon" to addon,
            "plugin" to plugin,
            "enabled" to enabled,
            "material" to material,
            "recipeType" to recipeType,
            "inputs" to inputs.map(SlimefunCatalogStack::asMap),
            "output" to output?.asMap(),
            "displayRecipes" to displayRecipes?.map(SlimefunCatalogStack::asMap),
        )
}

internal fun slimefunCatalogPage(
    entries: List<SlimefunCatalogEntry>,
    offset: Int,
    limit: Int,
): Map<String, Any?> {
    require(offset >= 0) { "offset must be non-negative" }
    require(limit in 1..100) { "limit must be 1..100" }

    val ordered = entries.sortedBy(SlimefunCatalogEntry::id)
    val pageItems = ordered.drop(offset).take(limit)
    return slimefunCatalogResponse(pageItems, ordered.size, offset, limit)
}

private fun slimefunCatalogResponse(
    entries: List<SlimefunCatalogEntry>,
    total: Int,
    offset: Int,
    limit: Int,
): Map<String, Any?> {
    require(offset >= 0) { "offset must be non-negative" }
    require(limit in 1..100) { "limit must be 1..100" }
    require(total >= entries.size) { "total must include the returned items" }
    require(entries.size <= limit) { "returned items exceed limit" }

    return linkedMapOf(
        "source" to "slimefun4-registry",
        "recipeCoverage" to
            linkedMapOf(
                "staticItemRecipes" to true,
                "displayRecipes" to "RecipeDisplayItem",
                "dynamicMachineRecipes" to "not-enumerated",
            ),
        "total" to total,
        "offset" to offset,
        "limit" to limit,
        "items" to entries.map(SlimefunCatalogEntry::asMap),
    )
}

object OpsSlimefunCatalogHandlers {
    fun list(
        offset: Int,
        limit: Int,
    ): Map<String, Any?> {
        require(offset >= 0) { "offset must be non-negative" }
        require(limit in 1..100) { "limit must be 1..100" }

        return OpsBukkitSync.call {
            if (!Bukkit.getPluginManager().isPluginEnabled("Slimefun")) {
                throw IllegalStateException("Slimefun is not enabled")
            }

            val registeredItems = Slimefun.getRegistry().allSlimefunItems
            val orderedItems = registeredItems.sortedBy { it.id }
            val pageEntries = orderedItems.drop(offset).take(limit).map(::toEntry)
            slimefunCatalogResponse(pageEntries, registeredItems.size, offset, limit)
        }
    }

    private fun toEntry(item: SlimefunItem): SlimefunCatalogEntry {
        val displayRecipes =
            (item as? RecipeDisplayItem)
                ?.displayRecipes
                ?.mapNotNull(::toStack)

        return SlimefunCatalogEntry(
            id = item.id,
            addon = item.addon.name,
            plugin = item.addon.javaPlugin.name,
            enabled = !item.isDisabled,
            material = item.item.type.name,
            recipeType = item.recipeType.key.toString(),
            inputs = item.recipe.orEmpty().mapNotNull(::toStack),
            output = toStack(item.recipeOutput),
            displayRecipes = displayRecipes,
        )
    }

    private fun toStack(stack: ItemStack?): SlimefunCatalogStack? {
        if (stack == null || stack.type.isAir) return null
        return SlimefunCatalogStack(
            sfId = SlimefunItem.getByItem(stack)?.id,
            material = stack.type.name,
            amount = stack.amount,
        )
    }
}
