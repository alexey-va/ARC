package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `top accounts use the public currency cache without touching relocated lettuce types`() {
        val richestId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val excludedId = UUID.randomUUID()
        val currency = mockk<Currency>()
        every { currency.accounts } returns
            mapOf(
                secondId to 50.0,
                excludedId to 10.0,
                richestId to 99.25,
            )
        val api =
            api(
                currency,
                mapOf(
                    richestId to "Richest",
                    secondId to "Second",
                    excludedId to "Excluded",
                ),
            )
        val hook = RedisEcoHook(apiProvider = { api }, runAsync = Runnable::run)

        val accounts = hook.getTopAccounts(2).join()

        assertEquals(
            listOf(
                RedisEcoHook.Account("Richest", richestId, 99.25),
                RedisEcoHook.Account("Second", secondId, 50.0),
            ),
            accounts,
        )
        verify(exactly = 0) { currency.getOrderedAccounts(any()) }
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
