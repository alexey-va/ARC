package ru.arc.hooks.economyshop

import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Locale

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

    /**
     * Returns currently accessible, native IA furniture offers for the small
     * dialog catalogue. Implementations must resolve prices from the live shop
     * objects; an empty default keeps optional integrations source-compatible.
     */
    fun furnitureOffers(player: Player, amount: Int = 1): List<FurnitureShopOffer> = emptyList()

    /** Re-reads one live offer before a dialog confirmation is committed. */
    fun furnitureOffer(player: Player, itemPath: String, amount: Int = 1): FurnitureShopOffer? = null

    /**
     * Returns the cheapest currently accessible Vault offer that gives exactly
     * [amount] plain vanilla [material] items. Command products, custom item
     * variants and composite prices are deliberately excluded.
     */
    fun quotePlainMaterial(
        player: Player,
        material: Material,
        amount: Int,
    ): ShopMaterialQuote? = null

    /** Current balance in the same Vault currency used by [quotePlainMaterial]. */
    fun vaultBalance(player: Player): Double? = null

    /** Formats an aggregate Vault amount with the active shop provider. */
    fun formatVaultPrice(amount: Double): String? = null
}

internal data class ShopMaterialQuote(
    val material: Material,
    val itemPath: String,
    val amount: Int,
    val totalPrice: Double,
    val formattedPrice: String,
) {
    init {
        require(material.isItem && !material.isAir) { "Shop material quote requires an item material" }
        require(itemPath.length in 1..512) { "Shop material quote path is outside its size bound" }
        require(amount > 0) { "Shop material quote amount must be positive" }
        require(totalPrice.isFinite() && totalPrice in 0.000_001..1_000_000_000_000_000.0) {
            "Shop material quote price is outside its safety bound"
        }
        require(formattedPrice.length in 1..256) { "Shop material quote formatted price is outside its size bound" }
    }
}

internal data class ShopMaterialOffer(
    val itemPath: String,
    val totalPrice: Double,
)

internal data class FurnitureShopOffer(
    val itemPath: String,
    val furnitureId: String,
    val category: String,
    val displayName: String,
    val amount: Int,
    val totalPrice: Double,
    val formattedPrice: String,
) {
    init {
        require(itemPath.length in 1..512) { "Furniture shop offer path is outside its size bound" }
        require(furnitureId.length in 1..256) { "Furniture shop offer id is outside its size bound" }
        require(category.length in 1..128) { "Furniture shop offer category is outside its size bound" }
        require(displayName.length in 1..256) { "Furniture shop offer display name is outside its size bound" }
        require(amount > 0) { "Furniture shop offer amount must be positive" }
        require(totalPrice.isFinite() && totalPrice > 0.0) { "Furniture shop offer price must be finite and positive" }
        require(formattedPrice.length in 1..256) { "Furniture shop offer formatted price is outside its size bound" }
    }
}

/** Deterministic pure selector shared by the live adapter and unit tests. */
internal object ShopMaterialOfferSelector {
    fun cheapest(offers: Iterable<ShopMaterialOffer>): ShopMaterialOffer? =
        offers
            .filter { it.itemPath.isNotBlank() && it.totalPrice.isFinite() && it.totalPrice > 0.0 }
            .minWithOrNull(
                compareBy<ShopMaterialOffer> { it.totalPrice }
                    .thenBy { it.itemPath.lowercase(Locale.ROOT) }
                    .thenBy { it.itemPath },
            )
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
