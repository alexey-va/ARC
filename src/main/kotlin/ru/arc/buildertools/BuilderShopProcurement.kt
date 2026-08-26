package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.hooks.economyshop.ShopMaterialQuote
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus

internal data class BuilderShopProcurementRequest(
    val cost: BuilderItemAmount,
    val expected: ShopMaterialQuote,
) {
    init {
        require(cost.amount == expected.amount) { "Builder procurement request quantity mismatch" }
        require(BuilderInventory.plainMaterial(cost) == expected.material) {
            "Builder procurement request must use the exact plain quoted material"
        }
    }
}

internal sealed interface BuilderShopProcurementResult {
    val purchasedItems: Int
    val material: Material?

    data class Success(
        override val purchasedItems: Int,
        val formattedPrices: List<String>,
    ) : BuilderShopProcurementResult {
        override val material: Material? = null
    }

    data class EstimateChanged(
        override val purchasedItems: Int,
        override val material: Material,
    ) : BuilderShopProcurementResult

    data class Failed(
        override val purchasedItems: Int,
        override val material: Material,
        val status: ShopPurchaseStatus,
    ) : BuilderShopProcurementResult

    data class Ambiguous(
        override val purchasedItems: Int,
        override val material: Material,
        val failure: Throwable? = null,
    ) : BuilderShopProcurementResult
}

/**
 * Executes deterministic one-product-at-a-time shop purchases. EconomyShopGUI
 * owns each atomic purchase; this executor never attempts a synthetic refund
 * when a later product fails, because completed products are already ordinary
 * player-owned inventory and construction has not started yet.
 */
internal class BuilderShopProcurementExecutor(
    private val service: ShopPurchaseService,
    private val priceIncreaseTolerance: Double,
) {
    init {
        require(priceIncreaseTolerance.isFinite() && priceIncreaseTolerance >= 0.0) {
            "Builder procurement price tolerance is invalid"
        }
    }

    fun execute(
        player: Player,
        requests: List<BuilderShopProcurementRequest>,
    ): BuilderShopProcurementResult {
        var purchasedItems = 0
        val formattedPrices = mutableListOf<String>()
        for (request in requests) {
            val expected = request.expected
            val live = runCatching { service.quotePlainMaterial(player, expected.material, expected.amount) }.getOrNull()
            if (
                live == null || live.material != expected.material || live.amount != expected.amount ||
                live.totalPrice > expected.totalPrice + priceIncreaseTolerance
            ) {
                return BuilderShopProcurementResult.EstimateChanged(purchasedItems, expected.material)
            }
            val before = BuilderInventory.countExact(player, request.cost)
            val outcome = try {
                service.purchase(player, live.itemPath, live.amount)
            } catch (failure: Throwable) {
                return BuilderShopProcurementResult.Ambiguous(purchasedItems, expected.material, failure)
            }
            val delivered = BuilderInventory.countExact(player, request.cost) - before
            if (outcome.status != ShopPurchaseStatus.SUCCESS) {
                if (delivered != 0) {
                    return BuilderShopProcurementResult.Ambiguous(purchasedItems, expected.material)
                }
                return BuilderShopProcurementResult.Failed(purchasedItems, expected.material, outcome.status)
            }
            if (delivered != live.amount) {
                return BuilderShopProcurementResult.Ambiguous(purchasedItems, expected.material)
            }
            purchasedItems += live.amount
            formattedPrices += outcome.formattedPrice.orEmpty().ifBlank { live.formattedPrice }
        }
        return BuilderShopProcurementResult.Success(purchasedItems, formattedPrices)
    }
}
