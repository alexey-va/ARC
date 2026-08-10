package ru.arc.hooks.economyshop

import me.gypopo.economyshopgui.api.events.PostTransactionEvent
import me.gypopo.economyshopgui.objects.ShopItem
import me.gypopo.economyshopgui.util.EcoType
import me.gypopo.economyshopgui.util.EconomyType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.audit.AuditMetadata
import ru.arc.audit.EconomyBalanceObservation
import ru.arc.audit.EconomyEventStatus
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomyLedgerContext
import ru.arc.audit.EconomyLedgerItem
import ru.arc.audit.EconomyPendingContextTracker
import ru.arc.audit.EconomyRecordKind
import ru.arc.audit.EconomySource
import ru.arc.audit.Type
import ru.arc.hooks.HookRegistry
import java.util.UUID
import kotlin.math.abs

/** Adds item, price and explicit outcome evidence around EconomyShopGUI mutations. */
internal class EconomyShopGuiAuditListener(
    private val now: () -> Long = System::currentTimeMillis,
    private val correlationId: () -> String = { UUID.randomUUID().toString() },
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPostTransaction(event: PostTransactionEvent) {
        val capturedAt = now()
        val player = event.player
        val action = event.transactionType.name.lowercase()
        val priceComponents = priceComponents(event)
        val vaultPrice = vaultPrice(event, priceComponents)
        val mapped = EconomyShopAuditMapper.map(event.transactionType.name, event.transactionResult.name, vaultPrice)
        val source = mapped.source
        val succeeded = mapped.status == EconomyEventStatus.SUCCEEDED
        val requestedAmount = mapped.requestedAmount
        val session = AuditManager.session(player.uniqueId, player.world.name)
        val balanceAfter = HookRegistry.redisEcoHook?.getCachedBalance(player.uniqueId, "vault")
        val balance =
            when {
                balanceAfter == null -> null
                succeeded && requestedAmount != null -> EconomyBalanceObservation.inferredFromAfter(requestedAmount, balanceAfter)
                !succeeded -> EconomyBalanceObservation.unchanged(balanceAfter)
                else -> null
            }
        val items = items(event, vaultPrice)
        val shopId = shopId(event)
        val context =
            EconomyLedgerContext(
                recordKind = EconomyRecordKind.ATTEMPT,
                status = mapped.status,
                accountId = player.uniqueId.toString(),
                correlationId = correlationId(),
                world = session?.world ?: player.world.name,
                sessionId = session?.sessionId,
                sessionStartedAt = session?.startedAt,
                balanceBefore = balance?.before,
                balanceAfter = balance?.after ?: balanceAfter,
                balanceEvidence = balance?.evidence,
                requestedAmount = requestedAmount,
                action = action,
                shopId = shopId,
                items = items,
                priceComponents = priceComponents,
                failureReason = event.transactionResult.name.lowercase().takeIf { !succeeded },
                capturedAt = capturedAt,
            )
        val metadata =
            AuditMetadata(
                source = source,
                flow = mapped.flow,
                currency = if (vaultPrice == null) "non_vault" else "vault",
                server = ARC.serverName ?: "unknown",
                origin = "EconomyShopGUI:${event.transactionType.name}",
            )
        AuditManager.economyAttempt(
            player.name,
            Type.SHOP,
            "${event.transactionType.name}:${event.transactionResult.name}",
            metadata,
            context,
        )
        if (succeeded && requestedAmount != null) {
            EconomyPendingContextTracker.register(player.uniqueId, requestedAmount, context, capturedAt)
        }
    }

    private fun priceComponents(event: PostTransactionEvent): Map<String, Double> {
        val mapped =
            event.prices.orEmpty().entries
                .asSequence()
                .filter { (_, amount) -> amount.isFinite() }
                .take(MAX_PRICE_COMPONENTS)
                .associate { (type, amount) -> priceKey(type) to amount }
        if (mapped.isNotEmpty()) return mapped.toSortedMap()
        return event.price.takeIf(Double::isFinite)?.let { mapOf("vault" to it) }.orEmpty()
    }

    private fun vaultPrice(event: PostTransactionEvent, components: Map<String, Double>): Double? {
        event.prices.orEmpty().entries.firstOrNull { (type, amount) ->
            type.type == EconomyType.VAULT && amount.isFinite()
        }?.let { return it.value }
        return components["vault"]?.takeIf(Double::isFinite)
    }

    private fun priceKey(type: EcoType): String {
        val currency = type.currency?.trim()?.lowercase().orEmpty()
        return (if (currency.isBlank()) type.type.name.lowercase() else "${type.type.name.lowercase()}:$currency").take(80)
    }

    private fun items(event: PostTransactionEvent, vaultPrice: Double?): List<EconomyLedgerItem> {
        val multi = event.items.orEmpty()
        if (multi.isNotEmpty()) {
            return multi.entries
                .sortedBy { (item, _) -> item.itemPath }
                .take(MAX_ITEMS)
                .map { (item, quantity) -> itemEvidence(item, quantity, null) }
        }
        val item = event.shopItem ?: return emptyList()
        val quantity = event.amount.coerceAtLeast(0)
        val unitPrice = vaultPrice?.takeIf { quantity > 0 }?.let { abs(it) / quantity }
        return listOf(itemEvidence(item, quantity, unitPrice))
    }

    private fun itemEvidence(item: ShopItem, quantity: Int, unitPrice: Double?): EconomyLedgerItem =
        EconomyLedgerItem(
            key = item.itemPath.take(160),
            material = runCatching { item.shopItem.type.key.asString() }.getOrNull()?.take(120),
            quantity = quantity.coerceAtLeast(0),
            unitPrice = unitPrice?.takeIf(Double::isFinite),
        )

    private fun shopId(event: PostTransactionEvent): String? {
        event.shopItem?.let { item ->
            item.section?.takeIf(String::isNotBlank)?.let { return it.take(80) }
            item.itemPath.substringBefore('.').takeIf(String::isNotBlank)?.let { return it.take(80) }
        }
        val sections =
            event.items.orEmpty().keys.mapNotNull { item ->
                item.section?.takeIf(String::isNotBlank) ?: item.itemPath.substringBefore('.').takeIf(String::isNotBlank)
            }.distinct()
        return when (sections.size) {
            0 -> null
            1 -> sections.single().take(80)
            else -> "multi"
        }
    }

    private companion object {
        const val MAX_ITEMS = 64
        const val MAX_PRICE_COMPONENTS = 16
    }
}

