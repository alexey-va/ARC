package ru.arc.buildertools

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.warn
import java.util.Locale
import java.util.UUID

internal sealed interface BuilderShopConfirmation {
    data object Ready : BuilderShopConfirmation

    data class Rejected(
        val messagePath: String,
        val values: Map<String, Any?> = emptyMap(),
    ) : BuilderShopConfirmation
}

/** Owns volatile quotes, player-facing estimates, and pre-construction procurement. */
internal class BuilderShopCoordinator(
    private val config: BuilderToolsConfig,
    private val messages: LocalizedMiniMessage,
    private val serviceProvider: () -> ShopPurchaseService? = { HookRegistry.shopPurchaseService },
) : AutoCloseable {
    private val estimates = mutableMapOf<UUID, BuilderShopEstimate>()

    fun preview(player: Player, plan: BuilderPlan) {
        val estimate = createEstimate(player, plan)
        if (estimate == null) {
            estimates.remove(player.uniqueId)
            return
        }
        estimates[player.uniqueId] = estimate
        sendEstimate(player, estimate)
    }

    fun clear(playerId: UUID) {
        estimates.remove(playerId)
    }

    fun procure(player: Player, plan: BuilderPlan): BuilderShopConfirmation {
        if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind)) return rejected("errors.shop-not-supported")
        val missing = BuilderInventory.missingCosts(player, plan.costs)
        if (missing.isEmpty()) return BuilderShopConfirmation.Ready
        if (!config.shopEnabled) return rejected("errors.shop-unavailable")
        val service = serviceProvider() ?: return rejected("errors.shop-unavailable")
        val missingItems = missing.sumOf { it.amount.toLong() }
        if (missing.size > config.shopMaxQuotedMaterials || missingItems > config.shopMaxAutoBuyItems) {
            return rejected(
                "errors.shop-limit",
                "items" to missingItems,
                "price" to "—",
            )
        }

        val preview = estimates[player.uniqueId]
        val current = checkNotNull(createEstimate(player, plan, missing))
        if (current.missingUnavailable.isNotEmpty()) {
            estimates[player.uniqueId] = current
            current.missingUnavailable.distinctBy { it.material }.forEach { line ->
                send(player, "plan.market-unavailable", "material" to line.material.key.value())
            }
            return rejected("errors.shop-material-unavailable")
        }
        val comparison = if (preview == null) {
            BuilderShopEstimateComparison.REQUEST_CHANGED
        } else {
            BuilderShopEstimateRules.compareMissing(preview, current)
        }
        if (comparison != BuilderShopEstimateComparison.ACCEPT) {
            estimates[player.uniqueId] = current
            sendEstimate(player, current)
            return rejected("errors.shop-estimate-changed")
        }
        if (current.missingTotal > config.shopMaxAutoBuyPrice) {
            return rejected(
                "errors.shop-limit",
                "items" to missingItems,
                "price" to formatTotal(service, current.missingTotal, true),
            )
        }
        val balance = service.vaultBalance(player) ?: return rejected("errors.shop-unavailable")
        if (balance < current.missingTotal) {
            return rejected(
                "errors.shop-insufficient-funds",
                "price" to formatTotal(service, current.missingTotal, true),
                "balance" to formatTotal(service, balance, true),
            )
        }
        if (
            !BuilderInventory.canApplyAfterReceiving(
                player,
                missing,
                plan.costs,
                plan.rewards,
                plan.toolFingerprintBase64,
                plan.toolDamage,
            )
        ) {
            return rejected("errors.inventory")
        }

        val requests = missing.zip(current.missing).map { (cost, line) ->
            BuilderShopProcurementRequest(cost, checkNotNull(line.quote))
        }
        val procurement = BuilderShopProcurementExecutor(service).execute(player, requests)
        val purchasedItems: Int
        val purchasedPrices: List<String>
        when (procurement) {
            is BuilderShopProcurementResult.Success -> {
                purchasedItems = procurement.purchasedItems
                purchasedPrices = procurement.formattedPrices.map(::plainPrice)
            }
            is BuilderShopProcurementResult.Failed -> {
                refresh(player, plan)
                return partialFailure(
                    player,
                    procurement.material.key.value(),
                    procurement.purchasedItems,
                    procurement.status.name.lowercase(Locale.ROOT),
                )
            }
            is BuilderShopProcurementResult.Ambiguous -> {
                procurement.failure?.let { failure ->
                    warn(
                        "Builder-tools shop purchase outcome is ambiguous for {} {}: {}",
                        player.name,
                        procurement.material.key,
                        failure.message,
                    )
                }
                refresh(player, plan)
                sendPurchaseStopped(player, procurement.purchasedItems, "ambiguous outcome")
                return rejected("errors.shop-purchase-ambiguous", "material" to procurement.material.key.value())
            }
        }
        if (BuilderInventory.missingCosts(player, plan.costs).isNotEmpty()) {
            refresh(player, plan)
            sendPurchaseStopped(player, purchasedItems, "delivery mismatch")
            return rejected("errors.shop-purchase-ambiguous", "material" to "—")
        }
        send(
            player,
            "shop.purchased",
            "items" to purchasedItems,
            "price" to purchasedPrices.joinToString(" + ").ifBlank { formatTotal(service, current.missingTotal, true) },
        )
        return BuilderShopConfirmation.Ready
    }

    private fun createEstimate(
        player: Player,
        plan: BuilderPlan,
        missing: List<BuilderItemAmount> = BuilderInventory.missingCosts(player, plan.costs),
    ): BuilderShopEstimate? {
        if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind) || plan.costs.isEmpty()) return null
        val service = serviceProvider().takeIf { config.shopEnabled }
        fun lines(costs: List<BuilderItemAmount>): List<BuilderShopEstimateLine> = costs.mapIndexed { index, cost ->
            val prototype = BuilderItemCodec.decodePrototype(cost.itemBase64)
            val plainMaterial = BuilderInventory.plainMaterial(cost)
            val quote = if (
                index < config.shopMaxQuotedMaterials &&
                service != null &&
                plainMaterial != null &&
                cost.amount <= config.shopMaxAutoBuyItems
            ) {
                runCatching { service.quotePlainMaterial(player, plainMaterial, cost.amount) }.getOrNull()
            } else {
                null
            }
            BuilderShopEstimateLine(prototype.type, cost.amount, quote)
        }
        return BuilderShopEstimate(plan.id, lines(plan.costs), lines(missing))
    }

    private fun sendEstimate(player: Player, estimate: BuilderShopEstimate) {
        val service = serviceProvider().takeIf { config.shopEnabled }
        messages.renderLines(
            "plan.market",
            locale(player),
            mapOf(
                "full_price" to messages.literal(
                    formatTotal(
                        service,
                        estimate.fullTotal,
                        estimate.full.isNotEmpty() && estimate.fullUnavailable.isEmpty(),
                    ),
                ),
                "missing_price" to messages.literal(
                    formatTotal(service, estimate.missingTotal, estimate.missingUnavailable.isEmpty()),
                ),
            ),
        ).forEach(player::sendMessage)
        estimate.missing.take(MAX_DISPLAYED_MARKET_LINES).forEach { line ->
            send(player, "plan.market-item", "amount" to line.amount, "material" to line.material.key.value())
        }
        val unavailable = estimate.fullUnavailable.distinctBy { it.material }
        unavailable.take(MAX_DISPLAYED_MARKET_LINES).forEach { line ->
            send(player, "plan.market-unavailable", "material" to line.material.key.value())
        }
        val hiddenLines =
            (estimate.missing.size - MAX_DISPLAYED_MARKET_LINES).coerceAtLeast(0) +
                (unavailable.size - MAX_DISPLAYED_MARKET_LINES).coerceAtLeast(0)
        if (hiddenLines > 0) send(player, "plan.market-more", "count" to hiddenLines)
    }

    private fun partialFailure(
        player: Player,
        material: String,
        purchasedItems: Int,
        status: String,
    ): BuilderShopConfirmation.Rejected {
        sendPurchaseStopped(player, purchasedItems, status)
        return rejected("errors.shop-purchase-failed", "material" to material)
    }

    private fun sendPurchaseStopped(player: Player, purchasedItems: Int, status: String) {
        messages.renderLines(
            "shop.purchase-detail",
            locale(player),
            mapOf("status" to messages.literal(status), "purchased" to messages.literal(purchasedItems)),
        ).forEach(player::sendMessage)
        send(player, "shop.world-untouched")
    }

    private fun refresh(player: Player, plan: BuilderPlan) {
        createEstimate(player, plan)?.let { estimates[player.uniqueId] = it }
    }

    private fun formatTotal(service: ShopPurchaseService?, amount: Double, available: Boolean): String {
        if (!available) return "—"
        return service?.formatVaultPrice(amount)?.let(::plainPrice)
            ?: String.format(Locale.US, "%,.2f", amount)
    }

    private fun plainPrice(raw: String): String =
        PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(raw))
            .take(MAX_FORMATTED_PRICE_LENGTH)

    private fun send(player: Player, path: String, vararg values: Pair<String, Any?>) {
        player.sendMessage(
            messages.render(
                path,
                locale(player),
                values.associate { (key, value) -> key to messages.literal(value) },
            ),
        )
    }

    private fun rejected(path: String, vararg values: Pair<String, Any?>): BuilderShopConfirmation.Rejected =
        BuilderShopConfirmation.Rejected(path, values.toMap())

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    override fun close() {
        estimates.clear()
    }

    private companion object {
        const val MAX_DISPLAYED_MARKET_LINES = 12
        const val MAX_FORMATTED_PRICE_LENGTH = 80
    }
}
