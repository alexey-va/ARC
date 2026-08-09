package ru.arc.hooks.economyshop

import org.bukkit.entity.Player

/** ARC-owned boundary around the optional EconomyShopGUI Premium API. */
internal interface ShopPurchaseService {
    /** Accepted, human-friendly item queries visible to [player], used for completion. */
    fun itemQueries(player: Player): List<String>

    /** Attempts one atomic purchase of exactly [amount] items. */
    fun purchase(
        player: Player,
        itemPath: String,
        amount: Int,
    ): ShopPurchaseOutcome
}

internal data class ShopPurchaseOutcome(
    val status: ShopPurchaseStatus,
    val itemPath: String,
    val amount: Int,
    val formattedPrice: String? = null,
    val itemName: String? = null,
)

internal enum class ShopPurchaseStatus {
    SUCCESS,
    ITEM_NOT_FOUND,
    ITEM_ERROR,
    NOT_BUYABLE,
    NO_PERMISSIONS,
    REQUIREMENTS_FAILED,
    INSUFFICIENT_FUNDS,
    NO_INVENTORY_SPACE,
    TRANSACTION_CANCELLED,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM,
    OUT_OF_STOCK,
    FAILED,
}
