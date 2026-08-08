package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import ru.arc.core.Tasks
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisEcoHook(
    private val runAsync: (Runnable) -> Unit = { Tasks.scheduler.runAsync(it) },
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) {
    @JvmRecord
    data class Account(@JvmField val name: String?, @JvmField val uuid: UUID?, @JvmField val balance: Double)

    fun getAccounts(players: List<UUID>): CompletableFuture<List<Account>> {
        val api = apiProvider() ?: return CompletableFuture.completedFuture(emptyList())
        val currency = api.defaultCurrency
        val balances =
            players
                .distinct()
                .associateWith { playerId ->
                    currency.getAccountRedis(playerId).toCompletableFuture()
                }
        if (balances.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        return CompletableFuture
            .allOf(*balances.values.toTypedArray())
            .thenApply {
                balances.map { (playerId, balanceFuture) ->
                    Account(
                        name = api.getUsernameFromUUIDCache(playerId),
                        uuid = playerId,
                        balance = balanceFuture.getNow(0.0),
                    )
                }
            }
    }

    fun getTopAccounts(n: Int): CompletableFuture<List<Account>> {
        require(n >= 0) { "Account limit must not be negative" }
        val api = apiProvider() ?: return CompletableFuture.completedFuture(emptyList())
        val currency = api.defaultCurrency

        // RedisEconomy 4.5+ relocates Lettuce inside its plugin JAR. Its public cache
        // keeps this integration independent of those version-specific shaded types.
        val result = CompletableFuture<List<Account>>()
        try {
            runAsync(
                Runnable {
                    try {
                        val accounts =
                            currency.accounts.entries
                                .sortedByDescending { it.value }
                                .take(n)
                                .map { (uuid, balance) ->
                                    Account(
                                        name = api.getUsernameFromUUIDCache(uuid),
                                        uuid = uuid,
                                        balance = balance,
                                    )
                                }
                        result.complete(accounts)
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                },
            )
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }
}
