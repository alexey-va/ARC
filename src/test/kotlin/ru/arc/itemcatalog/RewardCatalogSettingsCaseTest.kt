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

    "retired case outcomes stay addressable but are excluded from selection and coverage" {
        val retired = RewardCatalogEntry(
            id = "retired",
            name = "Old outcome",
            description = emptyList(),
            rarity = null,
            requires = emptyList(),
            source = RewardCatalogSource.TravelAnchors(1),
            icon = null,
            weight = 0,
        )
        val active = retired.copy(id = "active", source = RewardCatalogSource.TravelAnchors(2), weight = 5)
        val case = RewardCatalogCategory(
            id = "case_daily",
            name = "Daily",
            description = emptyList(),
            icon = CatalogIconStyle("CHEST"),
            entries = listOf(retired, active),
            rolls = 1,
        )
        val catalogueReward = retired.copy(id = "old_outcome", weight = null)
        val catalogue = RewardCatalogCategory(
            id = "catalogue",
            name = "Catalogue",
            description = emptyList(),
            icon = CatalogIconStyle("CHEST"),
            entries = listOf(catalogueReward),
        )
        val settings = RewardCatalogSettings(true, "Rewards", listOf(case, catalogue), RewardCatalogMessages.DEFAULT)

        settings.caseEntry("case_daily", "retired") shouldBe (case to retired)
        case.selectableCaseEntries.map { it.id } shouldBe listOf("active")
        case.visibleEntries.map { it.id } shouldBe listOf("active")
        case.chance(retired) shouldBe null
        case.chance(active) shouldBe "100%"
        settings.uncoveredRewards() shouldBe listOf("catalogue/old_outcome")
    }
})
