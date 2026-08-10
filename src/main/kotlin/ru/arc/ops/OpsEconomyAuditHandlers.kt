package ru.arc.ops

import ru.arc.audit.AuditManager
import ru.arc.hooks.HookRegistry
import java.util.concurrent.TimeUnit

/** Authenticated, read-only economy ledger summary for balancing and exploit triage. */
object OpsEconomyAuditHandlers {
    fun summary(hours: Int, limit: Int, serverFilter: String?): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, 100)
        val result = LinkedHashMap(AuditManager.economySummary(hours, safeLimit, serverFilter))
        val hook = HookRegistry.redisEcoHook
        val topBalances =
            runCatching {
                hook
                    ?.getTopAccounts(safeLimit)
                    ?.thenApply { accounts ->
                        accounts
                            .map { account ->
                                val bankBalance =
                                    account.uuid?.let { uuid ->
                                        runCatching { HookRegistry.bankHook?.offlineBalance(uuid.toString()) ?: 0.0 }.getOrDefault(0.0)
                                    } ?: 0.0
                                linkedMapOf(
                                    "player" to (account.name ?: "unknown"),
                                    "vaultBalance" to account.balance,
                                    "bankBalance" to bankBalance,
                                    "totalBalance" to account.balance + bankBalance,
                                )
                            }.sortedByDescending { (it["totalBalance"] as Number).toDouble() }
                    }?.get(5, TimeUnit.SECONDS)
                    .orEmpty()
            }
        result["topBalances"] = topBalances.getOrDefault(emptyList())
        result["topBalancesAvailable"] = hook != null && topBalances.isSuccess
        result["topBalancesCoverage"] = "vault-ranked accounts enriched with Bank; bank-only accounts outside the Vault top may be absent"
        result["ledgerScope"] = "network-shared server-player shards"
        result["recentAnomaliesScope"] = "local process since last ARC start"
        result["auditWeight"] = AuditManager.weight()
        return result
    }
}
