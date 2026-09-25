package ru.arc.hooks.economyshop

import java.util.Locale

/**
 * Immutable, exact-ID index of native EconomyShopGUI furniture items.
 *
 * Duplicate offers remain visible to the caller so it can fail closed instead
 * of choosing an arbitrary SKU. Incomplete snapshots expose no entries.
 */
internal class FurnitureShopItemIndex<T> private constructor(
    private val entriesByFurnitureId: Map<String, List<T>>,
    val complete: Boolean,
) {
    fun entries(furnitureId: String): List<T> =
        normalize(furnitureId)?.let(entriesByFurnitureId::get).orEmpty()

    fun contains(furnitureId: String): Boolean = entries(furnitureId).isNotEmpty()

    companion object {
        fun <T> from(entries: Sequence<Pair<String, T>>, maxEntries: Int): FurnitureShopItemIndex<T> {
            require(maxEntries > 0) { "Furniture shop index bound must be positive" }
            val indexed = linkedMapOf<String, MutableList<T>>()
            var count = 0
            for ((rawId, item) in entries) {
                if (++count > maxEntries) return FurnitureShopItemIndex(emptyMap(), complete = false)
                val id = normalize(rawId) ?: continue
                indexed.getOrPut(id) { mutableListOf() }.add(item)
            }
            return FurnitureShopItemIndex(indexed.mapValues { (_, items) -> items.toList() }, complete = true)
        }

        fun <T> empty(complete: Boolean = true): FurnitureShopItemIndex<T> =
            FurnitureShopItemIndex(emptyMap(), complete)

        internal fun normalize(id: String?): String? =
            id?.trim()?.takeIf { it.length in 3..256 && it.count { char -> char == ':' } == 1 }
                ?.lowercase(Locale.ROOT)
    }
}
