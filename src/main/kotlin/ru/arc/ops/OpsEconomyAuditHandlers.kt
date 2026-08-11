package ru.arc.ops

import ru.arc.audit.AuditManager
import ru.arc.audit.autosell.AutoSellAuditModule
import ru.arc.audit.bank.BankAuditModule
import ru.arc.hooks.HookRegistry
import java.util.concurrent.TimeUnit

/** Authenticated, read-only economy ledger summary for balancing and exploit triage. */
object OpsEconomyAuditHandlers {
    fun summary(hours: Int, limit: Int, serverFilter: String?): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, 100)
        val result = LinkedHashMap(AuditManager.economySummary(hours, safeLimit, serverFilter))
        result["autoSellAudit"] = AutoSellAuditModule.summary()
        val bankAudit = BankAuditModule.summary(safeLimit)
        result["bankAudit"] = bankAudit
        val hook = HookRegistry.redisEcoHook
        @Suppress("UNCHECKED_CAST")
        val bankTopBalances =
            (bankAudit["topKnownAccounts"] as? List<Map<String, Any?>>)
                ?.takeIf { bankAudit["status"] == "ready" && bankAudit["complete"] == true }
        val topBalances =
            if (!bankTopBalances.isNullOrEmpty()) {
                Result.success(bankTopBalances)
            } else runCatching {
                hook
                    ?.getTopAccounts(safeLimit)
                    ?.thenApply { accounts ->
                        accounts
                            .map { account ->
                                val bankAccount =
                                    runCatching {
                                        HookRegistry.bankHook?.account(account.uuid?.toString().orEmpty(), account.name)
                                    }.getOrNull()
                                val bankBalance = bankAccount?.balance ?: 0.0
                                val pendingInterest = bankAccount?.pendingInterest ?: 0.0
                                val bankSupply = bankBalance + pendingInterest
                                linkedMapOf(
                                    "player" to (account.name ?: "unknown"),
                                    "vaultBalance" to account.balance,
                                    "bankBalance" to bankBalance,
                                    "pendingInterest" to pendingInterest,
                                    "bankSupply" to bankSupply,
                                    "totalBalance" to account.balance + bankSupply,
                                )
                            }.sortedByDescending { (it["totalBalance"] as Number).toDouble() }
                    }?.get(5, TimeUnit.SECONDS)
                    .orEmpty()
            }
        result["topBalances"] = topBalances.getOrDefault(emptyList())
        result["topBalancesAvailable"] = hook != null && topBalances.isSuccess
        result["topBalancesCoverage"] =
            if (!bankTopBalances.isNullOrEmpty()) {
                "single-leader network snapshot across the RedisEconomy account cache"
            } else {
                "fallback: vault-ranked accounts enriched with Bank; bank-only accounts outside the Vault top may be absent"
            }
        result["ledgerScope"] = "network-shared server-player shards"
        result["recentAnomaliesScope"] = "local process since last ARC start"
        result["auditWeight"] = AuditManager.weight()
        return result
    }
}
