package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.arc.mounts.MountMoneyEvidence
import ru.arc.mounts.MountWallet
import ru.arc.treasure.core.Treasure
import java.util.UUID

class CatalogPhysicalRewardsTest : StringSpec({
    "token cheques bind the exact configured currency and nominal" {
        CatalogPhysicalRewards.tokenAmount(Treasure.Command(listOf("rediseconomy:balance %player% tokens give 3 arc-lootbox-catalog"))) shouldBe 3L
        CatalogPhysicalRewards.tokenAmount(Treasure.Command(listOf("rediseconomy:balance %player% vault give 3 arc-lootbox-catalog"))) shouldBe null
        CatalogPhysicalRewards.tokenAmount(Treasure.Command(listOf("rediseconomy:balance %player% tokens give 3 arc-lootbox-catalog", "say extra"))) shouldBe null
    }

    "token redemption credits only tokens and requires a matching receipt" {
        val playerId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val player = mockk<Player>()
        every { player.uniqueId } returns playerId
        val wallet = mockk<MountWallet>()
        val tokens = mockk<MountWallet>()
        every { wallet.walletForCurrency("tokens") } returns tokens
        every { tokens.available } returns true
        every { tokens.balanceMinor(playerId) } returns 700L
        every { tokens.deposit(playerId, 300L, "arc-reward:$operationId", 700L) } returns MountMoneyEvidence(true, true, 1000L)
        val service = CatalogPhysicalRewards(RewardCatalogSettings(false, "test", emptyList(), RewardCatalogMessages.DEFAULT), wallet)
        service.deposit(player, "tokens", 3.0, operationId) shouldBe PhysicalRewardOutcome.Applied
        verify(exactly = 0) { wallet.deposit(any(), any(), any(), any()) }
        verify(exactly = 1) { tokens.deposit(playerId, 300L, "arc-reward:$operationId", 700L) }

        every { tokens.deposit(playerId, 300L, "arc-reward:$operationId", 700L) } returns MountMoneyEvidence(null, true, null, "provider_threw")
        (service.deposit(player, "tokens", 3.0, operationId) is PhysicalRewardOutcome.Uncertain) shouldBe true

        every { tokens.deposit(playerId, 300L, "arc-reward:$operationId", 700L) } returns MountMoneyEvidence(false, false, 700L, "provider_unavailable")
        (service.deposit(player, "tokens", 3.0, operationId) is PhysicalRewardOutcome.Rejected) shouldBe true
    }
})
