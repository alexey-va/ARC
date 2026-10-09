package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.nio.file.Files
import java.util.Comparator

class PersonalTreasureMapCaseMintTest : StringSpec({
    "weekly case map mint rejects the old mixed pool and accepts the item-only cache pool" {
        withPreparedCaseMap(hasUnsupportedLegacyBranch = true) { controller, player, mapEntry, mapKey ->
            val category = healthCategory(controller.healthSnapshot(), "case_weekly")
            (category["unavailable"] as List<*>).contains(mapEntry.id) shouldBe true
            (category["interactive"] as List<*>).filterIsInstance<Map<*, *>>()
                .single { it["id"] == mapEntry.id }["sourceKey"] shouldBe mapKey

            controller.issueCaseReward(player, "case_weekly", mapEntry.id) shouldBe
                CaseRewardIssueResult.PROVIDER_UNAVAILABLE
            player.inventory.storageContents.filterNotNull().isEmpty() shouldBe true
        }

        withPreparedCaseMap(hasUnsupportedLegacyBranch = false) { controller, player, mapEntry, mapKey ->
            val category = healthCategory(controller.healthSnapshot(), "case_weekly")
            (category["unavailable"] as List<*>).contains(mapEntry.id) shouldBe false

            controller.issueCaseReward(player, "case_weekly", mapEntry.id) shouldBe CaseRewardIssueResult.INVENTORY
            val deliveredMap = player.inventory.storageContents.filterNotNull().single()
            deliveredMap.type shouldBe Material.FILLED_MAP
            PhysicalRewardVoucher.identity(deliveredMap)?.key shouldBe mapKey
        }
    }
})

