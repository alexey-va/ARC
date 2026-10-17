package ru.arc.investigation

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.transaction.AccountID
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class InvestigationMoneyEvidence(
    val providerAccepted: Boolean?,
    val providerCallAttempted: Boolean,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
)

data class InvestigationProviderEvidence(
    val transactionId: String?,
    val historyAvailable: Boolean,
) {
    val found: Boolean get() = transactionId != null
}

interface InvestigationWallet {
    val available: Boolean

    fun balanceMinor(playerId: UUID): Long?

    fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence

    fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence

    fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<InvestigationProviderEvidence> =
        CompletableFuture.completedFuture(InvestigationProviderEvidence(null, false))
}

/** Exact RedisEconomy adapter. A provider mutation is attempted at most once. */
class RedisEconomyInvestigationWallet(
    private val playerName: (UUID) -> String? = { id -> Bukkit.getPlayer(id)?.name ?: Bukkit.getOfflinePlayer(id).playerProfile.name },
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : InvestigationWallet {
    override val available: Boolean get() = apiProvider()?.defaultCurrency != null

    override fun balanceMinor(playerId: UUID): Long? =
        runCatching { apiProvider()?.defaultCurrency?.getBalance(playerId)?.toMinorOrNull() }.getOrNull()

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): InvestigationMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = false)

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): InvestigationMoneyEvidence = mutate(playerId, amountMinor, reason, expectedBalanceBeforeMinor, deposit = true)

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<InvestigationProviderEvidence> = prepareHistory(playerId, amountMinor, reason, notBeforeMillis)()

    /** Capture the provider's mutable registry on Paper; invoke the returned history query on a worker. */
    internal fun prepareHistory(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): () -> CompletableFuture<InvestigationProviderEvidence> {
        require(amountMinor != 0L) { "Investigation provider history amount cannot be zero" }
        require(REASON_PATTERN.matches(reason)) { "Invalid investigation provider history reason" }
        val api = apiProvider()
            ?: return { CompletableFuture.completedFuture(InvestigationProviderEvidence(null, false)) }
        val currency = api.defaultCurrency
        if (!currency.shouldSaveTransactions()) {
            return { CompletableFuture.completedFuture(InvestigationProviderEvidence(null, false)) }
        }
        val currencyName = currency.currencyName
        val exchange = api.exchange
        return { exchange
            .getTransactions(AccountID(playerId), HISTORY_LIMIT)
            .toCompletableFuture()
            .handle { transactions, failure ->
                if (failure != null || transactions == null) return@handle InvestigationProviderEvidence(null, false)
                val match =
                    transactions.entries.firstOrNull { (_, transaction) ->
                        transaction.timestamp >= notBeforeMillis &&
                            transaction.currencyName == currencyName &&
                            transaction.reason.orEmpty().lineSequence().firstOrNull() == reason &&
                            transaction.amount.toMinorOrNull() == amountMinor
                    }
                InvestigationProviderEvidence(match?.key?.toString(), true)
            } }
    }

    private fun mutate(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
        deposit: Boolean,
    ): InvestigationMoneyEvidence {
        require(amountMinor > 0L) { "Investigation money mutation must be positive" }
        require(REASON_PATTERN.matches(reason)) { "Invalid investigation money reason" }
        val currency = apiProvider()?.defaultCurrency
            ?: return InvestigationMoneyEvidence(false, false, null, "provider_unavailable")
        val before = currency.getBalance(playerId).toMinorOrNull()
            ?: return InvestigationMoneyEvidence(false, false, null, "provider_balance_unavailable")
        if (before != expectedBalanceBeforeMinor) {
            return InvestigationMoneyEvidence(false, false, before, "provider_balance_changed_before_call")
        }
        if (currency.transactionTax != 0.0) {
            return InvestigationMoneyEvidence(false, false, before, "provider_transaction_tax_nonzero")
        }
        val amount = BigDecimal.valueOf(amountMinor, 2).toDouble()
        // The exact UUID overload's second argument is the account NAME, not a currency selector.
        val accountName = playerName(playerId)
        val response: EconomyResponse =
            try {
                if (deposit) {
                    currency.depositPlayer(playerId, accountName, amount, reason)
                } else {
                    currency.withdrawPlayer(playerId, accountName, amount, reason)
                }
            } catch (_: Throwable) {
                return InvestigationMoneyEvidence(
                    providerAccepted = null,
                    providerCallAttempted = true,
                    balanceAfterMinor = currency.getBalance(playerId).toMinorOrNull(),
                    failureCode = "provider_threw",
                )
            }
        return InvestigationMoneyEvidence(
            providerAccepted = response.transactionSuccess(),
            providerCallAttempted = true,
            balanceAfterMinor = currency.getBalance(playerId).toMinorOrNull(),
            failureCode = if (response.transactionSuccess()) null else "provider_rejected",
        )
    }

    companion object {
        private const val HISTORY_LIMIT = 512
        private val REASON_PATTERN = Regex("arc-investigation(?:-reward)?:[0-9a-f-]{36}")
    }
}

private fun Double.toMinorOrNull(): Long? {
    if (!isFinite()) return null
    return runCatching {
        BigDecimal.valueOf(this)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}
