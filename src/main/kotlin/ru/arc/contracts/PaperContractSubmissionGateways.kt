package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Paper inventory adapter for plain vanilla resource orders. Custom namespaces
 * and any ItemMeta/NBT-bearing variants fail closed until an exact resolver is
 * implemented for that item family.
 */
class PaperContractInventoryGateway(
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
) : ContractInventoryGateway {
    override suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
    ): PreparedContractInventory? =
        onBukkitMain {
            val uuid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return@onBukkitMain null
            val player = playerLookup(uuid)?.takeIf { it.isOnline } ?: return@onBukkitMain null
            val material = vanillaMaterial(itemKey) ?: return@onBukkitMain null
            require(quantity in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY) { "Invalid contract inventory quantity" }

            var remaining = quantity
            val slots = mutableListOf<PaperContractSlotPlan>()
            player.inventory.storageContents.forEachIndexed { slot, stack ->
                if (remaining == 0 || stack == null || !isPlainExact(stack, material, itemKey)) return@forEachIndexed
                val take = minOf(stack.amount, remaining)
                val removed = stack.clone().also { it.amount = take }
                slots +=
                    PaperContractSlotPlan(
                        slot = slot,
                        removeQuantity = take,
                        beforeBytes = stack.serializeAsBytes(),
                        payload = PaperContractItemPayloadCodec.captureVerified(itemKey, removed),
                    )
                remaining -= take
            }
            if (remaining != 0 || slots.isEmpty()) return@onBukkitMain null
            PaperPreparedContractInventory(uuid, playerLookup, slots)
        }

    private fun vanillaMaterial(itemKey: String): Material? {
        if (!itemKey.startsWith("minecraft:")) return null
        val material = Material.matchMaterial(itemKey.substringAfter(':')) ?: return null
        return material.takeIf { it.isItem && !it.isAir }
    }

    private fun isPlainExact(stack: ItemStack, material: Material, itemKey: String): Boolean =
        stack.type == material &&
            stack.type.key.toString() == itemKey &&
            stack.isSimilar(ItemStack(material, stack.amount))
}

private data class PaperContractSlotPlan(
    val slot: Int,
    val removeQuantity: Int,
    val beforeBytes: ByteArray,
    val payload: EscrowedItemPayload,
)

private class PaperPreparedContractInventory(
    private val playerId: UUID,
    private val playerLookup: (UUID) -> Player?,
    private val slots: List<PaperContractSlotPlan>,
) : PreparedContractInventory {
    override val payloads: List<EscrowedItemPayload> = slots.map { it.payload }
    private var removed = false

    override suspend fun removeExact(): ContractInventoryMutation =
        onBukkitMain {
            if (removed) return@onBukkitMain ContractInventoryMutation.Ambiguous
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val inventory = player.inventory
            if (slots.any { plan -> !inventory.getItem(plan.slot).sameBytes(plan.beforeBytes) }) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("slot_changed")
            }

            try {
                slots.forEach { plan ->
                    val current = requireNotNull(inventory.getItem(plan.slot))
                    val remaining = current.amount - plan.removeQuantity
                    require(remaining >= 0) { "Contract slot underflow" }
                    if (remaining == 0) {
                        inventory.setItem(plan.slot, null)
                    } else {
                        inventory.setItem(plan.slot, current.clone().also { it.amount = remaining })
                    }
                }
                if (slots.any { plan -> !inventory.getItem(plan.slot).isExpectedAfterRemoval(plan) }) {
                    return@onBukkitMain ContractInventoryMutation.Ambiguous
                }
                removed = true
                ContractInventoryMutation.Confirmed
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    override suspend fun restoreExact(): ContractInventoryMutation =
        onBukkitMain {
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val inventory = player.inventory
            if (!removed) {
                return@onBukkitMain if (slots.all { inventory.getItem(it.slot).sameBytes(it.beforeBytes) }) {
                    ContractInventoryMutation.Confirmed
                } else {
                    ContractInventoryMutation.NotPerformed("escrow_not_removed")
                }
            }
            if (slots.any { plan -> !inventory.getItem(plan.slot).isExpectedAfterRemoval(plan) }) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("refund_slot_changed")
            }

            try {
                slots.forEach { plan -> inventory.setItem(plan.slot, ItemStack.deserializeBytes(plan.beforeBytes)) }
                if (slots.any { plan -> !inventory.getItem(plan.slot).sameBytes(plan.beforeBytes) }) {
                    return@onBukkitMain ContractInventoryMutation.Ambiguous
                }
                removed = false
                ContractInventoryMutation.Confirmed
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    private fun ItemStack?.isExpectedAfterRemoval(plan: PaperContractSlotPlan): Boolean {
        val before = runCatching { ItemStack.deserializeBytes(plan.beforeBytes) }.getOrNull() ?: return false
        val expectedAmount = before.amount - plan.removeQuantity
        return if (expectedAmount == 0) {
            this == null || type.isAir
        } else {
            sameBytes(before.clone().also { it.amount = expectedAmount }.serializeAsBytes())
        }
    }
}

private fun ItemStack?.sameBytes(expected: ByteArray): Boolean =
    this != null && runCatching { serializeAsBytes().contentEquals(expected) }.getOrDefault(false)

/** Exact RedisEconomy 4.5.12 adapter; the provider call is never retried. */
class RedisEconomyContractPaymentGateway(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : ContractPaymentGateway {
    override suspend fun balanceMinor(playerId: String): Long? {
        val uuid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return null
        val currency = apiProvider()?.defaultCurrency ?: return null
        return runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull()
    }

    override suspend fun deposit(
        playerId: String,
        amountMinor: Long,
        reason: String,
    ): ContractPaymentEvidence {
        require(amountMinor > 0L) { "Contract payout must be positive" }
        val uuid = runCatching { UUID.fromString(playerId) }.getOrNull()
            ?: return ContractPaymentEvidence(false, null, "invalid_player_id")
        val currency = apiProvider()?.defaultCurrency
            ?: return ContractPaymentEvidence(false, null, "provider_unavailable")
        val amount = BigDecimal.valueOf(amountMinor, 2).toDouble()
        val response: EconomyResponse =
            try {
                currency.depositPlayer(uuid, currency.currencyName, amount, reason)
            } catch (_: Throwable) {
                return ContractPaymentEvidence(
                    providerAccepted = null,
                    balanceAfterMinor = runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull(),
                )
            }
        val after = runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull()
        return ContractPaymentEvidence(
            providerAccepted = response.transactionSuccess(),
            balanceAfterMinor = after,
            failureCode = if (response.transactionSuccess()) null else "provider_rejected",
        )
    }

    private fun Double.toExactMinor(): Long? {
        if (!isFinite()) return null
        return runCatching {
            BigDecimal.valueOf(this)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        }.getOrNull()
    }
}

private suspend fun <T> onBukkitMain(block: () -> T): T {
    if (Bukkit.isPrimaryThread()) return block()
    return suspendCancellableCoroutine { continuation ->
        try {
            Tasks.scheduler.runSync(
                Runnable {
                    if (!continuation.isActive) return@Runnable
                    try {
                        continuation.resume(block())
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                },
            )
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}
