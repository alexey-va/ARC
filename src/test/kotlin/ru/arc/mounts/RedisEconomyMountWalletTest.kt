package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI

class RedisEconomyMountWalletTest : StringSpec({
    "provider balance tolerates only sub-cent floating point drift" {
        1_970_147.040334559.toProviderMinorOrNull() shouldBe 197_014_704L
        10.0.toProviderMinorOrNull() shouldBe 1_000L
        10.001.toProviderMinorOrNull() shouldBe null
        Double.NaN.toProviderMinorOrNull() shouldBe null
    }

    "routes only to the explicitly named currency without default fallback" {
        val vault = mockk<dev.unnm3d.rediseconomy.currency.Currency>()
        val tokens = mockk<dev.unnm3d.rediseconomy.currency.Currency>()
        val api = mockk<RedisEconomyAPI>()
        every { api.getCurrencyByName("vault") } returns vault
        every { api.getCurrencyByName("tokens") } returns tokens
        every { api.getCurrencyByName("unknown") } returns null
        val wallet = RedisEconomyMountWallet({ api })

        wallet.walletForCurrency("vault") shouldBe wallet
        wallet.walletForCurrency("tokens")?.available shouldBe true
        wallet.walletForCurrency("unknown")?.available shouldBe false
    }
})
