package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.economyshop.ShopMaterialQuote
import ru.arc.hooks.economyshop.ShopPurchaseOutcome
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BuilderShopEstimateTest : FunSpec({
    val planId = UUID.fromString("77777777-7777-7777-7777-777777777777")

    test("auto-buy is limited to material-backed construction plans") {
        BuilderShopEstimateRules.supportsAutoBuy(BuilderPlanKind.FILL) shouldBe true
        BuilderShopEstimateRules.supportsAutoBuy(BuilderPlanKind.PASTE) shouldBe true
        BuilderShopEstimateRules.supportsAutoBuy(BuilderPlanKind.CROWN) shouldBe true
        BuilderShopEstimateRules.supportsAutoBuy(BuilderPlanKind.DECONSTRUCT) shouldBe false
        BuilderShopEstimateRules.supportsAutoBuy(BuilderPlanKind.UNDO) shouldBe false
    }

    test("same request accepts any refreshed admin-shop total") {
        MockBukkitTestRuntime.open().use {
            val preview = estimate(planId, line(Material.STONE, 64, 320.0), line(Material.OAK_PLANKS, 16, 80.0))
            val lower = estimate(planId, line(Material.STONE, 64, 300.0), line(Material.OAK_PLANKS, 16, 80.0))
            val higher = estimate(planId, line(Material.STONE, 64, 500.0), line(Material.OAK_PLANKS, 16, 120.0))

            BuilderShopEstimateRules.compareMissing(preview, lower) shouldBe
                BuilderShopEstimateComparison.ACCEPT
            BuilderShopEstimateRules.compareMissing(preview, higher) shouldBe
                BuilderShopEstimateComparison.ACCEPT
        }
    }

    test("changed deficit and unavailable product require a new confirmation") {
        MockBukkitTestRuntime.open().use {
            val preview = estimate(planId, line(Material.STONE, 64, 320.0))
            val changed = estimate(planId, line(Material.STONE, 63, 315.0))
            val unavailable = estimate(planId, BuilderShopEstimateLine(Material.STONE, 64, null))

            BuilderShopEstimateRules.compareMissing(preview, changed) shouldBe
                BuilderShopEstimateComparison.REQUEST_CHANGED
            BuilderShopEstimateRules.compareMissing(preview, unavailable) shouldBe
                BuilderShopEstimateComparison.REQUEST_CHANGED
            BuilderShopEstimateRules.compareMissing(
                preview,
                estimate(UUID.fromString("88888888-8888-8888-8888-888888888888"), line(Material.STONE, 64, 320.0)),
            ) shouldBe BuilderShopEstimateComparison.REQUEST_CHANGED
        }
    }

    test("procurement buys every exact request and reports the actual formatted prices") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("QuoteBuyer")
            val service = FakeShopPurchaseService()
            val costs = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 4), ItemStack(Material.OAK_PLANKS, 2)))
            val requests = costs.map { cost ->
                val material = checkNotNull(BuilderInventory.plainMaterial(cost))
                val quote = quote(material, cost.amount, cost.amount * 2.0)
                service.quotes[material] = quote
                BuilderShopProcurementRequest(cost, quote)
            }

            BuilderShopProcurementExecutor(service).execute(player, requests) shouldBe
                BuilderShopProcurementResult.Success(6, listOf("8.0 coins", "4.0 coins"))
            BuilderInventory.missingCosts(player, costs) shouldBe emptyList()
        }
    }

    test("a later shop failure keeps earlier purchases and never pretends the sequence was atomic") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("PartialBuyer")
            val service = FakeShopPurchaseService(failMaterial = Material.OAK_PLANKS)
            val costs = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 4), ItemStack(Material.OAK_PLANKS, 2)))
            val requests = costs.map { cost ->
                val material = checkNotNull(BuilderInventory.plainMaterial(cost))
                val quote = quote(material, cost.amount, cost.amount * 2.0)
                service.quotes[material] = quote
                BuilderShopProcurementRequest(cost, quote)
            }

            BuilderShopProcurementExecutor(service).execute(player, requests) shouldBe
                BuilderShopProcurementResult.Failed(4, Material.OAK_PLANKS, ShopPurchaseStatus.OUT_OF_STOCK)
            BuilderInventory.countExact(player, costs.first()) shouldBe 4
            BuilderInventory.countExact(player, costs.last()) shouldBe 0
        }
    }

    test("purchase uses the current admin-shop price without another confirmation") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("PriceGuard")
            val service = FakeShopPurchaseService()
            val cost = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 4))).single()
            val preview = quote(Material.STONE, 4, 8.0)
            service.quotes[Material.STONE] = quote(Material.STONE, 4, 8.02)

            BuilderShopProcurementExecutor(service).execute(
                player,
                listOf(BuilderShopProcurementRequest(cost, preview)),
            ) shouldBe BuilderShopProcurementResult.Success(4, listOf("8.02 coins"))
            BuilderInventory.countExact(player, cost) shouldBe 4
            service.purchaseCalls shouldBe 1
        }
    }

    test("a failed status with an inventory mutation is treated as ambiguous") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer("AmbiguousBuyer")
            val service = FakeShopPurchaseService(failMaterial = Material.STONE, deliverOnFailure = true)
            val cost = BuilderItemCodec.aggregate(listOf(ItemStack(Material.STONE, 4))).single()
            val expected = quote(Material.STONE, 4, 8.0)
            service.quotes[Material.STONE] = expected

            BuilderShopProcurementExecutor(service).execute(
                player,
                listOf(BuilderShopProcurementRequest(cost, expected)),
            ) shouldBe BuilderShopProcurementResult.Ambiguous(0, Material.STONE)
            BuilderInventory.countExact(player, cost) shouldBe 4
        }
    }
})

private fun estimate(planId: UUID, vararg missing: BuilderShopEstimateLine) =
    BuilderShopEstimate(planId, full = missing.toList(), missing = missing.toList())

private fun line(material: Material, amount: Int, price: Double): BuilderShopEstimateLine =
    BuilderShopEstimateLine(
        material,
        amount,
        quote(material, amount, price),
    )

private fun quote(material: Material, amount: Int, price: Double) =
    ShopMaterialQuote(material, "Blocks.${material.name.lowercase()}", amount, price, "$price coins")

private class FakeShopPurchaseService(
    private val failMaterial: Material? = null,
    private val deliverOnFailure: Boolean = false,
) : ShopPurchaseService {
    val quotes = mutableMapOf<Material, ShopMaterialQuote>()
    var purchaseCalls = 0

    override fun itemQueries(player: Player): List<String> = emptyList()

    override fun quotePlainMaterial(player: Player, material: Material, amount: Int): ShopMaterialQuote? =
        quotes[material]?.takeIf { it.amount == amount }

    override fun purchase(player: Player, itemPath: String, amount: Int): ShopPurchaseOutcome {
        purchaseCalls++
        val quote = quotes.values.single { it.itemPath == itemPath && it.amount == amount }
        if (quote.material == failMaterial) {
            if (deliverOnFailure) player.inventory.addItem(ItemStack(quote.material, amount))
            return ShopPurchaseOutcome(ShopPurchaseStatus.OUT_OF_STOCK, itemPath, amount)
        }
        player.inventory.addItem(ItemStack(quote.material, amount))
        return ShopPurchaseOutcome(
            ShopPurchaseStatus.SUCCESS,
            itemPath,
            amount,
            formattedPrice = "${quote.totalPrice} coins",
        )
    }

    override fun vaultBalance(player: Player): Double = 1_000_000.0

    override fun formatVaultPrice(amount: Double): String = "$amount coins"
}