private fun withPreparedCaseMap(
    hasUnsupportedLegacyBranch: Boolean,
    block: (RewardCatalogGuiController, org.bukkit.entity.Player, RewardCatalogEntry, String) -> Unit,
) {
    MockBukkitTestRuntime.open().use { paper ->
        val root = Files.createTempDirectory("arc-personal-map-case-mint")
        val nestedPoolId = if (hasUnsupportedLegacyBranch) "vanilla_rare" else "weekly_map_valuables"
        val rootPool = TreasurePool(
            id = "weekly_map_cache",
            treasures = listOf(Treasure.SubPool(nestedPoolId, id = "rare_find")),
        )
        val nestedTreasures = if (hasUnsupportedLegacyBranch) {
            // Mirrors the previous vanilla_rare pool: eight item outcomes plus an enchant roll,
            // which is not allowed in a three-roll personal-map prize.
            listOf(
                Treasure.Item(ItemStack(Material.DIAMOND), min = 3, max = 8, weight = 14, id = "diamond"),
                Treasure.Item(ItemStack(Material.ANCIENT_DEBRIS), min = 1, max = 3, weight = 9, id = "debris"),
                Treasure.Item(ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), weight = 7, id = "upgrade"),
                Treasure.Item(ItemStack(Material.NETHER_STAR), weight = 4, id = "star"),
                Treasure.Item(ItemStack(Material.TOTEM_OF_UNDYING), weight = 7, id = "totem"),
                Treasure.Item(ItemStack(Material.HEART_OF_THE_SEA), weight = 5, id = "heart"),
                Treasure.Item(ItemStack(Material.HEAVY_CORE), weight = 2, id = "core"),
                Treasure.Item(ItemStack(Material.ENCHANTED_GOLDEN_APPLE), weight = 2, id = "gapple"),
                Treasure.Enchant(min = 2, max = 4, weight = 10, id = "enchant_book"),
            )
        } else {
            // Mirrors the current weekly_map_valuables item-only pool.
            listOf(
                Treasure.Item(ItemStack(Material.DIAMOND), min = 16, max = 24, weight = 3000, id = "diamonds"),
                Treasure.Item(ItemStack(Material.ANCIENT_DEBRIS), min = 4, max = 8, weight = 1800, id = "ancient_debris"),
                Treasure.Item(ItemStack(Material.NETHERITE_INGOT), weight = 500, id = "netherite_ingot"),
                Treasure.Item(ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), weight = 1000, id = "upgrade_template"),
                Treasure.Item(ItemStack(Material.TOTEM_OF_UNDYING), min = 2, max = 2, weight = 1000, id = "totems"),
                Treasure.Item(ItemStack(Material.ENCHANTED_GOLDEN_APPLE), min = 2, max = 2, weight = 800, id = "enchanted_golden_apples"),
                Treasure.Item(ItemStack(Material.SHULKER_BOX), min = 3, max = 3, weight = 1200, id = "shulker_boxes"),
                Treasure.Item(ItemStack(Material.BEACON), weight = 200, id = "beacon"),
                Treasure.Item(ItemStack(Material.HEAVY_CORE), weight = 300, id = "heavy_core"),
                Treasure.Item(ItemStack(Material.ELYTRA), weight = 200, id = "elytra"),
            )
        }
        val nestedPool = TreasurePool(id = nestedPoolId, treasures = nestedTreasures)
        mockkObject(Treasures)
        try {
            every { Treasures.getPool(any()) } answers {
                when (firstArg<String>()) {
                    rootPool.id -> rootPool
                    nestedPool.id -> nestedPool
                    else -> null
                }
            }

            val mapEntry = RewardCatalogEntry(
                id = "weekly_personal_map",
                name = "Личная карта тайника",
                description = emptyList(),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.PersonalMap(
                    rewardCategoryId = "vanilla",
                    rewardEntryId = "weekly_map_cache",
                    searchPolicy = PersonalTreasureMapSearchPolicy(
                        server = "survival",
                        world = "survival",
                        bounds = PersonalTreasureMapBounds(-9650, 9650, -9650, 9650),
                        minDistance = 3000,
                    ),
                    prizeRolls = 3,
                ),
                icon = CatalogIconStyle(Material.FILLED_MAP.name),
                weight = 400,
            )
            val prizeEntry = RewardCatalogEntry(
                id = "weekly_map_cache",
                name = "Находка из личного тайника",
                description = emptyList(),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.Treasure(rootPool.id, "rare_find"),
                icon = CatalogIconStyle(Material.HEART_OF_THE_SEA.name),
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Награды",
                categories = listOf(
                    RewardCatalogCategory(
                        id = "case_weekly",
                        name = "Недельный кейс",
                        description = emptyList(),
                        icon = CatalogIconStyle(Material.CHEST.name),
                        entries = listOf(mapEntry),
                        rolls = 1,
                    ),
                    RewardCatalogCategory(
                        id = "vanilla",
                        name = "Ванильные предметы",
                        description = emptyList(),
                        icon = CatalogIconStyle(Material.CHEST.name),
                        entries = listOf(prizeEntry),
                    ),
                ),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val nativeRewards = CatalogPhysicalRewards(settings, frozen = FrozenPhysicalRewards(root))
            val prepared = requireNotNull(nativeRewards.captureInteractiveArchives())
            nativeRewards.persistInteractiveArchives(prepared) shouldBe true
            val mapMaterialization = requireNotNull(nativeRewards.materialization(mapEntry))
            val mapKey = mapMaterialization.sourceKey
            val ledger = mockk<OneTimeUseLedger>()
            every { ledger.available } returns true
            val vouchers = PhysicalRewardController(
                paper.createSimplePlugin("MapCaseMint"),
                ledger,
                nativeRewards::resolve,
                nativeRewards::canRedeem,
                nativeRewards::redeem,
                "spawn",
            )
            val createPhysical: (String) -> ItemStack? = { key ->
                if (nativeRewards.canMaterialize(key)) vouchers.createStack(key) else null
            }
            fun currentMaterialization(entry: RewardCatalogEntry) = nativeRewards.materialization(entry)
            fun canMaterialize(entry: RewardCatalogEntry) =
                entry.source !is RewardCatalogSource.PersonalMap ||
                    currentMaterialization(entry)?.let { nativeRewards.canMaterialize(it.sourceKey) } == true

            val controller = RewardCatalogGuiController(
                settings = settings,
                givePermission = "arc.test",
                physicalPreview = { entry ->
                    currentMaterialization(entry)?.sourceKey?.let(vouchers::previewStack)
                },
                physicalCreate = { entry -> currentMaterialization(entry)?.let { createPhysical(it.sourceKey) } },
                physicalSource = nativeRewards::isVoucherSource,
                physicalMaterialization = ::currentMaterialization,
                physicalCreateKey = createPhysical,
                physicalCanMaterialize = ::canMaterialize,
            )
            block(controller, paper.addPlayer("map-case-mint"), mapEntry, mapKey)
        } finally {
            unmockkObject(Treasures)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private fun healthCategory(snapshot: Map<String, Any?>, categoryId: String): Map<*, *> =
    (snapshot["categories"] as List<*>).filterIsInstance<Map<*, *>>().single { it["id"] == categoryId }
