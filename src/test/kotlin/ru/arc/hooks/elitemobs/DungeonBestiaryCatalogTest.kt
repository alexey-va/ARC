package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DungeonBestiaryCatalogTest : FreeSpec({
    "unknown powers do not get a category placeholder and known defenses have concrete effects" {
        bestiaryPowerFact("Защитная способность", "Защитная способность EliteMobs.") shouldBe null
        bestiaryPowerFact("Щитовая стена", "Способность EliteMobs.") shouldBe null

        NativeDungeonBestiaryCatalog.knownPowerFact("invulnerability_arrow.yml") shouldBe
            BestiaryFact("Защита от снарядов", "Не получает урон от снарядов.")
        NativeDungeonBestiaryCatalog.knownPowerFact("invulnerability_fire.yml") shouldBe
            BestiaryFact("Огнестойкость", "Постоянно действует эффект Огнестойкости II.")
        NativeDungeonBestiaryCatalog.knownPowerFact("invulnerability_fall_damage.yml") shouldBe
            BestiaryFact("Защита от падения", "Отменяет урон от падения.")
        NativeDungeonBestiaryCatalog.knownPowerFact("invulnerability_knockback.yml") shouldBe
            BestiaryFact("Защита от отбрасывания", "Сбрасывает полученный от удара импульс отбрасывания.")
        NativeDungeonBestiaryCatalog.knownPowerFact("invulnerability_fireworks.yml") shouldBe
            BestiaryFact("Защита от фейерверков", "Отменяет урон от фейерверков.")
        NativeDungeonBestiaryCatalog.knownPowerFact("shield_wall.lua") shouldBe
            BestiaryFact("Щитовая стена", "С шансом 10% поднимает щитовую стену, блокирующую атаки с трёх сторон.")
    }

    "generated item names describe the material and expand encounter placeholders without rolling loot" {
        bestiaryItemName("Default name", "Булава", "Химико") shouldBe "Булава"
        bestiaryItemName("&a\$boss: \$item", "Булава", "Химико") shouldBe "Химико: Булава"
    }

    "phase files resolve to their only phase-one ancestor" {
        bestiaryPhaseCanonicalRoots(mapOf(
            "crypt_keeper.yml" to listOf("crypt_keeper_p2.yml"),
            "crypt_keeper_p2.yml" to listOf("crypt_keeper_p3.yml"),
            "bone_matriarch.yml" to emptyList(),
        )) shouldBe mapOf(
            "crypt_keeper_p2.yml" to "crypt_keeper.yml",
            "crypt_keeper_p3.yml" to "crypt_keeper.yml",
        )
    }

    "shared phases and cyclic phase references are left unaliased" {
        bestiaryPhaseCanonicalRoots(mapOf(
            "first.yml" to listOf("shared.yml"),
            "second.yml" to listOf("shared.yml"),
            "cycle_a.yml" to listOf("cycle_b.yml"),
            "cycle_b.yml" to listOf("cycle_a.yml"),
        )) shouldBe emptyMap()
    }

    "loot probability matches EliteMobs' independent and nested rolls" {
        bestiaryEffectiveLootChance("table", .25, false) shouldBe .25
        bestiaryEffectiveLootChance("command", .25, false) shouldBe .1875
        bestiaryEffectiveLootChance("vanilla", .25, true) shouldBe .0625
        bestiaryEffectiveLootChance("vanilla", .9, true, 3) shouldBe .8991
        bestiaryEffectiveLootChance("vanilla", .8, true, 2) shouldBe .768
        bestiaryEffectiveLootChance("vanilla", .25, false) shouldBe .25
        bestiaryEffectiveLootChance("command", Double.NaN, false).isNaN() shouldBe true
    }
})
