package ru.arc.investigation

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.plugin.PluginManager
import java.util.UUID

class InvestigationWalletTest : StringSpec({
    "an unavailable wallet never resolves the absent optional RedisEconomy API" {
        val loader = RedisEconomyAbsentClassLoader(InvestigationWalletTest::class.java.classLoader)
        val manager = mockk<PluginManager>()
        every { manager.isPluginEnabled("RedisEconomy") } returns false
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getPluginManager() } returns manager
            val type = Class.forName("ru.arc.investigation.RedisEconomyInvestigationWallet", true, loader)
            val wallet = type.getDeclaredConstructor().newInstance() as InvestigationWallet
            wallet.javaClass.classLoader shouldBe loader
            verify(exactly = 0) { manager.isPluginEnabled(any<String>()) }

            val playerId = UUID.randomUUID()
            val reason = "arc-investigation:11111111-1111-1111-1111-111111111111"
            wallet.available shouldBe false
            wallet.balanceMinor(playerId) shouldBe null
            wallet.withdraw(playerId, 100, reason, 1_000) shouldBe
                InvestigationMoneyEvidence(false, false, null, "provider_unavailable")
            wallet.deposit(playerId, 100, reason, 1_000) shouldBe
                InvestigationMoneyEvidence(false, false, null, "provider_unavailable")
            wallet.findTransaction(playerId, -100, reason, 0).getNow(null) shouldBe
                InvestigationProviderEvidence(null, false)
            loader.optionalClassLoads shouldBe emptyList()
        } finally { unmockkStatic(Bukkit::class) }
    }

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
        every { currency.withdrawPlayer(playerId, "CachedName", 100.0, reason) } returns
            EconomyResponse(100.0, 1_970_047.040334559, EconomyResponse.ResponseType.SUCCESS, null)
        val wallet = RedisEconomyInvestigationWallet(playerName = { "CachedName" }) { api }

        wallet.balanceMinor(playerId) shouldBe 197_014_704L
        wallet.withdraw(playerId, 10_000L, reason, 197_014_704L) shouldBe
            InvestigationMoneyEvidence(true, true, 197_004_704L)
        verify(exactly = 1) { currency.withdrawPlayer(playerId, "CachedName", 100.0, reason) }
        verify(exactly = 0) { currency.withdrawPlayer(playerId, "vault", any(), any()) }
    }

    "rejects non-finite provider balances before a mutation" {
        val playerId = UUID.randomUUID()
        val currency = mockk<Currency>()
        val api = mockk<RedisEconomyAPI>()
        every { api.defaultCurrency } returns currency
        every { currency.getBalance(playerId) } returns Double.NaN

        RedisEconomyInvestigationWallet { api }.balanceMinor(playerId) shouldBe null
    }

    "the UUID overload receives the nullable cached profile name without a cold offline name lookup" {
        val playerId = UUID.randomUUID()
        val reason = "arc-investigation-reward:11111111-1111-1111-1111-111111111111"
        val currency = mockk<Currency>()
        val api = mockk<RedisEconomyAPI>()
        val offline = mockk<org.bukkit.OfflinePlayer>()
        every { api.defaultCurrency } returns currency
        every { currency.transactionTax } returns 0.0
        every { currency.getBalance(playerId) } returnsMany listOf(1_000.0, 1_300.0)
        every { currency.depositPlayer(playerId, null, 300.0, reason) } returns
            EconomyResponse(300.0, 1_300.0, EconomyResponse.ResponseType.SUCCESS, null)
        every { offline.name } throws IllegalStateException("Cold offline name lookup")
        every { offline.playerProfile } returns mockk { every { name } returns null }
        mockkStatic(org.bukkit.Bukkit::class)
        try {
            every { org.bukkit.Bukkit.getPlayer(playerId) } returns null
            every { org.bukkit.Bukkit.getOfflinePlayer(playerId) } returns offline
            RedisEconomyInvestigationWallet { api }.deposit(playerId, 30_000, reason, 100_000) shouldBe
                InvestigationMoneyEvidence(true, true, 130_000)
            verify(exactly = 1) { currency.depositPlayer(playerId, null, 300.0, reason) }
            verify(exactly = 0) { offline.name }
        } finally { unmockkStatic(org.bukkit.Bukkit::class) }
    }
})

/** Loads the production facade/adapter in isolation while sharing its public domain port. */
private class RedisEconomyAbsentClassLoader(private val source: ClassLoader) : ClassLoader(source) {
    val optionalClassLoads = mutableListOf<String>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("dev.unnm3d.rediseconomy.")) {
            optionalClassLoads += name
            throw ClassNotFoundException("RedisEconomy is intentionally absent")
        }
        if (!name.startsWith("ru.arc.investigation.RedisEconomyInvestigation") &&
            name != "ru.arc.investigation.InvestigationWalletKt") return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded ->
                if (resolve) resolveClass(loaded)
                return loaded
            }
            val bytes = source.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size).also { if (resolve) resolveClass(it) }
        }
    }
}
