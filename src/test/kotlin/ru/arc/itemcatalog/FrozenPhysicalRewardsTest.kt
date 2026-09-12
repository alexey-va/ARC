package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CompletableFuture

class FrozenPhysicalRewardsTest : StringSpec({
    "money furniture and weighted treasure recipes survive archive reload and catalogue removal" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-rewards")
            try {
                val preview = ItemStack(Material.DIAMOND)
                val furnitureBox = ItemStack(Material.PURPLE_SHULKER_BOX)
                val recipes = listOf(
                    FrozenPhysicalRecipe(
                        type = "treasure",
                        treasure = FrozenTreasureNode("money", "money", 1, minDouble = 7.0, maxDouble = 11.0),
                    ),
                    FrozenPhysicalRecipe(
                        type = "furniture",
                        furnitureBoxes = listOf(encode(furnitureBox)),
                    ),
                    FrozenPhysicalRecipe(
                        type = "treasure",
                        treasure = FrozenTreasureNode(
                            id = "pool-root",
                            type = "sub-pool",
                            weight = 1,
                            poolId = "season-pool",
                            children = listOf(
                                FrozenTreasureNode("money", "money", 1, minDouble = 2.0, maxDouble = 2.0),
                                FrozenTreasureNode("diamond", "item", 3, minInt = 2, maxInt = 4, stack = encode(ItemStack(Material.DIAMOND))),
                            ),
                        ),
                    ),
                )
                val archive = FrozenPhysicalRewards(root)
                val first = recipes.mapIndexed { index, recipe ->
                    archive.prepare("treasure:season:$index", recipe, preview)!!
                }

                val reloaded = FrozenPhysicalRewards(root)
                first.forEachIndexed { index, materialization ->
                    val record = requireNotNull(reloaded.find(materialization.sourceKey))
                    record.recipe shouldBe recipes[index]
                    reloaded.preview(record)?.type shouldBe Material.DIAMOND
                    CatalogPhysicalRewards(
                        RewardCatalogSettings(false, "removed", emptyList(), RewardCatalogMessages.DEFAULT),
                        frozen = reloaded,
                    ).resolve(materialization.sourceKey)?.fingerprint?.sha256 shouldBe materialization.providerFingerprint
                }
            } finally {
                deleteTree(root)
            }
        }
    }

    "slimefun treasure archives the exact provider stack for later redemption" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-slimefun")
            try {
                val providerStack = ItemStack(Material.DIAMOND, 3)
                val recipe = FrozenPhysicalRecipe(
                    type = "treasure",
                    treasure = FrozenTreasureNode(
                        id = "slimefun-leaf",
                        type = "slimefun",
                        weight = 1,
                        minInt = 2,
                        maxInt = 5,
                        itemId = "LEGACY_ITEM",
                        stack = encode(providerStack),
                    ),
                )
                val archive = FrozenPhysicalRewards(root)
                val materialization = archive.prepare("treasure:slimefun", recipe, ItemStack(Material.PAPER))!!
                val reloaded = FrozenPhysicalRewards(root)
                val archived = requireNotNull(reloaded.find(materialization.sourceKey))
                val restored = ItemStack.deserializeBytes(
                    java.util.Base64.getDecoder().decode(requireNotNull(archived.recipe.treasure).stack),
                )

                restored shouldBe providerStack
                // A pre-stack archive cannot claim immutable Slimefun semantics.
                reloaded.prepare(
                    "treasure:slimefun-legacy",
                    recipe.copy(treasure = recipe.treasure!!.copy(stack = null)),
                    ItemStack(Material.PAPER),
                ) shouldBe null
            } finally {
                deleteTree(root)
            }
        }
    }

    "collection seal snapshots preserve every detached member and reject bearers" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-seal")
            try {
                val members = listOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD))
                val recipe = FrozenPhysicalRecipe(
                    type = "seal",
                    sealItems = members.map(::encode),
                    sealName = "Морозный комплект",
                    sealDescription = listOf("Снимок полного состава на момент выдачи."),
                )
                val markedPreview = CollectionSealIdentity.mark(ItemStack(Material.PAPER), "set_frost")
                val inertPreview = markedPreview.clone().also { stack ->
                    stack.editMeta { meta ->
                        meta.persistentDataContainer.remove(CollectionSealIdentity.key)
                        meta.persistentDataContainer.remove(CollectionSealIdentity.versionKey)
                    }
                }
                val archive = FrozenPhysicalRewards(root)
                val materialization = archive.prepare("seal:set_frost", recipe, inertPreview)!!

                val reloaded = FrozenPhysicalRewards(root)
                val record = requireNotNull(reloaded.find(materialization.sourceKey))
                record.recipe shouldBe recipe
                record.recipe.sealItems!!.map { decode(it) } shouldBe members
                CollectionSealIdentity.categoryId(requireNotNull(reloaded.preview(record))) shouldBe null
                val archived = requireNotNull(reloaded.archivedSeal("set_frozen_${materialization.providerFingerprint}"))
                archived.choices shouldBe members
                archived.name shouldBe "Морозный комплект"

                val bearer = CollectionSealIdentity.mark(ItemStack(Material.DIAMOND), "set_frost")
                reloaded.prepare(
                    "seal:set_frost-bearer",
                    recipe.copy(sealItems = listOf(encode(bearer))),
                    inertPreview,
                ) shouldBe null
            } finally {
                deleteTree(root)
            }
        }
    }

    "malformed and cyclic definitions fail closed during preparation and reload" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-invalid")
            try {
                val archive = FrozenPhysicalRewards(root)
                val cyclic = FrozenPhysicalRecipe(
                    type = "treasure",
                    treasure = FrozenTreasureNode(
                        "outer",
                        "sub-pool",
                        1,
                        poolId = "same",
                        children = listOf(
                            FrozenTreasureNode(
                                "inner",
                                "sub-pool",
                                1,
                                poolId = "same",
                                children = listOf(FrozenTreasureNode("money", "money", 1, minDouble = 1.0, maxDouble = 1.0)),
                            ),
                        ),
                    ),
                )
                archive.prepare("treasure:cycle", cyclic, ItemStack(Material.PAPER)) shouldBe null
                archive.prepare(
                    "treasure:command",
                    FrozenPhysicalRecipe(
                        "command",
                        commandKind = "arcbuilder",
                        commandValue = "arcbuilder:builder systembook %player% unsafe;give @a diamond",
                    ),
                    ItemStack(Material.PAPER),
                ) shouldBe null

                val valid = archive.prepare(
                    "treasure:valid",
                    FrozenPhysicalRecipe("treasure", treasure = FrozenTreasureNode("money", "money", 1, minDouble = 1.0, maxDouble = 1.0)),
                    ItemStack(Material.PAPER),
                )!!
                val unaffected = archive.prepare(
                    "treasure:unaffected",
                    FrozenPhysicalRecipe("treasure", treasure = FrozenTreasureNode("money", "money", 1, minDouble = 2.0, maxDouble = 2.0)),
                    ItemStack(Material.PAPER),
                )!!
                val recordPath = root.resolve("data/reward-physical-archive/${valid.providerFingerprint}.json")
                Files.writeString(recordPath, "{}")
                val reloaded = FrozenPhysicalRewards(root)
                reloaded.find(valid.sourceKey) shouldBe null
                reloaded.find(unaffected.sourceKey) shouldNotBe null
            } finally {
                deleteTree(root)
            }
        }
    }

    "the same archived spec mints a fresh UUID for every voucher" {
        MockBukkitTestRuntime.open().use { paper ->
            val root = Files.createTempDirectory("arc-frozen-uuid")
            try {
                val archive = FrozenPhysicalRewards(root)
                val materialization = archive.prepare(
                    "treasure:uuid",
                    FrozenPhysicalRecipe("treasure", treasure = FrozenTreasureNode("money", "money", 1, minDouble = 3.0, maxDouble = 3.0)),
                    ItemStack(Material.GOLD_INGOT),
                )!!
                val ledger = mockk<OneTimeUseLedger>()
                every { ledger.available } returns true
                val plugin = paper.createSimplePlugin("FrozenPhysicalRewardController")
                val controller = PhysicalRewardController(
                    plugin = plugin,
                    ledger = ledger,
                    resolve = { key ->
                        archive.find(key)?.let { record ->
                            PhysicalRewardSpec(record.key, OneTimeUseFingerprint.parse(record.fingerprint), archive.preview(record)!!)
                        }
                    },
                    redeem = { _, _, _ -> CompletableFuture.completedFuture(PhysicalRewardOutcome.Applied) },
                    scope = "arc.catalog-reward",
                )
                try {
                    val first = controller.createStack(materialization.sourceKey)!!
                    val second = controller.createStack(materialization.sourceKey)!!
                    val firstIdentity = PhysicalRewardVoucher.identity(first)!!
                    val secondIdentity = PhysicalRewardVoucher.identity(second)!!
                    firstIdentity.key shouldBe secondIdentity.key
                    firstIdentity.fingerprint shouldBe secondIdentity.fingerprint
                    firstIdentity.id shouldNotBe secondIdentity.id
                } finally {
                    controller.close()
                }
            } finally {
                deleteTree(root)
            }
        }
    }
})

private fun encode(stack: ItemStack): String =
    java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes())

private fun decode(encoded: String): ItemStack =
    ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(encoded))

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
