package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DungeonAdventureDataTest : FreeSpec({
    "dynamic levels match the native five-level range at minimum and maximum bounds" {
        dynamicDungeonAvailableLevels(1) shouldBe listOf(5)
        dynamicDungeonAvailableLevels(6) shouldBe listOf(5, 10)
        dynamicDungeonAvailableLevels(195) shouldBe listOf(190, 195, 200)
        dynamicDungeonAvailableLevels(200) shouldBe listOf(195, 200)
        // Native DynamicDungeonBrowser computes min=235 and max=200 here; the loop is empty.
        dynamicDungeonAvailableLevels(240) shouldBe emptyList()
    }

    "dynamic default chooses the nearest native option and breaks ties downward" {
        recommendedDynamicDungeonLevel(listOf(5, 10), 7) shouldBe 5
        recommendedDynamicDungeonLevel(listOf(5, 10), 8) shouldBe 10
        recommendedDynamicDungeonLevel(emptyList(), 40) shouldBe null
    }

    "difficulty selection keeps the stable id separate from the exact native name" {
        nativeDungeonDifficulty(mapOf("id" to "hard_mode", "name" to "Hard: Tier II", "level" to "50")) shouldBe
            DungeonDifficultyInfo("hard_mode", "Hard: Tier II", 50)
        nativeDungeonDifficulty(mapOf("name" to "Default")) shouldBe
            DungeonDifficultyInfo("Default", "Default", null)
        nativeDungeonDifficulty(mapOf("id" to "missing-name")) shouldBe null
    }

    "skill perk toggles check unlock level before the active state, as the native menu does" {
        skillPerkToggleGuard(playerLevel = 9, requiredLevel = 10, isActive = true, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.LEVEL_REQUIRED
    }

    "skill perk toggles allow deactivation at the active limit and reject another activation" {
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = true, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_DEACTIVATED
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = false, activeCount = 3, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_LIMIT
    }

    "skill perk toggles activate an unlocked perk while a slot remains" {
        skillPerkToggleGuard(playerLevel = 10, requiredLevel = 10, isActive = false, activeCount = 2, maxActive = 3) shouldBe
            DungeonAdventureActionResult.PERK_ACTIVATED
    }

    "skill type ids normalize case and reject unknown values" {
        skillTypeFromId(" swords ") shouldBe com.magmaguy.elitemobs.skills.SkillType.SWORDS
        skillTypeFromId("not-a-skill") shouldBe null
    }
})
