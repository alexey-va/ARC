package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Base64
import java.nio.file.Files

class PersonalTreasureMapRecipeTest : StringSpec({
    "map parser freezes global bounded expeditions and rejects invalid prize/search contracts" {
        val root = Files.createTempDirectory("arc-map-config")
        try {
            fun load(document: String): RewardCatalogSettings {
                Files.createDirectories(root.resolve("modules"))
                Files.writeString(root.resolve("modules/reward-catalog.yml"), document.trimIndent())
                ru.arc.config.ConfigManager.ofModule(root, "reward-catalog.yml").load()
                return RewardCatalogModuleConfig.load(root).snapshot()
            }
            fun legacyDocument(prize: String = "loot", radius: String = "96", rewardExtra: String = "") = """
                    enabled: true
                    categories:
                      rewards:
                        entries:
                          loot:
                            treasure: {pool: weekly_map_cache, id: rare_find}
                          map:
                            personal-map:
                              reward: {category: rewards, entry: $prize$rewardExtra}
                              search: {server: survival, world: world, radius: $radius}
                """
            val source = load(legacyDocument()).categories.single().entries.last().source as RewardCatalogSource.PersonalMap
            source.rewardCategoryId shouldBe "rewards"
            source.rewardEntryId shouldBe "loot"
            source.searchPolicy shouldBe PersonalTreasureMapSearchPolicy("survival", "world", 96)
            source.prizeRolls shouldBe 1
            source.searchPolicy.targetPolicyFingerprint shouldBe OneTimeUseFingerprint.sha256Fields(
                "personal-treasure-map-target-policy-v1", "survival", "world", "96", "", "", "", "", "16",
            )

            val expedition = load(
                """
                    enabled: true
                    categories:
                      rewards:
                        entries:
                          loot:
                            treasure: {pool: weekly_map_cache, id: rare_find}
                          map:
                            personal-map:
                              reward: {category: rewards, entry: loot, rolls: 3}
                              search:
                                server: survival
                                world: survival
                                additional-worlds: [vanilla]
                                bounds: {min-x: -9650, max-x: 9650, min-z: -9650, max-z: 9650}
                                min-distance: 3000
                """,
            ).categories.single().entries.last().source as RewardCatalogSource.PersonalMap
            expedition.searchPolicy shouldBe PersonalTreasureMapSearchPolicy(
                "survival", "survival", bounds = PersonalTreasureMapBounds(-9650, 9650, -9650, 9650),
                minDistance = 3000, additionalWorlds = setOf("vanilla"),
            )
            expedition.searchPolicy.acceptsWorld("survival") shouldBe true
            expedition.searchPolicy.acceptsWorld("vanilla") shouldBe true
            expedition.searchPolicy.acceptsWorld("world") shouldBe false
            PersonalTreasureMapSearchPolicy(
                "survival", "survival", bounds = PersonalTreasureMapBounds(-9650, 9650, -9650, 9650),
                minDistance = 3000, additionalWorlds = linkedSetOf("vanilla", "event"),
            ).targetPolicyFingerprint shouldBe PersonalTreasureMapSearchPolicy(
                "survival", "survival", bounds = PersonalTreasureMapBounds(-9650, 9650, -9650, 9650),
                minDistance = 3000, additionalWorlds = linkedSetOf("event", "vanilla"),
            ).targetPolicyFingerprint
            expedition.prizeRolls shouldBe 3

            runCatching { load(legacyDocument(prize = "map")) }.isFailure shouldBe true
            runCatching { load(legacyDocument(prize = "missing")) }.isFailure shouldBe true
            runCatching { load(legacyDocument(radius = "999")) }.isFailure shouldBe true
            runCatching { load(legacyDocument(rewardExtra = ", command: op")) }.isFailure shouldBe true
            runCatching {
                load(
                    """
                        enabled: true
                        categories:
                          rewards:
                            entries:
                              loot: {treasure: {pool: weekly_map_cache, id: rare_find}}
                              map:
                                personal-map:
                                  reward: {category: rewards, entry: loot}
                                  search: {server: survival, world: survival, radius: 96, additional-worlds: [survival]}
                    """,
                )
            }.isFailure shouldBe true
            runCatching {
                load(
                    """
                        enabled: true
                        categories:
                          rewards:
                            entries:
                              loot: {treasure: {pool: weekly_map_cache, id: rare_find}}
                              map:
                                personal-map:
                                  reward: {category: rewards, entry: loot}
                                  search: {server: survival, world: survival, radius: 96, additional-worlds: [vanilla, vanilla]}
                    """,
                )
            }.isFailure shouldBe true
            runCatching {
                load(
                    """
                        enabled: true
                        categories:
                          rewards:
                            entries:
                              loot: {treasure: {pool: weekly_map_cache, id: rare_find}}
                              map:
                                personal-map:
                                  reward: {category: rewards, entry: loot, rolls: 9}
                                  search: {server: survival, world: survival, bounds: {min-x: -9650, max-x: 9650, min-z: -9650, max-z: 9650}, min-distance: 3000}
                    """,
                )
            }.isFailure shouldBe true
            runCatching {
                load(
                    """
                        enabled: true
                        categories:
                          rewards:
                            entries:
                              loot: {treasure: {pool: weekly_map_cache, id: rare_find}}
                              map:
                                personal-map:
                                  reward: {category: rewards, entry: loot}
                                  search: {server: survival, world: survival, radius: 96, bounds: {min-x: -9650, max-x: 9650, min-z: -9650, max-z: 9650}, min-distance: 3000}
                    """,
                )
            }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "archived map keeps its dynamic policy and prize after reload independently of live catalog" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-map-archive")
            try {
                val archive = FrozenPhysicalRewards(root)
                val prizeStack = ItemStack(Material.DIAMOND)
                val prize = requireNotNull(archive.prepare(
                    "treasure:weekly-map-prize",
                    FrozenPhysicalRecipe(
                        type = "treasure",
                        treasure = FrozenTreasureNode(
                            id = "rare_find",
                            type = "item",
                            weight = 1,
                            minInt = 1,
                            maxInt = 1,
                            stack = Base64.getEncoder().encodeToString(prizeStack.serializeAsBytes()),
                        ),
                    ),
                    prizeStack,
                ))
                val recipe = FrozenPhysicalRecipe(
                    type = "personal-map",
                    mapId = "weekly_personal_map",
                    mapPrizeKey = prize.sourceKey,
                    mapPrizeFingerprint = prize.providerFingerprint,
                    mapPrizeRolls = 3,
                    mapSearchServer = "survival",
                    mapSearchWorld = "survival",
                    mapSearchMinX = -9650,
                    mapSearchMaxX = 9650,
                    mapSearchMinZ = -9650,
                    mapSearchMaxZ = 9650,
                    mapSearchMinDistance = 3000,
                    mapSearchAdditionalWorlds = listOf("vanilla"),
                )
                val captured = requireNotNull(archive.capture("personal-map:weekly_personal_map", recipe, ItemStack(Material.FILLED_MAP)))
                archive.find(captured.materialization.sourceKey) shouldBe null
                Files.exists(root.resolve("data/reward-physical-archive/${captured.record.fingerprint}.json")) shouldBe false
                val prepared = requireNotNull(archive.persist(captured))
                val restored = requireNotNull(FrozenPhysicalRewards(root).find(prepared.sourceKey))
                restored.recipe shouldBe recipe
                restored.recipe.mapPrizeKey shouldBe prize.sourceKey
                restored.recipe.mapPrizeRolls shouldBe 3
                restored.recipe.mapSearchWorld shouldBe "survival"
                restored.recipe.mapSearchMinX shouldBe -9650
                restored.recipe.mapSearchMaxZ shouldBe 9650
                restored.recipe.mapSearchMinDistance shouldBe 3000
                restored.recipe.mapSearchAdditionalWorlds shouldBe listOf("vanilla")
                restored.fingerprint shouldBe prepared.providerFingerprint
                recipe.copy(mapSearchAdditionalWorlds = null).validate()
                runCatching { recipe.copy(mapSearchAdditionalWorlds = listOf("vanilla", "vanilla")).validate() }
                    .isFailure shouldBe true
                runCatching { recipe.copy(mapPrizeKey = "frozen:" + "0".repeat(64)).validate() }.isFailure shouldBe true
                runCatching { recipe.copy(type = "particle-preset", particlePresetId = "arc_rainbow").validate() }.isFailure shouldBe true
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
})
