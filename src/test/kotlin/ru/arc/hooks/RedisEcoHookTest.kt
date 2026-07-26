package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.lettuce.core.ScoredValue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisEcoHookTest {
    @Test
    fun `specific accounts are loaded concurrently and duplicate ids are removed`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val currency = mockk<Currency>()
        every { currency.getAccountRedis(first) } returns CompletableFuture.completedFuture(12.5)
        every { currency.getAccountRedis(second) } returns CompletableFuture.completedFuture(7.0)
        val api = api(currency, mapOf(first to "First", second to "Second"))
        val hook = RedisEcoHook { api }

        val accounts = hook.getAccounts(listOf(first, second, first)).join()

        assertEquals(
            listOf(
                RedisEcoHook.Account("First", first, 12.5),
                RedisEcoHook.Account("Second", second, 7.0),
            ),
            accounts,
        )
    }

    @Test
    fun `top accounts use typed scored values and skip malformed ids`() {
        val playerId = UUID.randomUUID()
        val currency = mockk<Currency>()
        every { currency.getOrderedAccounts(10) } returns
            CompletableFuture.completedFuture(
                listOf(
                    ScoredValue.just(99.25, playerId.toString()),
                    ScoredValue.just(50.0, "not-a-uuid"),
                ),
            )
        val api = api(currency, mapOf(playerId to "Player"))
        val hook = RedisEcoHook { api }

        val accounts = hook.getTopAccounts(10).join()

        assertEquals(listOf(RedisEcoHook.Account("Player", playerId, 99.25)), accounts)
    }

    @Test
    fun `missing api returns empty results and negative top limit is rejected`() {
        val hook = RedisEcoHook { null }

        assertEquals(emptyList<RedisEcoHook.Account>(), hook.getAccounts(listOf(UUID.randomUUID())).join())
        assertEquals(emptyList<RedisEcoHook.Account>(), hook.getTopAccounts(0).join())
        assertThrows(IllegalArgumentException::class.java) {
            hook.getTopAccounts(-1)
        }
    }

    private fun api(
        currency: Currency,
        names: Map<UUID, String>,
    ): RedisEconomyAPI =
        mockk {
            every { defaultCurrency } returns currency
            every { getUsernameFromUUIDCache(any()) } answers { names[firstArg()] }
        }
}
