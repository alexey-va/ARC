package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import io.lettuce.core.ScoredValue
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisEcoHook(
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

        return currency.getOrderedAccounts(n).thenApply { balances ->
            balances.mapNotNull { scored: ScoredValue<String> ->
                if (!scored.hasValue()) return@mapNotNull null
                val uuid = runCatching { UUID.fromString(scored.value) }.getOrNull()
                    ?: return@mapNotNull null

                Account(
                    name = api.getUsernameFromUUIDCache(uuid),
                    uuid = uuid,
                    balance = scored.score,
                )
            }
        }.toCompletableFuture()
    }
}
