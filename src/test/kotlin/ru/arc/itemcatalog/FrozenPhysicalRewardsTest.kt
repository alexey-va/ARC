package ru.arc.itemcatalog

import com.google.gson.Gson
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
    "a shared lookup fetches the exact choice root and every referenced child before owner-thread import" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-shared-archive-graph")
            try {
                val archive = FrozenPhysicalRewards(root)
                val children = (0..2).map { index ->
                    archive.capture(
                        "mount:shared_$index",
                        FrozenPhysicalRecipe(type = "mount", mountId = "shared_$index"),
                        ItemStack(Material.PAPER),
                    )!!.record
                }
                val parent = archive.capture(
                    "choice:shared-parent",
                    FrozenPhysicalRecipe(
                        type = "choice",
                        choiceOptions = children.mapIndexed { index, child ->
                            FrozenChoiceOption(
                                id = "choice_$index",
                                name = "Choice $index",
                                description = emptyList(),
                                childKey = child.key,
                                childFingerprint = child.fingerprint,
                            )
                        },
                    ),
                    ItemStack(Material.PAPER),
                )!!.record
                val payloads = (children + parent).associate { record ->
                    record.fingerprint to Gson().toJson(record).toByteArray(Charsets.UTF_8)
                }
                val shared = InMemoryPhysicalRewardArchive(payloads)

                val found = archive.fetchReachable(parent.fingerprint, shared).join()
                    .let { it as FrozenPhysicalArchiveLookup.Found }
                found.payloads.size shouldBe 4
                archive.find(parent.key) shouldBe null
                archive.importShared(found.payloads) shouldBe true
                archive.find(parent.key)?.recipe?.choiceOptions?.size shouldBe 3
                children.forEach { archive.find(it.key)?.fingerprint shouldBe it.fingerprint }

                val missingChild = children.last()
                val missing = FrozenPhysicalRewards(root.resolve("other"))
                    .fetchReachable(parent.fingerprint, InMemoryPhysicalRewardArchive(payloads - missingChild.fingerprint))
                    .join()
                missing shouldBe FrozenPhysicalArchiveLookup.Missing(missingChild.fingerprint)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    "reachable recipe graphs accept exactly 64 records and reject 65 like the cold reader" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-shared-archive-bounds")
            try {
                val archive = FrozenPhysicalRewards(root)
                val records = linkedMapOf<String, FrozenPhysicalRewardRecord>()
                fun mount(name: String): FrozenPhysicalRewardRecord = archive.capture(
                    "mount:$name",
                    FrozenPhysicalRecipe(type = "mount", mountId = name),
                    ItemStack(Material.PAPER),
                )!!.record.also { records[it.fingerprint] = it }

                fun choice(name: String, children: List<FrozenPhysicalRewardRecord>): FrozenPhysicalRewardRecord {
                    return archive.capture(
                        "choice:$name",
                        FrozenPhysicalRecipe(
                            type = "choice",
                            choiceOptions = children.mapIndexed { index, child ->
                                FrozenChoiceOption(
                                    id = "choice_$index",
                                    name = "Choice $index",
                                    description = emptyList(),
                                    childKey = child.key,
                                    childFingerprint = child.fingerprint,
                                )
                            },
                        ),
                        ItemStack(Material.PAPER),
                    )!!.record.also { records[it.fingerprint] = it }
                }

                fun root(prefix: String, nestedLeafCount: Int): FrozenPhysicalRewardRecord {
                    val directLeaves = (0 until 31).map { mount("$prefix-direct-$it") }
                    val nestedLeaves = (0 until nestedLeafCount).map { mount("$prefix-nested-$it") }
                    val nested = choice("$prefix-nested-choice", nestedLeaves)
                    return choice("$prefix-root", directLeaves + nested)
                }

                val root64 = root("exact64", nestedLeafCount = 31)
                archive.reachableGraph(root64.fingerprint, records).size shouldBe 64

                val root65 = root("over65", nestedLeafCount = 32)
                val failure = runCatching { archive.reachableGraph(root65.fingerprint, records) }.exceptionOrNull()
                (failure as FrozenPhysicalRewardGraphException).reason shouldBe "node_limit"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    "a previously seen child beyond the depth limit still matches cold lookup rejection" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-shared-archive-depth")
            try {
                val archive = FrozenPhysicalRewards(root)
                val records = linkedMapOf<String, FrozenPhysicalRewardRecord>()
                fun mount(name: String) = archive.capture(
                    "mount:$name",
                    FrozenPhysicalRecipe(type = "mount", mountId = name),
                    ItemStack(Material.PAPER),
                )!!.record.also { records[it.fingerprint] = it }
                fun choice(name: String, children: List<FrozenPhysicalRewardRecord>) = archive.capture(
                    "choice:$name",
                    FrozenPhysicalRecipe(
                        type = "choice",
                        choiceOptions = children.mapIndexed { index, child ->
                            FrozenChoiceOption(
                                id = "choice_$index",
                                name = "Choice $index",
                                description = emptyList(),
                                childKey = child.key,
                                childFingerprint = child.fingerprint,
                            )
                        },
                    ),
                    ItemStack(Material.PAPER),
                )!!.record.also { records[it.fingerprint] = it }

                val alreadySeen = mount("already-seen")
                var descendant: FrozenPhysicalRewardRecord = choice(
                    "depth-16",
                    listOf(alreadySeen, mount("depth-16-a"), mount("depth-16-b")),
                )
                for (depth in 15 downTo 1) {
                    descendant = choice(
                        "depth-$depth",
                        listOf(descendant, mount("depth-$depth-a"), mount("depth-$depth-b")),
                    )
                }
                val rootRecord = choice(
                    "depth-root",
                    listOf(alreadySeen, descendant, mount("depth-root-side")),
                )

                val failure = runCatching { archive.reachableGraph(rootRecord.fingerprint, records) }.exceptionOrNull()
                (failure as FrozenPhysicalRewardGraphException).reason shouldBe "depth_limit"
                val payloads = records.mapValues { (_, record) ->
                    Gson().toJson(record).toByteArray(Charsets.UTF_8)
                }
                val coldFailure = runCatching {
                    archive.fetchReachable(rootRecord.fingerprint, InMemoryPhysicalRewardArchive(payloads)).join()
                }.exceptionOrNull()
                requireNotNull(coldFailure).cause?.message shouldBe "Frozen physical reward graph is too deep"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    "frozen AE random-book filters survive JSON reload while legacy nodes default open" {
        val gson = Gson()
        val restricted = FrozenTreasureNode(
            id = "special-book",
            type = "ae",
            weight = 5,
            aeKind = "random_book",
            amount = 1,
            aeGroup = "UNIQUE",
            aeMaxLevel = 1,
        )
        restricted.validate(0, emptySet())
        val restored = gson.fromJson(gson.toJson(restricted), FrozenTreasureNode::class.java)
        restored shouldBe restricted
        restored.validate(0, emptySet())

        val legacy = gson.fromJson(
            """{"id":"legacy-book","type":"ae","weight":1,"aeKind":"random_book","amount":1}""",
            FrozenTreasureNode::class.java,
        )
        legacy.aeGroup shouldBe null
        legacy.aeMaxLevel shouldBe null
        legacy.validate(0, emptySet())

        runCatching { restricted.copy(aeGroup = "UNKNOWN").validate(0, emptySet()) }.isFailure shouldBe true
        runCatching { restricted.copy(aeMaxLevel = 0).validate(0, emptySet()) }.isFailure shouldBe true
        runCatching { restricted.copy(aeKind = "item", itemName = "magic").validate(0, emptySet()) }.isFailure shouldBe true
    }

    "particle preset recipes are strict while legacy archives default the new field to null" {
        FrozenPhysicalRecipe(
            type = "particle-preset",
            particlePresetId = "arc_raincloud",
        ).validate()

        val legacy = Gson().fromJson(
            """{"type":"money","currency":"vault","minAmount":1.0,"maxAmount":1.0}""",
            FrozenPhysicalRecipe::class.java,
        )
        legacy.particlePresetId shouldBe null
        legacy.validate()
        Gson().toJson(legacy).contains("particlePresetId") shouldBe false

        runCatching {
            FrozenPhysicalRecipe("particle-preset", particlePresetId = "arc_unapproved").validate()
        }.isFailure shouldBe true
        runCatching {
            FrozenPhysicalRecipe("money", currency = "vault", minAmount = 1.0, maxAmount = 1.0, particlePresetId = "arc_angel").validate()
        }.isFailure shouldBe true
        runCatching {
            FrozenPhysicalRecipe("particle-preset", particlePresetId = "arc_angel", tokenAmount = 1).validate()
        }.isFailure shouldBe true
    }

    "travel-anchor recipes bind a bounded personal-anchor amount" {
        FrozenPhysicalRecipe(
            type = "travel-anchors",
            travelAnchorAmount = 2,
        ).validate()
        runCatching {
            FrozenPhysicalRecipe(
                type = "travel-anchors",
                travelAnchorAmount = 0,
            ).validate()
        }.isFailure shouldBe true
        runCatching {
            FrozenPhysicalRecipe(
                type = "money",
                currency = "vault",
                minAmount = 1.0,
                maxAmount = 1.0,
                travelAnchorAmount = 2,
            ).validate()
        }.isFailure shouldBe true
    }

    "new furniture-roll archives retain weighted native leaves while legacy shulker recipes remain valid" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-furniture-rolls")
            try {
                val itemA = ItemStack(Material.DIAMOND)
                val itemB = ItemStack(Material.EMERALD)
                val recipe = FrozenPhysicalRecipe(
                    type = "furniture-rolls",
                    furnitureMinRolls = 8,
                    furnitureMaxRolls = 12,
                    treasure = FrozenTreasureNode(
                        id = "forge",
                        type = "sub-pool",
                        weight = 1,
                        poolId = "furniture-package:forge",
                        children = listOf(
                            FrozenTreasureNode(
                                id = "forge:chair",
                                type = "item",
                                weight = 4,
                                minInt = 1,
                                maxInt = 1,
                                stack = java.util.Base64.getEncoder().encodeToString(itemA.serializeAsBytes()),
                                requiresItemsAdder = true,
                            ),
                            FrozenTreasureNode(
                                id = "forge:table",
                                type = "item",
                                weight = 1,
                                minInt = 1,
                                maxInt = 1,
                                stack = java.util.Base64.getEncoder().encodeToString(itemB.serializeAsBytes()),
                                requiresItemsAdder = true,
                            ),
                        ),
                    ),
                )
                recipe.validate()
                val archive = FrozenPhysicalRewards(root)
                val prepared = checkNotNull(archive.prepare("package:forge", recipe, ItemStack(Material.PAPER)))
                FrozenPhysicalRewards(root).find(prepared.sourceKey)?.recipe shouldBe recipe

                runCatching {
                    recipe.copy(furnitureMaxRolls = 13).validate()
                }.isFailure shouldBe true
                runCatching {
                    recipe.copy(treasure = recipe.treasure!!.copy(children = listOf(
                        FrozenTreasureNode("money", "money", 1, minDouble = 1.0, maxDouble = 1.0),
                    ))).validate()
                }.isFailure shouldBe true
                runCatching {
                    recipe.copy(treasure = recipe.treasure!!.copy(children = listOf(
                        recipe.treasure!!.children!!.first().copy(requiresItemsAdder = false),
                    ))).validate()
                }.isFailure shouldBe true

                FrozenPhysicalRecipe(
                    type = "furniture",
                    furnitureBoxes = listOf(
                        java.util.Base64.getEncoder().encodeToString(ItemStack(Material.PURPLE_SHULKER_BOX).serializeAsBytes()),
                    ),
                ).validate()
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    "map roll overrides stay transient and cannot enter the frozen archive" {
        MockBukkitTestRuntime.open().use {
            val recipe = FrozenPhysicalRecipe(
                type = "treasure",
                treasure = FrozenTreasureNode(
                    id = "single_item",
                    type = "item",
                    weight = 1,
                    minInt = 1,
                    maxInt = 1,
                    stack = java.util.Base64.getEncoder().encodeToString(ItemStack(Material.DIAMOND).serializeAsBytes()),
                ),
            )
            Gson().toJson(recipe).contains("treasureRolls") shouldBe false
            recipe.copy(treasureRolls = 3).let { selected ->
                runCatching { selected.validate() }.isFailure shouldBe true
            }
        }
    }

    "choice archive snapshots stay unpublished until asynchronous child and parent persistence" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-frozen-choice")
            try {
                val archive = FrozenPhysicalRewards(root)
                val child = archive.capture(
                    "travel-anchors:2",
                    FrozenPhysicalRecipe(type = "travel-anchors", travelAnchorAmount = 2),
                    ItemStack(Material.DIAMOND),
                )!!
                val option = FrozenChoiceOption(
                    id = "adventure",
                    name = "Путешествие",
                    description = listOf("Два личных якоря."),
                    childKey = child.materialization.sourceKey,
                    childFingerprint = child.materialization.providerFingerprint,
                )
                val parent = archive.capture(
                    "choice:weekly",
                    FrozenPhysicalRecipe(type = "choice", choiceOptions = listOf(option, option.copy(id = "building", childKey = "frozen:${"b".repeat(64)}", childFingerprint = "b".repeat(64)), option.copy(id = "work", childKey = "frozen:${"c".repeat(64)}", childFingerprint = "c".repeat(64)))),
                    ItemStack(Material.PAPER),
                )!!

                archive.find(child.materialization.sourceKey) shouldBe null
                archive.find(parent.materialization.sourceKey) shouldBe null
                CompletableFuture.supplyAsync { archive.persist(child) }.get() shouldBe child.materialization
                CompletableFuture.supplyAsync { archive.persist(parent) }.get() shouldBe parent.materialization

                val reloaded = FrozenPhysicalRewards(root)
                val stored = reloaded.find(parent.materialization.sourceKey)!!
                stored.recipe.choiceOptions?.map { it.id } shouldBe listOf("adventure", "building", "work")
                stored.recipe.choiceOptions?.first()?.childKey shouldBe child.materialization.sourceKey
            } finally {
                deleteTree(root)
            }
        }

        runCatching {
            FrozenPhysicalRecipe(type = "choice", choiceOptions = emptyList()).validate()
        }.isFailure shouldBe true
    }

    "storage validation checks archive structure without deserializing Bukkit stacks" {
        MockBukkitTestRuntime.open().use {
            val recipe = FrozenPhysicalRecipe(
                type = "furniture",
                furnitureBoxes = listOf(java.util.Base64.getEncoder().encodeToString(byteArrayOf(0))),
            )
            val sourceKey = "furniture:structural-check"
            val previewBytes = ItemStack(Material.DIAMOND).serializeAsBytes()
            val preview = java.util.Base64.getEncoder().encodeToString(previewBytes)
            val fingerprint = OneTimeUseFingerprint.sha256(
                "arc-frozen-physical-v1\n$sourceKey\n${Gson().toJson(recipe)}\n${OneTimeUseFingerprint.sha256(previewBytes).sha256}"
                    .toByteArray(Charsets.UTF_8),
            ).sha256
            val record = FrozenPhysicalRewardRecord(
                version = 1,
                key = "frozen:$fingerprint",
                sourceKey = sourceKey,
                fingerprint = fingerprint,
                preview = preview,
                recipe = recipe,
            )

            record.validate(validateBukkitStacks = false)
            runCatching { record.validate() }.isFailure shouldBe true
        }
    }

    "dungeon case recipes bind a bounded case id and exact generator definition" {
        FrozenPhysicalRecipe(
            type = "dungeon-case",
            dungeonCaseId = "loot_case",
            dungeonCaseDefinition = "dungeon-case-v1:test",
        ).validate()
        runCatching {
            FrozenPhysicalRecipe(
                type = "dungeon-case",
                dungeonCaseId = "bad:id",
                dungeonCaseDefinition = "dungeon-case-v1:test",
            ).validate()
        }.isFailure shouldBe true
        runCatching {
            FrozenPhysicalRecipe(
                type = "money",
                currency = "vault",
                minAmount = 1.0,
                maxAmount = 1.0,
                dungeonCaseId = "loot_case",
            ).validate()
        }.isFailure shouldBe true
    }

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

private class InMemoryPhysicalRewardArchive(
    private val records: Map<String, ByteArray>,
) : SharedPhysicalRewardArchiveRepository {
    override fun publishBatch(records: List<FrozenPhysicalRewardRecord>) =
        CompletableFuture.completedFuture(Unit)

    override fun fetch(fingerprint: String): CompletableFuture<ByteArray?> =
        CompletableFuture.completedFuture(records[fingerprint])

    override fun status() = SharedPhysicalRewardArchiveStatus(ready = true)
    override fun close() = Unit
}
