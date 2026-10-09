package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

private typealias ContentType = ContentPackagesConfigFields.ContentType

class DungeonQuestScopeTest : FreeSpec({
    "Primis meta package groups main and side worlds in both directions" {
        val packages = listOf(
            scopePackage(
                "primis_meta_pack.yml",
                ContentType.META_PACKAGE,
                children = listOf(
                    "primis_adventure.yml",
                    "primis_blood_temple_sanctum.yml",
                    "primis_gladius_invasion_dungeon.yml",
                ),
            ),
            scopePackage(
                "primis_adventure.yml",
                ContentType.OPEN_DUNGEON,
                world = "em_primis",
                wormhole = "em_primis_wormhole",
            ),
            scopePackage("primis_blood_temple_sanctum.yml", ContentType.INSTANCED_DUNGEON, "em_id_bloodtemple"),
            scopePackage("primis_gladius_invasion_dungeon.yml", ContentType.INSTANCED_DUNGEON, "em_id_primis_gladius"),
            scopePackage("oasis_adventure.yml", ContentType.OPEN_DUNGEON, "em_oasis"),
        )
        val primisWorlds = setOf("em_primis", "em_primis_wormhole", "em_id_bloodtemple", "em_id_primis_gladius")

        dungeonQuestWorldNames("em_primis", "primis_adventure.yml", packages) shouldBe primisWorlds
        dungeonQuestWorldNames("primis-copy-42", "primis_blood_temple_sanctum.yml", packages) shouldBe
            primisWorlds + "primis-copy-42"
    }

    "catalog metas do not merge standalone dungeons and cyclic nested adventure graphs terminate" {
        val packages = listOf(
            scopePackage("free_story_mode_dungeon_meta_pack.yml", ContentType.META_PACKAGE,
                children = listOf("the_climb_dungeon.yml", "the_cave_sanctum.yml")),
            scopePackage("the_climb_dungeon.yml", ContentType.DYNAMIC_DUNGEON, "em_id_the_climb"),
            scopePackage("the_cave_sanctum.yml", ContentType.DYNAMIC_DUNGEON, "em_id_the_cave"),
            scopePackage("free_enchantment_challenges.yml", ContentType.META_PACKAGE,
                children = listOf("enchantment_challenge_1.yml", "enchantment_challenge_2.yml")),
            scopePackage("enchantment_challenge_1.yml", ContentType.INSTANCED_DUNGEON, "em_id_challenge_1"),
            scopePackage("enchantment_challenge_2.yml", ContentType.INSTANCED_DUNGEON, "em_id_challenge_2"),
            scopePackage("adventure_meta.yml", ContentType.META_PACKAGE,
                children = listOf("adventure_open.yml", "adventure_sides_meta.yml")),
            scopePackage("adventure_open.yml", ContentType.OPEN_DUNGEON, "adventure_world"),
            scopePackage("adventure_sides_meta.yml", ContentType.META_PACKAGE,
                children = listOf("adventure_side.yml", "adventure_meta.yml")),
            scopePackage("adventure_side.yml", ContentType.INSTANCED_DUNGEON, "adventure_side_blueprint"),
        )

        dungeonQuestWorldNames("em_id_the_climb", "the_climb_dungeon.yml", packages) shouldBe
            setOf("em_id_the_climb")
        dungeonQuestWorldNames("em_id_challenge_1", "enchantment_challenge_1.yml", packages) shouldBe
            setOf("em_id_challenge_1")
        dungeonQuestWorldNames("adventure-copy", "adventure_side.yml", packages) shouldBe
            setOf("adventure-copy", "adventure_world", "adventure_side_blueprint")
    }

    "unmapped packages and blank native package fields fall back to the live world" {
        dungeonQuestWorldNames("custom-world", "missing.yml", emptyList()) shouldBe setOf("custom-world")
        dungeonQuestWorldNames(
            "custom-world",
            "",
            listOf(scopePackage("", null, world = "", wormhole = "")),
        ) shouldBe setOf("custom-world")
        dungeonQuestWorldNames(null, null, emptyList()) shouldBe emptySet()

        mockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        try {
            val currentWorld = mockk<World> {
                every { uid } returns UUID.randomUUID()
                every { name } returns "custom-world"
            }
            val player = mockk<Player> { every { world } returns currentWorld }
            val nullFields = mockk<ContentPackagesConfigFields> {
                every { filename } returns "null-fields.yml"
                every { contentType } returns null
                every { worldName } returns null
                every { wormholeWorldName } returns null
                every { containedPackages } returns null
            }
            every { ContentPackagesConfig.getDungeonPackages() } returns hashMapOf("null-fields.yml" to nullFields)
            every { DungeonInstance.getDungeonInstances() } returns emptySet()

            currentDungeonQuestWorlds(player) shouldBe setOf("custom-world")
        } finally {
            unmockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        }
    }

    "current instanced world UID selects only that copy among several dungeon instances" {
        mockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        try {
            val currentWorldId = UUID.randomUUID()
            val otherCopyWorldId = UUID.randomUUID()
            val currentWorld = mockk<World> {
                every { uid } returns currentWorldId
                every { name } returns "primis-copy-current"
            }
            val otherCopyWorld = mockk<World> {
                every { uid } returns otherCopyWorldId
                every { name } returns "primis-copy-other-party"
            }
            val player = mockk<Player> { every { world } returns currentWorld }
            val primisMeta = nativePackageFields(
                "primis_meta_pack.yml", ContentType.META_PACKAGE,
                children = listOf("primis_adventure.yml", "primis_blood_temple_sanctum.yml", "primis_gladius_invasion_dungeon.yml"),
            )
            val primisOpen = nativePackageFields(
                "primis_adventure.yml", ContentType.OPEN_DUNGEON,
                world = "em_primis", wormhole = "em_primis_wormhole",
            )
            val primisBloodTemple = nativePackageFields(
                "primis_blood_temple_sanctum.yml", ContentType.INSTANCED_DUNGEON, world = "em_id_bloodtemple",
            )
            val primisGladius = nativePackageFields(
                "primis_gladius_invasion_dungeon.yml", ContentType.INSTANCED_DUNGEON,
                world = "em_id_primis_gladius",
            )
            val currentInstance = dungeonInstance(currentWorld, primisBloodTemple)
            val otherCopy = dungeonInstance(otherCopyWorld, primisBloodTemple)
            every { ContentPackagesConfig.getDungeonPackages() } returns hashMapOf(
                "primis_meta_pack.yml" to primisMeta,
                "primis_adventure.yml" to primisOpen,
                "primis_blood_temple_sanctum.yml" to primisBloodTemple,
                "primis_gladius_invasion_dungeon.yml" to primisGladius,
            )
            every { DungeonInstance.getDungeonInstances() } returns setOf(currentInstance, otherCopy)

            currentDungeonQuestWorlds(player) shouldBe setOf(
                "primis-copy-current",
                "em_primis",
                "em_primis_wormhole",
                "em_id_bloodtemple",
                "em_id_primis_gladius",
            )
        } finally {
            unmockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        }
    }

    "wormhole world resolves by exact native alias after main world matching" {
        mockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        try {
            val wormholeWorld = mockk<World> {
                every { uid } returns UUID.randomUUID()
                every { name } returns "em_primis_wormhole"
            }
            val player = mockk<Player> { every { world } returns wormholeWorld }
            val primisMeta = nativePackageFields(
                "primis_meta_pack.yml", ContentType.META_PACKAGE,
                children = listOf("primis_adventure.yml", "primis_blood_temple_sanctum.yml"),
            )
            val primisOpen = nativePackageFields(
                "primis_adventure.yml", ContentType.OPEN_DUNGEON,
                world = "em_primis", wormhole = "em_primis_wormhole",
            )
            val primisSide = nativePackageFields(
                "primis_blood_temple_sanctum.yml", ContentType.INSTANCED_DUNGEON,
                world = "em_id_bloodtemple",
            )
            every { ContentPackagesConfig.getDungeonPackages() } returns hashMapOf(
                "primis_meta_pack.yml" to primisMeta,
                "primis_adventure.yml" to primisOpen,
                "primis_blood_temple_sanctum.yml" to primisSide,
            )
            every { DungeonInstance.getDungeonInstances() } returns emptySet()

            currentDungeonQuestWorlds(player) shouldBe setOf(
                "em_primis_wormhole", "em_primis", "em_id_bloodtemple",
            )
        } finally {
            unmockkStatic(ContentPackagesConfig::class, DungeonInstance::class)
        }
    }
})

private fun scopePackage(
    filename: String,
    contentType: ContentType?,
    world: String? = null,
    wormhole: String? = null,
    children: List<String> = emptyList(),
) = DungeonQuestScopePackage(filename, contentType, world, wormhole, children)

private fun nativePackageFields(
    packageFilename: String,
    packageType: ContentType,
    world: String? = null,
    wormhole: String? = null,
    children: List<String> = emptyList(),
): ContentPackagesConfigFields = mockk {
    every { filename } returns packageFilename
    every { contentType } returns packageType
    every { worldName } returns world
    every { wormholeWorldName } returns wormhole
    every { containedPackages } returns children
}

private fun dungeonInstance(instanceWorld: World, packageFields: ContentPackagesConfigFields): DungeonInstance = mockk {
    every { world } returns instanceWorld
    every { contentPackagesConfigFields } returns packageFields
}
