package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.mounts.MountMoneyEvidence
import ru.arc.mounts.MountWallet
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.treasure.core.TreasurePool
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.nio.file.Files
import java.util.Comparator

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

    "collection seals archive all choices with an inert preview and reject partial snapshots" {
        MockBukkitTestRuntime.open().use {
            val poolId = "catalog-seal-${UUID.randomUUID()}"
            val diamond = Treasure.Item(ItemStack(Material.DIAMOND), id = "diamond")
            val emerald = Treasure.Item(ItemStack(Material.EMERALD), id = "emerald")
            val pool = TreasurePool(poolId, treasures = listOf(diamond, emerald))
            mockkObject(Treasures)
            every { Treasures.getPool(poolId) } returns pool
            val targetEntries = listOf(
                RewardCatalogEntry("diamond", null, emptyList(), null, emptyList(), RewardCatalogSource.Treasure(poolId, diamond.id), null),
                RewardCatalogEntry("emerald", null, emptyList(), null, emptyList(), RewardCatalogSource.Treasure(poolId, emerald.id), null),
            )
            val target = RewardCatalogCategory(
                "set_frost", "Морозный комплект", emptyList(), CatalogIconStyle(Material.PAPER.name), targetEntries,
            )
            val seal = RewardCatalogEntry(
                "seal", "Полный комплект", emptyList(), null, emptyList(), RewardCatalogSource.Seal(target.id), CatalogIconStyle(Material.PAPER.name),
            )
            fun settings(collection: RewardCatalogCategory) = RewardCatalogSettings(
                enabled = true,
                title = "Каталог",
                categories = listOf(collection, RewardCatalogCategory("case", "Кейс", emptyList(), CatalogIconStyle(Material.CHEST.name), listOf(seal), rolls = 1)),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val root = Files.createTempDirectory("arc-catalog-seal")
            try {
                val archive = FrozenPhysicalRewards(root)
                val service = CatalogPhysicalRewards(
                    settings(target),
                    frozen = archive,
                    sealStack = { categoryId, _ -> CollectionSealIdentity.mark(ItemStack(Material.PAPER), categoryId) },
                )
                service.isVoucherSource(seal) shouldBe true
                service.key(seal) shouldBe "seal:set_frost"
                val materialization = requireNotNull(service.materialization(seal))
                val record = requireNotNull(archive.find("frozen:${materialization.providerFingerprint}"))
                record.recipe.sealItems?.size shouldBe 2
                CollectionSealIdentity.categoryId(archive.preview(record)) shouldBe null
                val archivedCategory = materialization.sourceKey
                archivedCategory shouldBe requireNotNull(archive.archivedCategoryId(record.key))
                val sealStack = requireNotNull(service.createSealStack(archivedCategory))
                CollectionSealIdentity.categoryId(sealStack) shouldBe archivedCategory
                service.archivedSeal(archivedCategory)?.choices?.size shouldBe 2
                val emptySettings = settings(target).copy(categories = emptyList())
                val reloaded = CatalogPhysicalRewards(emptySettings, frozen = FrozenPhysicalRewards(root))
                val controller = RewardCatalogGuiController(
                    emptySettings, "arc.test",
                    physicalCreateKey = reloaded::createSealStack,
                )
                val reference = ru.arc.paper.api.ArcItemMaterializationReference.FreshVoucher(
                    ru.arc.paper.api.ArcItemMaterializationRequest("case", "seal"),
                    materialization.providerFingerprint, archivedCategory,
                )
                val minted = requireNotNull(controller.materializeCaseReward(reference)).single()
                CollectionSealIdentity.categoryId(minted) shouldBe archivedCategory
                controller.materializeCaseReward(reference.copy(providerFingerprint = "wrong")) shouldBe null
                reloaded.archivedSeal(archivedCategory)?.choices?.map { it.type } shouldBe listOf(Material.DIAMOND, Material.EMERALD)

                val unavailable = target.copy(
                    entries = targetEntries + RewardCatalogEntry(
                        "missing", null, emptyList(), null, emptyList(), RewardCatalogSource.Treasure(poolId, "missing"), null,
                    ),
                )
                CatalogPhysicalRewards(
                    settings(unavailable),
                    frozen = FrozenPhysicalRewards(root),
                    sealStack = { categoryId, _ -> CollectionSealIdentity.mark(ItemStack(Material.PAPER), categoryId) },
                ).materialization(seal) shouldBe null
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                unmockkObject(Treasures)
            }
        }
    }
})
