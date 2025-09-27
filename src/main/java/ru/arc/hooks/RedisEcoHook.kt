package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import lombok.extern.slf4j.Slf4j
import java.util.*
import java.util.concurrent.CompletableFuture

@Slf4j
class RedisEcoHook {
    @JvmRecord
    data class Account(@JvmField val name: String?, @JvmField val uuid: UUID?, @JvmField val balance: Double)

    fun getAccounts(players: List<UUID>): CompletableFuture<List<Account>> {
        return CompletableFuture.supplyAsync {
            players.associateWith {
                RedisEconomyAPI.getAPI()
                    ?.defaultCurrency
                    ?.getAccountRedis(it)
            }
                .mapValues { it.value?.toCompletableFuture()?.join() ?: 0.0 }
                .map {
                    val playerName = RedisEconomyAPI.getAPI()?.getUsernameFromUUIDCache(it.key)
                    Account(
                        name = playerName,
                        uuid = it.key,
                        balance = it.value,
                    )
                }
        }
    }

    fun getTopAccounts(n: Int): CompletableFuture<List<Account>> {
        if (RedisEconomyAPI.getAPI() == null) return CompletableFuture.completedFuture(listOf())
        return RedisEconomyAPI.getAPI()
            ?.defaultCurrency
            ?.getOrderedAccounts(n)
            ?.thenApply { balances ->
                balances.orEmpty()
                    .map {
                        val uuid = UUID.fromString(it.value)
                        val playerName = RedisEconomyAPI.getAPI()?.getUsernameFromUUIDCache(uuid)
                        Account(
                            name = playerName,
                            uuid = uuid,
                            balance = it.score
                        )
                    }.toList()
            }?.toCompletableFuture() ?: CompletableFuture.completedFuture(listOf())
    }
}
