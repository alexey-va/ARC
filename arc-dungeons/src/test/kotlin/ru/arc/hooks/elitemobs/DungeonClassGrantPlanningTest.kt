package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.skills.SkillType
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.maps.shouldContainExactly

class DungeonClassGrantPlanningTest : FreeSpec({
    "deep class grant raises only the prerequisites used by its lineage" {
        val catalog = BuiltInClassContent.catalog()

        requiredDungeonClassSkillLevels(catalog.progressionPathOf("bulwark")) shouldContainExactly mapOf(
            SkillType.ARMOR to 90,
            SkillType.SWORDS to 60,
            SkillType.SPEARS to 30,
            SkillType.MACES to 90,
        )
    }
})
