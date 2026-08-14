package ru.arc.mounts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.transaction.AccountID
import net.milkbowl.vault.economy.EconomyResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.roundToLong

data class MountMoneyEvidence(
    val providerAccepted: Boolean?,
    val providerCallAttempted: Boolean,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
)

data class MountProviderTransactionEvidence(
    val transactionId: String?,
    val historyAvailable: Boolean,
) {
    val found: Boolean get() = transactionId != null
}

interface MountWallet {
    val available: Boolean

    fun balanceMinor(playerId: UUID): Long?

    fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence

    fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence

    fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> =
        CompletableFuture.completedFuture(MountProviderTransactionEvidence(null, false))
}

/** Exact RedisEconomy 4.5.12 mount sink adapter. Provider mutation calls are never retried. */
class RedisEconomyMountWallet(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : MountWallet {
    override val available: Boolean get() = apiProvider()?.defaultCurrency != null

    override fun balanceMinor(playerId: UUID): Long? =
        runCatching { apiProvider()?.defaultCurrency?.getBalance(playerId)?.toProviderMinorOrNull() }.getOrNull()

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = false)

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): MountMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = true)

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> {
        require(amountMinor != 0L) { "Mount provider history amount cannot be zero" }
        require(REASON_PATTERN.matches(reason)) { "Invalid mount provider history reason" }
        val api = apiProvider()
            ?: return CompletableFuture.completedFuture(MountProviderTransactionEvidence(null, false))
        val currency = api.defaultCurrency
        if (!currency.shouldSaveTransactions()) {
            return CompletableFuture.completedFuture(MountProviderTransactionEvidence(null, false))
        }
        return api.exchange
            .getTransactions(AccountID(playerId), HISTORY_LIMIT)
            .toCompletableFuture()
            .handle { transactions, failure ->
                if (failure != null || transactions == null) return@handle MountProviderTransactionEvidence(null, false)
                val match =
                    transactions.entries.firstOrNull { (_, transaction) ->
                        transaction.timestamp >= notBeforeMillis &&
                            transaction.currencyName == currency.currencyName &&
                            transaction.reason.orEmpty().lineSequence().firstOrNull() == reason &&
                            transaction.amount.toProviderMinorOrNull() == amountMinor
                    }
                MountProviderTransactionEvidence(match?.key?.toString(), true)
            }
    }

    private fun mutate(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
        deposit: Boolean,
    ): MountMoneyEvidence {
        require(amountMinor > 0L) { "Mount money mutation must be positive" }
        require(REASON_PATTERN.matches(reason)) { "Invalid mount money mutation reason" }
        val currency = apiProvider()?.defaultCurrency
            ?: return MountMoneyEvidence(false, false, null, "provider_unavailable")
        val before = currency.getBalance(playerId).toProviderMinorOrNull()
            ?: return MountMoneyEvidence(false, false, null, "provider_balance_unavailable")
        if (before != expectedBalanceBeforeMinor) {
            return MountMoneyEvidence(false, false, before, "provider_balance_changed_before_call")
        }
        if (currency.transactionTax != 0.0) {
            return MountMoneyEvidence(false, false, before, "provider_transaction_tax_nonzero")
        }
        val amount = BigDecimal.valueOf(amountMinor, 2).toDouble()
        val response: EconomyResponse =
            try {
                if (deposit) {
                    currency.depositPlayer(playerId, currency.currencyName, amount, reason)
                } else {
                    currency.withdrawPlayer(playerId, currency.currencyName, amount, reason)
                }
            } catch (_: Throwable) {
                return MountMoneyEvidence(
                    providerAccepted = null,
                    providerCallAttempted = true,
                    balanceAfterMinor = currency.getBalance(playerId).toProviderMinorOrNull(),
                    failureCode = "provider_threw",
                )
            }
        return MountMoneyEvidence(
            providerAccepted = response.transactionSuccess(),
            providerCallAttempted = true,
            balanceAfterMinor = currency.getBalance(playerId).toProviderMinorOrNull(),
            failureCode = if (response.transactionSuccess()) null else "provider_rejected",
        )
    }

    companion object {
        private const val HISTORY_LIMIT = 512
        private val REASON_PATTERN = Regex("arc-mount(?:-refund)?:[0-9a-f-]{36}")
    }
}

internal fun Double.toProviderMinorOrNull(): Long? {
    if (!isFinite()) return null
    val scaled = this * 100.0
    if (!scaled.isFinite() || scaled < Long.MIN_VALUE.toDouble() || scaled > Long.MAX_VALUE.toDouble()) return null
    val nearest = scaled.roundToLong()
    return nearest.takeIf { abs(scaled - nearest.toDouble()) <= PROVIDER_MINOR_DRIFT_TOLERANCE }
}

private const val PROVIDER_MINOR_DRIFT_TOLERANCE = 0.05

internal fun Double.toExactMinor(): Long =
    BigDecimal.valueOf(this)
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()

internal fun Long.minorToDouble(): Double = BigDecimal.valueOf(this, 2).toDouble()
