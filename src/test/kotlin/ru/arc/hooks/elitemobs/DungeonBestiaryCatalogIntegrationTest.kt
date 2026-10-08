package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.ItemSettingsConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.items.customloottable.CommandLootTable
import com.magmaguy.elitemobs.items.customloottable.CustomLootEntry
import com.magmaguy.elitemobs.items.customloottable.CustomLootTable
import com.magmaguy.elitemobs.items.customloottable.VanillaCustomLootEntry
import com.magmaguy.elitemobs.mobconstructor.BossType
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Material
import java.io.File

class DungeonBestiaryCatalogIntegrationTest : FreeSpec({
    "loaded registries build a dungeon roster from folder ownership, phases, and summons" {
        mockkStatic(ContentPackagesConfig::class, CustomBossesConfig::class, ItemSettingsConfig::class)
        NativeDungeonBestiaryCatalog.invalidate()
        try {
            val content = contentFields("crypt_dungeon.yml", "crypt")
            val dungeons = hashMapOf("crypt_dungeon.yml" to content)
            val challenges = hashMapOf<String, ContentPackagesConfigFields>()
            val bosses = hashMapOf<String, CustomBossesConfigFields>()
            val keeper = bossFields(
                filename = "crypt_keeper.yml",
                folder = "crypt",
                name = "Crypt Keeper",
                bossType = BossType.BOSS,
                phases = listOf("crypt_keeper_p2.yml:0.5"),
                powers = listOf(mapOf(
                    "summonType" to "ONCE",
                    "filename" to "crypt_summon.yml",
                )),
                loot = lootTable(
                    commandEntry(.25),
                    vanillaEntry(.9, amount = 3),
                ),
            )
            val phaseTwo = bossFields(
                filename = "crypt_keeper_p2.yml",
                folder = "crypt",
                name = "Crypt Keeper Phase Two",
                bossType = BossType.BOSS,
                loot = lootTable(commandEntry(.5)),
            )
            val summon = bossFields(
                filename = "crypt_summon.yml",
                folder = "reinforcements",
                name = "Cryptling",
                bossType = BossType.REINFORCEMENT,
                reinforcement = true,
            )
            val unrelated = bossFields(
                filename = "unrelated.yml",
                folder = "other_dungeon",
                name = "Other Dungeon Mob",
                spawnLocations = listOf("other_world,1,64,1"),
            )
            bosses[keeper.filename] = keeper
            bosses[phaseTwo.filename] = phaseTwo
            bosses[summon.filename] = summon
            bosses[unrelated.filename] = unrelated

            every { ContentPackagesConfig.getDungeonPackages() } returns dungeons
            every { ContentPackagesConfig.getEnchantedChallengeDungeonPackages() } returns challenges
            every { CustomBossesConfig.getCustomBosses() } returns bosses
            every { CustomBossesConfig.getCustomBoss(any()) } answers { checkNotNull(bosses[firstArg<String>()]) }
            every { ItemSettingsConfig.isPutLootDirectlyIntoPlayerInventory() } returns true

            val withExtension = NativeDungeonBestiaryCatalog.entries("crypt_dungeon.yml")
            val withoutExtension = NativeDungeonBestiaryCatalog.entries("crypt_dungeon")
            withExtension.map { it.id } shouldContainExactlyInAnyOrder withoutExtension.map { it.id }
            withExtension.map { it.id }.toSet() shouldBe setOf("crypt_keeper.yml", "crypt_summon.yml")
            withExtension.map { it.id } shouldNotContain "crypt_keeper_p2.yml"
            withExtension.map { it.id } shouldNotContain "unrelated.yml"
            NativeDungeonBestiaryCatalog.contains("unrelated.yml") shouldBe false
            NativeDungeonBestiaryCatalog.discoveryId("crypt_keeper_p2.yml") shouldBe "crypt_keeper.yml"

            val keeperEntry = withExtension.single { it.id == "crypt_keeper.yml" }
            keeperEntry.name shouldBe "Crypt Keeper"
            keeperEntry.abilities.map { it.name } shouldContain "Призыв: Cryptling"
            keeperEntry.loot.single { it.name == "Фаза: Crypt Keeper — Серверная награда" }.description shouldContain "18,75%"
            keeperEntry.loot.single { it.name == "Фаза: Crypt Keeper — Печенье" }.description shouldContain "89,91%"
            keeperEntry.loot.map { it.name } shouldContain "Фаза: Crypt Keeper — Серверная награда"
            keeperEntry.loot.map { it.name } shouldContain "Фаза: Crypt Keeper Phase Two — Серверная награда"

            // Replacing one boss in the existing native map keeps its size and identity unchanged.
            // Reading the same cached package again must still use the replacement's current fields.
            bosses[keeper.filename] = bossFields(
                filename = keeper.filename,
                folder = "crypt",
                name = "Renamed Crypt Keeper",
                bossType = BossType.BOSS,
                phases = listOf("crypt_keeper_p2.yml:0.5"),
                powers = listOf(mapOf("summonType" to "ONCE", "filename" to "crypt_summon.yml")),
            )
            NativeDungeonBestiaryCatalog.entries("crypt_dungeon").single { it.id == "crypt_keeper.yml" }.name shouldBe
                "Renamed Crypt Keeper"
        } finally {
            NativeDungeonBestiaryCatalog.invalidate()
            unmockkStatic(ContentPackagesConfig::class, CustomBossesConfig::class, ItemSettingsConfig::class)
        }
    }
})