internal data class EconomyShopAuditMapping(
    val source: EconomySource,
    val status: EconomyEventStatus,
    val flow: EconomyFlow,
    val requestedAmount: Double?,
)

/** Pure mapping kept independent of EconomyShopGUI's runtime-initialized enum constructors. */
internal object EconomyShopAuditMapper {
    fun map(transactionType: String, result: String, vaultPrice: Double?): EconomyShopAuditMapping {
        val normalizedType = transactionType.uppercase()
        val normalizedResult = result.uppercase()
        val status =
            when (normalizedResult) {
                "SUCCESS", "SUCCESS_COMMANDS_EXECUTED" -> EconomyEventStatus.SUCCEEDED
                "TRANSACTION_CANCELLED" -> EconomyEventStatus.CANCELLED
                else -> EconomyEventStatus.FAILED
            }
        val direction = if (normalizedType.contains("SELL") || normalizedType == "AUTO_SELL_CHEST") 1.0 else -1.0
        val requestedAmount = vaultPrice?.takeIf(Double::isFinite)?.let { direction * abs(it) }
        return EconomyShopAuditMapping(
            source = if (normalizedType == "AUTO_SELL_CHEST") EconomySource.AUTOSELL else EconomySource.SHOP,
            status = status,
            flow =
                when {
                    requestedAmount == null -> EconomyFlow.UNKNOWN
                    requestedAmount > 0.0 -> EconomyFlow.MINT
                    else -> EconomyFlow.BURN
                },
            requestedAmount = requestedAmount,
        )
    }
}
