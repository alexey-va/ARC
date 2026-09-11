package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RewardCatalogSettingsCaseTest : StringSpec({
    "only one-roll case entries can be selected for external issuance" {
        val entry = RewardCatalogEntry(
            id = "reward",
            name = null,
            description = emptyList(),
            rarity = null,
            requires = emptyList(),
            source = RewardCatalogSource.Planned("future"),
            icon = null,
            weight = 1,
        )
        val case = RewardCatalogCategory(
            id = "case_daily",
            name = "Daily",
            description = emptyList(),
            icon = CatalogIconStyle("CHEST"),
            entries = listOf(entry),
            rolls = 1,
        )
        val ordinary = case.copy(id = "catalogue", rolls = null)
        val multiRoll = case.copy(id = "bundle", rolls = 3)
        val settings = RewardCatalogSettings(true, "Rewards", listOf(case, ordinary, multiRoll), RewardCatalogMessages.DEFAULT)

        settings.caseEntry("case_daily", "reward") shouldBe (case to entry)
        settings.caseEntry("catalogue", "reward") shouldBe null
        settings.caseEntry("bundle", "reward") shouldBe null
        settings.caseEntry("case_daily", "missing") shouldBe null
    }
})
