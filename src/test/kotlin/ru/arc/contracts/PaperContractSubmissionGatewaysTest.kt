package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PaperContractSubmissionGatewaysTest : StringSpec({
    lateinit var server: ServerMock

    beforeSpec {
        server = MockBukkit.mock()
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "removes and restores the exact prevalidated vanilla slots" {
        runTest {
            val player = server.addPlayer("ContractMiner")
            player.inventory.setItem(0, ItemStack(Material.STONE, 5))
            player.inventory.setItem(1, ItemStack(Material.STONE, 10))
            val gateway = PaperContractInventoryGateway()

            player.isOnline shouldBe true
            player.inventory.getItem(0)!!.type.key.toString() shouldBe "minecraft:stone"

            val prepared = gateway.prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            prepared.payloads.map { it.quantity }.shouldContainExactly(5, 3)

            prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(0) shouldBe null
            player.inventory.getItem(1)?.amount shouldBe 7

            prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(0)?.amount shouldBe 5
            player.inventory.getItem(1)?.amount shouldBe 10
        }
    }

    "proves no mutation when a slot changes before removal" {
        runTest {
            val player = server.addPlayer("ChangingMiner")
            player.inventory.setItem(0, ItemStack(Material.STONE, 8))
            player.isOnline shouldBe true
            player.inventory.getItem(0)!!.type.key.toString() shouldBe "minecraft:stone"
            val prepared =
                PaperContractInventoryGateway()
                    .prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            player.inventory.setItem(0, ItemStack(Material.DIRT, 8))

            prepared.removeExact() shouldBe ContractInventoryMutation.NotPerformed("slot_changed")
            player.inventory.getItem(0)?.type shouldBe Material.DIRT
            player.inventory.getItem(0)?.amount shouldBe 8
        }
    }

    "rejects custom namespaces and metadata-bearing vanilla variants" {
        runTest {
            val player = server.addPlayer("NamedMiner")
            val namedStone = ItemStack(Material.STONE, 8)
            namedStone.itemMeta = namedStone.itemMeta.also { it.displayName(Component.text("Особый камень")) }
            player.inventory.setItem(0, namedStone)
            val gateway = PaperContractInventoryGateway()

            gateway.prepare(player.uniqueId.toString(), "minecraft:stone", 8) shouldBe null
            gateway.prepare(player.uniqueId.toString(), "slimefun:basic_machine", 1) shouldBe null
        }
    }

    "uses RedisEconomy 4_5_12 reason API and returns exact balance evidence" {
        runTest {
            val playerId = java.util.UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.currencyName } returns "vault"
            every { currency.getBalance(playerId) } returnsMany listOf(100.0, 120.0)
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:submission-1") } returns
                EconomyResponse(20.0, 120.0, EconomyResponse.ResponseType.SUCCESS, null)
            val gateway = RedisEconomyContractPaymentGateway { api }

            gateway.balanceMinor(playerId.toString()) shouldBe 10_000L
            gateway.deposit(playerId.toString(), 2_000L, "arc-contract:submission-1") shouldBe
                ContractPaymentEvidence(true, 12_000L)
            verify(exactly = 1) {
                currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:submission-1")
            }
        }
    }

    "fails closed when provider balance cannot be represented in cents" {
        runTest {
            val playerId = java.util.UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.getBalance(playerId) } returns 10.001

            RedisEconomyContractPaymentGateway { api }.balanceMinor(playerId.toString()) shouldBe null
        }
    }

    "withdraws an exact season burn with the RedisEconomy reason API" {
        runTest {
            val playerId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.currencyName } returns "vault"
            every { currency.transactionTax } returns 0.0
            every { currency.getBalance(playerId) } returnsMany listOf(100.0, 90.0)
            every { currency.withdrawPlayer(playerId, "vault", 10.0, "arc-season:dungeon_entry:action-pass-1") } returns
                EconomyResponse(10.0, 90.0, EconomyResponse.ResponseType.SUCCESS, null)

            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-1",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(true, true, 9_000L)
            verify(exactly = 1) {
                currency.withdrawPlayer(playerId, "vault", 10.0, "arc-season:dungeon_entry:action-pass-1")
            }
        }
    }

    "skips the season provider call when tax or balance changed" {
        runTest {
            val playerId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.getBalance(playerId) } returns 99.0
            every { currency.transactionTax } returns 0.0

            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-1",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, 9_900L, "provider_balance_changed_before_call")
            verify(exactly = 0) { currency.withdrawPlayer(any<java.util.UUID>(), any(), any(), any()) }

            every { currency.getBalance(playerId) } returns 100.0
            every { currency.transactionTax } returns 0.01
            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-2",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, 10_000L, "provider_transaction_tax_nonzero")
            verify(exactly = 0) { currency.withdrawPlayer(any<java.util.UUID>(), any(), any(), any()) }
        }
    }

    "proves a skipped season provider call when the API disappears" {
        runTest {
            val gateway = RedisEconomySeasonMoneyGateway { null }

            gateway.withdraw(
                "11111111-1111-1111-1111-111111111111",
                1_000L,
                "arc-season:dungeon_entry:action-pass-3",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, null, "provider_unavailable")
        }
    }
})
