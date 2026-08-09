package ru.arc.hooks.economyshop

import me.gypopo.economyshopgui.api.EconomyShopGUIHook
import me.gypopo.economyshopgui.objects.ShopItem
import me.gypopo.economyshopgui.util.EcoType
import me.gypopo.economyshopgui.util.Transaction
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** EconomyShopGUI Premium 6.3.0 implementation, loaded only while that plugin is present. */
internal class EconomyShopGuiPurchaseService(
    private val translateItem: (ItemStack?) -> String,
) : ShopPurchaseService {
    override fun itemQueries(player: Player): List<String> =
        allItems()
            .asSequence()
            .filter { !it.hasItemError() && !it.isHidden && !it.isDisplayItem && it.isBuyAble }
            .filter { EconomyShopGUIHook.hasPermissions(it, player) }
            .map(::descriptor)
            .toList()
            .let(ShopItemQueryIndex::preferredQueries)

    override fun purchase(
        player: Player,
        itemPath: String,
        amount: Int,
    ): ShopPurchaseOutcome {
        val item = resolveItem(itemPath)
            ?: return ShopPurchaseOutcome(ShopPurchaseStatus.ITEM_NOT_FOUND, itemPath, amount)
        val canonicalPath = item.itemPath
        val itemName = translatedName(item)

        if (item.isHidden || item.isDisplayItem || !item.isBuyAble) {
            return ShopPurchaseOutcome(
                ShopPurchaseStatus.NOT_BUYABLE,
                canonicalPath,
                amount,
                itemName = itemName,
            )
        }

        // check permissions, check requirements, disallow partial quantity, suppress native
        // chat output (ARC maps every result to one consistent command response).
        val result = EconomyShopGUIHook.purchaseItem(player, item, amount, true, true, false, false)
        val fulfilledAmount = result.amount.takeIf { it > 0 } ?: amount
        return ShopPurchaseOutcome(
            status = result.result.toPurchaseStatus(),
            itemPath = canonicalPath,
            amount = fulfilledAmount,
            formattedPrice = formatPrices(result.prices),
            itemName = itemName,
        )
    }

    private fun translatedName(item: ShopItem): String? =
        runCatching { translateItem(item.shopItem) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun resolveItem(itemPath: String): ShopItem? {
        EconomyShopGUIHook.getShopItem(itemPath)?.let { return it }
        val items = allItems()
        val canonicalPath = ShopItemQueryIndex.resolve(itemPath, items.map(::descriptor)) ?: return null
        return items.firstOrNull { it.itemPath.equals(canonicalPath, ignoreCase = true) }
    }

    private fun allItems(): List<ShopItem> =
        EconomyShopGUIHook.getSections().values.flatMap { it.shopItems }

    private fun descriptor(item: ShopItem): ShopItemDescriptor =
        ShopItemDescriptor(
            canonicalPath = item.itemPath,
            section = item.section(),
            relativeLocation = item.itemLoc(),
            material = runCatching { item.shopItem.type.name }.getOrNull(),
        )

    private fun formatPrices(prices: Map<EcoType, Double>?): String? {
        if (prices.isNullOrEmpty()) return null
        return prices.entries
            .sortedBy { it.key.toString() }
            .joinToString(" + ") { (type, price) ->
                EconomyShopGUIHook.getEcon(type)?.formatPrice(price) ?: price.toString()
            }
            .takeIf(String::isNotBlank)
    }

    private fun Transaction.Result.toPurchaseStatus(): ShopPurchaseStatus =
        when (this) {
            Transaction.Result.SUCCESS,
            Transaction.Result.SUCCESS_COMMANDS_EXECUTED,
            -> ShopPurchaseStatus.SUCCESS

            Transaction.Result.ITEM_ERROR -> ShopPurchaseStatus.ITEM_ERROR
            Transaction.Result.DISPLAY_ITEM,
            Transaction.Result.NEGATIVE_ITEM_PRICE,
            Transaction.Result.NO_ITEMS_FOUND,
            -> ShopPurchaseStatus.NOT_BUYABLE

            Transaction.Result.NO_PERMISSIONS -> ShopPurchaseStatus.NO_PERMISSIONS
            Transaction.Result.REQUIREMENTS_FAILED -> ShopPurchaseStatus.REQUIREMENTS_FAILED
            Transaction.Result.INSUFFICIENT_FUNDS -> ShopPurchaseStatus.INSUFFICIENT_FUNDS
            Transaction.Result.NO_INVENTORY_SPACE -> ShopPurchaseStatus.NO_INVENTORY_SPACE
            Transaction.Result.TRANSACTION_CANCELLED -> ShopPurchaseStatus.TRANSACTION_CANCELLED
            Transaction.Result.NOT_ENOUGH_ITEMS -> ShopPurchaseStatus.BELOW_MINIMUM
            Transaction.Result.TO_MANY_ITEMS -> ShopPurchaseStatus.ABOVE_MAXIMUM
            Transaction.Result.NO_ITEM_STOCK_LEFT -> ShopPurchaseStatus.OUT_OF_STOCK
            Transaction.Result.REACHED_SELL_LIMIT,
            Transaction.Result.CANT_STORE_PAYMENT,
            -> ShopPurchaseStatus.FAILED
        }
}

internal data class ShopItemDescriptor(
    val canonicalPath: String,
    val section: String,
    val relativeLocation: String,
    val material: String?,
)

/** Selects concise unambiguous command tokens while retaining canonical-path lookup. */
internal object ShopItemQueryIndex {
    fun preferredQueries(items: List<ShopItemDescriptor>): List<String> =
        items
            .groupBy { it.section.lowercase() }
            .values
            .flatMap { sectionItems ->
                sectionItems.map { item ->
                    val shortLocation = item.relativeLocation.substringAfterLast('.')
                    val selector =
                        listOfNotNull(item.material, shortLocation, item.relativeLocation)
                            .distinctBy { it.lowercase() }
                            .firstOrNull { candidate ->
                                sectionItems.count { matchesSelector(it, candidate) } == 1
                            } ?: item.relativeLocation
                    "${item.section}.$selector"
                }
            }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun resolve(query: String, items: List<ShopItemDescriptor>): String? {
        items.singleOrNull { it.canonicalPath.equals(query, ignoreCase = true) }?.let { return it.canonicalPath }

        val separator = query.indexOf('.')
        if (separator <= 0 || separator == query.lastIndex) return null
        val section = query.substring(0, separator)
        val selector = query.substring(separator + 1)
        val matches =
            items.filter { item ->
                item.section.equals(section, ignoreCase = true) &&
                    matchesSelector(item, selector)
            }
        return matches.singleOrNull()?.canonicalPath
    }

    private fun matchesSelector(item: ShopItemDescriptor, selector: String): Boolean =
        item.relativeLocation.equals(selector, ignoreCase = true) ||
            item.relativeLocation.substringAfterLast('.').equals(selector, ignoreCase = true) ||
            item.material?.equals(selector, ignoreCase = true) == true
}