private fun contentFields(filename: String, folder: String): ContentPackagesConfigFields =
    mockk<ContentPackagesConfigFields>().also { content ->
        every { content.filename } returns filename
        every { content.contentType } returns ContentPackagesConfigFields.ContentType.DYNAMIC_DUNGEON
        every { content.dungeonConfigFolderName } returns folder
        every { content.worldName } returns "crypt_world"
        every { content.wormholeWorldName } returns ""
    }

private fun bossFields(
    filename: String,
    folder: String,
    name: String,
    bossType: BossType = BossType.NORMAL,
    reinforcement: Boolean = false,
    phases: List<String> = emptyList(),
    powers: List<Any> = emptyList(),
    spawnLocations: List<String> = emptyList(),
    loot: CustomLootTable? = null,
): CustomBossesConfigFields = mockk<CustomBossesConfigFields>().also { fields ->
    every { fields.filename } returns filename
    every { fields.file } returns File("/plugins/EliteMobs/custombosses/$folder/$filename")
    every { fields.name } returns name
    every { fields.level } returns 0
    every { fields.bossType } returns bossType
    every { fields.isReinforcement } returns reinforcement
    every { fields.spawnLocations } returns spawnLocations
    every { fields.phases } returns phases
    every { fields.mountedEntity } returns null
    every { fields.powers } returns powers
    every { fields.rawEliteScripts } returns null
    every { fields.eliteScript } returns emptyList()
    every { fields.customLootTable } returns loot
    every { fields.isDropsEliteMobsLoot() } returns false
    every { fields.isDropsVanillaLoot() } returns false
    every { fields.isDropsRandomLoot() } returns false
    every { fields.isClassLoot() } returns false
}

private fun lootTable(vararg entries: CustomLootEntry): CustomLootTable = mockk<CustomLootTable>().also { table ->
    every { table.entries } returns entries.toMutableList()
}

private fun commandEntry(chance: Double): CommandLootTable = mockk<CommandLootTable>().also { entry ->
    every { entry.chance } returns chance
    every { entry.amount } returns 1
    every { entry.wave } returns -1
    every { entry.permission } returns ""
}

private fun vanillaEntry(chance: Double, amount: Int): VanillaCustomLootEntry =
    mockk<VanillaCustomLootEntry>().also { entry ->
        every { entry.chance } returns chance
        every { entry.amount } returns amount
        every { entry.wave } returns -1
        every { entry.permission } returns ""
        every { entry.material } returns Material.COOKIE
    }
