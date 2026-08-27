package ru.arc.investigation

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.EconomyResponse
import java.util.UUID

class InvestigationWalletTest : StringSpec({
    "normalizes a provider subcent remainder and proves the exact fee delta" {
        val playerId = UUID.randomUUID()
        val reason = "arc-investigation:11111111-1111-1111-1111-111111111111"
        val currency = mockk<Currency>()
        val api = mockk<RedisEconomyAPI>()
        every { api.defaultCurrency } returns currency
        every { currency.currencyName } returns "vault"
        every { currency.transactionTax } returns 0.0
        every { currency.getBalance(playerId) } returnsMany
            listOf(1_970_147.040334559, 1_970_147.040334559, 1_970_047.040334559)
        every { currency.withdrawPlayer(playerId, "vault", 100.0, reason) } returns
            EconomyResponse(100.0, 1_970_047.040334559, EconomyResponse.ResponseType.SUCCESS, null)
        val wallet = RedisEconomyInvestigationWallet { api }

        wallet.balanceMinor(playerId) shouldBe 197_014_704L
        wallet.withdraw(playerId, 10_000L, reason, 197_014_704L) shouldBe
            InvestigationMoneyEvidence(true, true, 197_004_704L)
        verify(exactly = 1) { currency.withdrawPlayer(playerId, "vault", 100.0, reason) }
    }

    "rejects non-finite provider balances before a mutation" {
        val playerId = UUID.randomUUID()
        val currency = mockk<Currency>()
        val api = mockk<RedisEconomyAPI>()
        every { api.defaultCurrency } returns currency
        every { currency.getBalance(playerId) } returns Double.NaN

        RedisEconomyInvestigationWallet { api }.balanceMinor(playerId) shouldBe null
    }
})
