package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

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
        val api = RedisEconomyAPI.getAPI() ?: return CompletableFuture.completedFuture(emptyList())
        val currency = api.defaultCurrency

        @Suppress("UNCHECKED_CAST")
        val stage = currency.getOrderedAccounts(n) as? CompletionStage<Any?>
            ?: return CompletableFuture.completedFuture(emptyList())

        return stage.thenApply { result ->
            val balances = result as? List<*> ?: emptyList<Any?>()

            balances.mapNotNull { scored ->
                if (scored == null) return@mapNotNull null

                val clazz = scored.javaClass

                // Extract `value` via reflection (field or getter)
                val valueString: String? = runCatching {
                    // Try public/declared field "value"
                    clazz.getDeclaredField("value").apply { isAccessible = true }
                        .get(scored) as? String
                }.recoverCatching {
                    // Try getter methods
                    clazz.methods.firstOrNull { m ->
                        m.parameterCount == 0 && (m.name == "getValue" || m.name == "value")
                    }?.invoke(scored) as? String
                }.getOrNull()

                // Extract `score` via reflection (field or getter)
                val scoreDouble: Double? = runCatching {
                    clazz.getDeclaredField("score").apply { isAccessible = true }
                        .get(scored) as? Number
                }.recoverCatching {
                    clazz.methods.firstOrNull { m ->
                        m.parameterCount == 0 && (m.name == "getScore" || m.name == "score")
                    }?.invoke(scored) as? Number
                }.getOrNull()?.toDouble()

                if (valueString == null || scoreDouble == null) return@mapNotNull null

                val uuid = runCatching { UUID.fromString(valueString) }.getOrNull()
                    ?: return@mapNotNull null

                val playerName = RedisEconomyAPI.getAPI()?.getUsernameFromUUIDCache(uuid)

                Account(
                    name = playerName,
                    uuid = uuid,
                    balance = scoreDouble
                )
            }
        }.toCompletableFuture()
    }
}
