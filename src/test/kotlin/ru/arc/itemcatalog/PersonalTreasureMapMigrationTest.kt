package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class PersonalTreasureMapMigrationTest : StringSpec({
    "legacy map keeps its bearer address while resolving the active Survival prize and presentation" {
        MockBukkitTestRuntime.open().use { paper ->
            val poolId = "weekly-map-migration-${UUID.randomUUID()}"
            val pool = TreasurePool(poolId, treasures = listOf(Treasure.Item(ItemStack(Material.DIAMOND), id = "rare_find")))
            var poolLookups = 0
            mockkObject(Treasures)
            every { Treasures.getPool(poolId) } answers {
                poolLookups++
                pool
            }
            val child = RewardCatalogEntry(
                id = "weekly_map_cache",
                name = "Находка из тайника",
                description = emptyList(),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.Treasure(poolId, "rare_find"),
                icon = CatalogIconStyle(Material.DIAMOND.name),
            )
            val map = RewardCatalogEntry(
                id = "weekly_personal_map",
                name = "Карта тайника",
                description = listOf("<#e8dfd2>Тайник на Survival в обычном мире."),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.PersonalMap(
                    "rewards", child.id, PersonalTreasureMapSearchPolicy("survival", "world", 96),
                ),
                icon = CatalogIconStyle(Material.FILLED_MAP.name),
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Награды",
                categories = listOf(
                    RewardCatalogCategory("rewards", "Награды", emptyList(), CatalogIconStyle(Material.CHEST.name), listOf(child, map)),
                ),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val root = Files.createTempDirectory("arc-legacy-map-migration")
            try {
                val archive = FrozenPhysicalRewards(root)
                val rewards = CatalogPhysicalRewards(settings, frozen = archive)
                val currentSnapshots = rewards.captureInteractiveArchives().shouldNotBeNull()
                rewards.persistInteractiveArchives(currentSnapshots) shouldBe true
                val poolLookupsAfterWarmup = poolLookups

                // This is the old archived EliteMobs case recipe and old Spawn preview from already issued maps.
                val oldPrize = archive.prepare(
                    "dungeon-case:loot_case",
                    FrozenPhysicalRecipe(
                        type = "dungeon-case",
                        dungeonCaseId = "loot_case",
                        dungeonCaseDefinition = "legacy-elitemobs-case-recipe",
                    ),
                    ItemStack(Material.PAPER),
                ).shouldNotBeNull()
                val oldDestinations = listOf(
                    PersonalTreasureMapDestination("spawn", "world", 10.0, 64.0, 20.0, "Старый тайник на Спавне"),
                )
                val oldMapPreview = ItemStack(Material.FILLED_MAP).apply {
                    editMeta { meta ->
                        meta.displayName(net.kyori.adventure.text.Component.text("Снаряжение EliteMobs"))
                        meta.lore(listOf(net.kyori.adventure.text.Component.text("Получить на Спавне")))
                    }
                }
                val oldMap = archive.prepare(
                    "personal-map:legacy-weekly-map",
                    FrozenPhysicalRecipe(
                        type = "personal-map",
                        mapId = map.id,
                        mapPrizeKey = oldPrize.sourceKey,
                        mapPrizeFingerprint = oldPrize.providerFingerprint,
                        mapDestinations = oldDestinations,
                    ),
                    oldMapPreview,
                ).shouldNotBeNull()

                val mapSpec = rewards.resolve(oldMap.sourceKey).shouldNotBeNull()
                val mapDefinition = rewards.personalMapDefinition(mapSpec).shouldNotBeNull()
                repeat(3) {
                    rewards.resolve(oldMap.sourceKey).shouldNotBeNull()
                    rewards.personalMapDefinition(mapSpec).shouldNotBeNull()
                }
                poolLookups shouldBe poolLookupsAfterWarmup
                mapSpec.key shouldBe oldMap.sourceKey
                mapSpec.fingerprint.sha256 shouldBe oldMap.providerFingerprint
                mapDefinition.searchPolicy shouldBe PersonalTreasureMapSearchPolicy("survival", "world", 96)
                mapDefinition.fingerprint shouldBe PersonalTreasureMapDefinition.legacyFingerprint(
                    map.id, oldPrize.sourceKey, oldDestinations,
                )
                val voucherId = UUID.randomUUID()
                mapDefinition.destinationIndex(voucherId) shouldBe Math.floorMod(voucherId.hashCode(), oldDestinations.size)

                val plain = PlainTextComponentSerializer.plainText()
                val visibleName = plain.serialize(mapSpec.preview.itemMeta!!.displayName()!!)
                val visibleLore = mapSpec.preview.itemMeta!!.lore().orEmpty().joinToString(" ") { plain.serialize(it) }
                visibleName shouldBe "Карта тайника"
                visibleLore.contains("EliteMobs") shouldBe false
                visibleLore.contains("Спавн") shouldBe false
                visibleLore.contains("Survival") shouldBe true

                val player = paper.addPlayer("legacy-map-owner")
                rewards.canRedeem(player, mapSpec) shouldBe null
                val result = rewards.redeem(player, mapSpec, UUID.randomUUID()).join()
                result shouldBe PhysicalRewardOutcome.Applied
                poolLookups shouldBe poolLookupsAfterWarmup
                player.inventory.contents.any { it?.type == Material.DIAMOND } shouldBe true
            } finally {
                unmockkObject(Treasures)
                root.toFile().deleteRecursively()
            }
        }
    }
})
