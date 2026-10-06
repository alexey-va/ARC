package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class PersonalTreasureMapRecipeTest : StringSpec({
    "map parser freezes authored destinations and rejects invalid or recursive prizes" {
        val root = Files.createTempDirectory("arc-map-config")
        try {
            fun load(prize: String = "loot", x: String = "10.5", extra: String = ""): RewardCatalogSettings {
                val document = """
                    enabled: true
                    categories:
                      rewards:
                        entries:
                          loot:
                            dungeon-case: loot_case
                          map:
                            personal-map:
                              reward: {category: rewards, entry: $prize}
                              destinations:
                                - {server: spawn, world: rc_origin_spawn, x: $x, y: 72, z: -20.5, hint: 'Ищи отметку на карте'$extra}
                """.trimIndent()
                Files.createDirectories(root.resolve("modules"))
                Files.writeString(root.resolve("modules/reward-catalog.yml"), document)
                ru.arc.config.ConfigManager.ofModule(root, "reward-catalog.yml").load()
                return RewardCatalogModuleConfig.load(root).snapshot()
            }
            val source = load().categories.single().entries.last().source as RewardCatalogSource.PersonalMap
            source.rewardCategoryId shouldBe "rewards"
            source.rewardEntryId shouldBe "loot"
            source.destinations.single().x shouldBe 10.5
            runCatching { load(prize = "map") }.isFailure shouldBe true
            runCatching { load(prize = "missing") }.isFailure shouldBe true
            runCatching { load(x = ".nan") }.isFailure shouldBe true
            runCatching { load(extra = ", command: op") }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "archived map keeps its destination and prize after reload independently of live catalog" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-map-archive")
            try {
                val archive = FrozenPhysicalRewards(root)
                val prize = requireNotNull(archive.prepare(
                    "particle-preset:arc_rainbow",
                    FrozenPhysicalRecipe(type = "particle-preset", particlePresetId = "arc_rainbow"),
                    ItemStack(Material.PAPER),
                ))
                val recipe = FrozenPhysicalRecipe(
                    type = "personal-map",
                    mapId = "weekly_personal_map",
                    mapPrizeKey = prize.sourceKey,
                    mapPrizeFingerprint = prize.providerFingerprint,
                    mapDestinations = listOf(PersonalTreasureMapDestination("spawn", "rc_origin_spawn", 10.5, 72.0, -20.5, "Тайник")),
                )
                val captured = requireNotNull(archive.capture("personal-map:weekly_personal_map", recipe, ItemStack(Material.FILLED_MAP)))
                archive.find(captured.materialization.sourceKey) shouldBe null
                Files.exists(root.resolve("data/reward-physical-archive/${captured.record.fingerprint}.json")) shouldBe false
                val prepared = requireNotNull(archive.persist(captured))
                val restored = requireNotNull(FrozenPhysicalRewards(root).find(prepared.sourceKey))
                restored.recipe shouldBe recipe
                restored.recipe.mapPrizeKey shouldBe prize.sourceKey
                restored.fingerprint shouldBe prepared.providerFingerprint
                runCatching { recipe.copy(mapPrizeKey = "frozen:" + "0".repeat(64)).validate() }.isFailure shouldBe true
                runCatching { recipe.copy(type = "particle-preset", particlePresetId = "arc_rainbow").validate() }.isFailure shouldBe true
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
})
