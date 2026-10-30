package ru.arc.helpcenter

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.itemcatalog.RewardCatalogSource
import ru.arc.ops.BundleDefinition
import ru.arc.ops.BundleEntry
import ru.arc.ops.ItemDefinition
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool

class HelpCenterEnchantmentExternalSourcesTest : StringSpec({
    "crate chance retains the full native denominator and exclusive alternate reward quantities" {
        val rewards = listOf(
            reward("crate", "case", "Кейс", 0.25, "ae_lootbox", 1),
            reward("crate", "case", "Кейс", 0.50, "ae_lootbox", 3),
            reward("crate", "case", "Кейс", 0.25, "unrelated", 1),
        )
        val source = HelpCenterEnchantmentExternalSources.summarize("ae_lootbox", rewards).facts.single()
        source.key shouldBe "crate-source"
        source.values["chance"] shouldBe "75"
        source.values["amount"] shouldBe "1–3"
        source.values["crate"] shouldBe "Кейс"
    }

    "crate and nested native pool probabilities multiply for direct AE consumables" {
        val rewards = listOf(HelpCenterExternalEnchantmentReward("crate", "case", "Кейс", 0.2,
            listOf(HelpCenterEnchantmentRewardOutcome("magic", 0.5, 4)),
        ))
        val report = HelpCenterEnchantmentExternalSources.summarize("magic", rewards)
        report.facts.single().values["chance"] shouldBe "10"
        report.facts.single().values["amount"] shouldBe "4"
    }

    "daily quest candidates report completion quantities without inventing assignment odds" {
        val rewards = listOf(reward("quest", "daily_enchant", "Усилить книгу", 1.0, "enchant_supply", 2))
        val source = HelpCenterEnchantmentExternalSources.summarize("enchant_supply", rewards).facts.single()
        source.key shouldBe "quest-source"
        source.values["quest"] shouldBe "Усилить книгу"
        source.values["amount"] shouldBe "2"
        source.values.containsKey("chance") shouldBe false
    }

    "overview links only actual AE boxes and missing providers remain explicit" {
        val rewards = listOf(
            reward("crate", "case", "Кейс", 0.5, "ae_lootbox_master", 1),
            reward("crate", "case", "Кейс", 0.5, "unrelated", 1),
        )
        val report = HelpCenterEnchantmentExternalSources.summarize("overview", rewards, listOf("rank-quests"))
        report.relatedTopics shouldBe listOf("ae_lootbox_master")
        report.facts.single { it.key == "unavailable" }.values["source"] shouldBe "rank-quests"
    }

    "catalogue references use the exact current treasure rather than its pool draw weight" {
        fun snapshot(amount: Int) = HelpCenterEnchantmentSourceSnapshot(
            listOf(TreasurePool("ae", listOf(
                Treasure.Ae(AeKind.ITEM, "magic", amount = amount, weight = 1, id = "dust"),
                Treasure.Money(weight = 99),
            ))), emptyList(), null,
        )
        val source = RewardCatalogSource.Treasure("ae", "dust")
        HelpCenterEnchantmentSources.rewardOutcomes(source, snapshot(4)) shouldBe listOf(HelpCenterEnchantmentRewardOutcome("magic", 1.0, 4))
        HelpCenterEnchantmentSources.rewardOutcomes(source, snapshot(2)) shouldBe listOf(HelpCenterEnchantmentRewardOutcome("magic", 1.0, 2))
    }

    "fixed bundle quantities are not multiplied by the externally requested scaled amount" {
        val item = ItemDefinition("ae_lootbox", null, JsonParser.parseString("""{"material":"CHEST"}""").asJsonObject)
        val bundle = BundleDefinition("bundle", null, listOf(BundleEntry("ae_lootbox", 2), BundleEntry("ae_lootbox", null)))
        val snapshot = HelpCenterEnchantmentSourceSnapshot(emptyList(), listOf(item, bundle), null)
        HelpCenterEnchantmentSources.rewardOutcomes(RewardCatalogSource.Preset("bundle"), snapshot, 3) shouldBe
            listOf(HelpCenterEnchantmentRewardOutcome("ae_lootbox", 1.0, 5))
    }
})

private fun reward(kind: String, id: String, name: String, probability: Double, topic: String, amount: Int) =
    HelpCenterExternalEnchantmentReward(kind, id, name, probability, listOf(HelpCenterEnchantmentRewardOutcome(topic, 1.0, amount)))
