package ru.arc.helpcenter

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.bschests.PersonalLootPoolSettings
import ru.arc.enchanting.AdvancedEnchantmentLootSettings
import ru.arc.enchanting.EnchantmentMiningLootSettings
import ru.arc.enchanting.EnchantmentFarmingLootSettings
import ru.arc.enchanting.EnchantmentWorldCacheSettings
import ru.arc.ops.BundleDefinition
import ru.arc.ops.BundleEntry
import ru.arc.ops.ItemDefinition
import ru.arc.ops.ItemPresetDefinition
import ru.arc.treasure.core.AeArg
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.TreasureRollRange
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class HelpCenterEnchantmentSourcesTest : StringSpec({
    "new pool weights and configured box rolls replace previous reported odds" {
        fun snapshot(weight: Int, min: Int, max: Int) = sources(
            listOf(TreasurePool("ae", listOf(item("whitescroll", weight), item("magic", 8), item("whitescroll", -50)))),
            listOf(box("ae_lootbox", "ae", min, max)),
        )
        val before = HelpCenterEnchantmentSources.describe("whitescroll", snapshot(2, 1, 2)).single("pool-item")
        before.values["per_roll"] shouldBe "20"
        before.values["per_box"] shouldBe "28"

        val after = HelpCenterEnchantmentSources.describe("whitescroll", snapshot(8, 4, 5)).single("pool-item")
        after.values["per_roll"] shouldBe "50"
        after.values["per_box"] shouldBe "95,3125"
        HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot(8, 4, 5)).single("box-rolls").values shouldBe
            mapOf("min" to "4", "max" to "5", "pool" to "ae")
    }

    "duplicate dust rows add their weights once without turning potency into quantity" {
        fun dust(weight: Int) = item("MAGIC", weight, 4, listOf(AeArg.RandomTier, AeArg.IntRange(3, 3)))
        val snapshot = sources(
            listOf(TreasurePool("ae", listOf(dust(30), dust(20), dust(20), item("whitescroll", 51)))),
            listOf(box("ae_lootbox", "ae", 1, 2)),
        )
        val report = HelpCenterEnchantmentSources.describe("magic", snapshot)
        report.single("pool-item").values["per_roll"] shouldBe "57,8512"
        report.single("pool-item").values["amount"] shouldBe "4"
        val value = report.single("pool-item-value").values
        value["valueKind"] shouldBe "dust"
        value["amount"] shouldBe "4"
        value["min"] shouldBe "3"
        value["max"] shouldBe "3"
        val contents = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot).facts.filter { it.key == "box-content" }
        contents.count { it.values["item"] == "magic" } shouldBe 1
    }

    "supply contents and boss acquisition follow the active pool and configured preset" {
        val snapshot = sources(
            listOf(TreasurePool("enchant_boss_supply", listOf(
                item("magic", 90, 1, listOf(AeArg.RandomTier, AeArg.IntRange(3, 7))),
                Treasure.Ae(AeKind.RANDOM_BOOK, amount = 1, weight = 10, group = "SIMPLE", maxLevel = 1),
            ))),
            listOf(box("enchant_boss_supply", "enchant_boss_supply", 1, 1)),
        ).copy(bossReward = HelpCenterEnchantmentBossReward(true, "enchant_boss_supply"))
        val contents = HelpCenterEnchantmentSources.describe("enchant_boss_supply", snapshot)
        contents.single("boss-source").values shouldBe mapOf("chance" to "100", "amount" to "1")
        val dust = contents.facts.single { it.key == "box-content" && it.values["item"] == "magic" }
        dust.values["per_roll"] shouldBe "90"
        dust.values["amount"] shouldBe "1"
        dust.values["min"] shouldBe "3"
        dust.values["max"] shouldBe "7"
        val book = contents.facts.single { it.key == "box-content" && it.values["item"] == "book" }
        book.values["group"] shouldBe "SIMPLE"
        book.values["level"] shouldBe "1"
        HelpCenterEnchantmentSources.describe("enchant_boss_supply", snapshot.copy(
            bossReward = HelpCenterEnchantmentBossReward(true, "ae_lootbox"),
        )).facts.any { it.key == "boss-source" } shouldBe false
    }

    "exclusive nested choices and bundle quantities preserve the upstream box chance" {
        val snapshot = sources(
            listOf(
                TreasurePool("outer", listOf(Treasure.SubPool("inner", weight = 1), Treasure.Money(weight = 1))),
                TreasurePool("inner", listOf(Treasure.Preset("bundle", amount = 4, weight = 1), Treasure.Money(weight = 1))),
                TreasurePool("ae", listOf(item("whitescroll", 1))),
            ),
            listOf(
                box("ae_lootbox", "ae", 1, 1),
                BundleDefinition("bundle", null, listOf(BundleEntry("ae_lootbox", 1), BundleEntry("ae_lootbox", null))),
            ),
        )
        val sources = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot).facts.filter { it.key == "pool-source" }
        sources.single { it.values["pool"] == "outer" }.values shouldBe mapOf("pool" to "outer", "per_roll" to "25", "amount" to "5")
        sources.single { it.values["pool"] == "inner" }.values shouldBe mapOf("pool" to "inner", "per_roll" to "50", "amount" to "5")
    }

    "orb slot selection and verified rename ID use the native command semantics" {
        val snapshot = sources(
            listOf(TreasurePool("ae", listOf(
                item("orb", 3, 1, listOf(AeArg.RandomSlot, AeArg.IntRange(10, 13), AeArg.IntRange(1, 100))),
                item("renametag", 3),
            ))),
            listOf(box("ae_lootbox", "ae", 1, 1)),
        )
        val orb = HelpCenterEnchantmentSources.describe("orb_weapon", snapshot)
        orb.single("pool-item").values["per_roll"] shouldBe "16,6667"
        orb.single("pool-item-value").values["min"] shouldBe "10"
        orb.single("pool-item-value").values["max"] shouldBe "13"
        HelpCenterEnchantmentSources.describe("itemnametag", snapshot).single("pool-item").values["per_roll"] shouldBe "50"
    }

    "natural source percentages refresh from the listener settings and retain local activation scope" {
        val original = AdvancedEnchantmentLootSettings(true, setOf("world"), 0.2, 0.8, 0.5, 1.5, mapOf("SIMPLE" to 1))
        val snapshot = sources(emptyList(), emptyList()).copy(naturalLoot = original, serverName = "survival")
        val report = HelpCenterEnchantmentSources.describe("overview", snapshot)
        report.single("scope").values["server"] shouldBe "survival"
        report.single("natural-books").values["kill"] shouldBe "0,2"
        report.single("natural-dust").values["fishing"] shouldBe "1,5"
        val changed = HelpCenterEnchantmentSources.describe("overview", snapshot.copy(
            naturalLoot = original.copy(naturalKillBookPercent = 3.0, naturalKillDustPercent = 7.0),
        ))
        changed.single("natural-books").values["kill"] shouldBe "3"
        changed.single("natural-dust").values["kill"] shouldBe "7"
        val inactive = HelpCenterEnchantmentSources.describe("overview", snapshot.copy(naturalLoot = original.copy(survivalBackend = false)))
        inactive.facts.any { it.key == "natural-books" || it.key == "natural-dust" } shouldBe false
        inactive.single("natural-inactive").values shouldBe emptyMap()
    }

    "at least one probability uses independent draws after one uniformly selected roll count" {
        HelpCenterEnchantmentSources.atLeastOne(0.2, TreasureRollRange(1, 2)) shouldBe (0.28 plusOrMinus 1e-12)
        HelpCenterEnchantmentSources.atLeastOne(0.5, TreasureRollRange(4, 5)) shouldBe (0.953125 plusOrMinus 1e-12)
        HelpCenterEnchantmentSources.atLeastOne(0.0, TreasureRollRange(1, 64)) shouldBe 0.0
        HelpCenterEnchantmentSources.atLeastOne(1.0, TreasureRollRange(1, 64)) shouldBe 1.0
    }

    "personal chests combine common draws with a single independent optional bonus" {
        val snapshot = sources(
            listOf(
                TreasurePool("common", listOf(Treasure.Preset("ae_lootbox", weight = 1), Treasure.Money(weight = 1))),
                TreasurePool("bonus", listOf(Treasure.Preset("ae_lootbox", weight = 1))),
                TreasurePool("ae", listOf(item("whitescroll", 1))),
            ),
            listOf(box("ae_lootbox", "ae", 1, 1)),
        ).copy(personalLoot = PersonalLootPoolSettings("common", 2, 2, "bonus", 0.25))
        val report = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot)
        report.single("personal-loot-source").values["chance"] shouldBe "81,25"
        val changed = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot.copy(
            personalLoot = snapshot.personalLoot?.copy(bonusChance = 0.0),
        ))
        changed.single("personal-loot-source").values["chance"] shouldBe "75"
    }

    "mining farming and exploration sources use the registered settings and disappear when inactive" {
        val mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE, Material.DEEPSLATE), 0.05)
        val surface = EnchantmentWorldCacheSettings(true, true, setOf("survival"), 128)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT, Material.CARROTS), 0.05, 86_400)
        val snapshot = sources(emptyList(), emptyList()).copy(miningCache = mining, worldCache = surface, farmingCache = farming)
        val current = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot)
        current.single("mining-cache").values["chance"] shouldBe "0,05"
        current.single("mining-cache-materials").values["materials"] shouldBe "DEEPSLATE,STONE"
        current.single("world-cache").values["chance"] shouldBe "0,7813"
        current.single("world-cache").values["one_in"] shouldBe "128"
        current.single("farming-cache").values["chance"] shouldBe "0,05"
        current.single("farming-cache").values["cooldown"] shouldBe "1d"
        current.single("farming-cache-materials").values["materials"] shouldBe "CARROTS,WHEAT"

        val changed = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot.copy(
            miningCache = EnchantmentMiningLootSettings(true, setOf("vanilla"), setOf(Material.STONE), 0.1),
            worldCache = EnchantmentWorldCacheSettings(true, true, setOf("vanilla"), 64),
        ))
        changed.single("mining-cache").values["chance"] shouldBe "0,1"
        changed.single("world-cache").values["chance"] shouldBe "1,5625"
        val inactive = HelpCenterEnchantmentSources.describe("ae_lootbox", snapshot.copy(miningCache = null, worldCache = null, farmingCache = null))
        inactive.facts.none { it.key in setOf("mining-cache", "world-cache", "farming-cache") } shouldBe true
    }

    "missing or cyclic sources are unavailable instead of fabricated no-drop conclusions" {
        val snapshot = sources(
            listOf(TreasurePool("ae", listOf(Treasure.SubPool("ae")))),
            listOf(box("ae_lootbox", "ae", 1, 1)),
        )
        val report = HelpCenterEnchantmentSources.describe("secret", snapshot)
        report.facts.any { it.key == "no-drop" } shouldBe false
        report.facts.any { it.key == "unavailable" && it.values["source"] == "ae" } shouldBe true
        HelpCenterEnchantmentSources.describe("secret", sources(emptyList(), emptyList())).single("no-drop").values shouldBe emptyMap()
    }

    "a synchronous external adapter or linkage failure preserves all known local source facts" {
        val local = HelpCenterEnchantmentAcquisition(listOf(HelpCenterEnchantmentSourceFact("pool-item", mapOf("per_roll" to "20"))), listOf("ae_lootbox"))
        for (failure in listOf(IllegalStateException("invalid provider snapshot"), NoClassDefFoundError("missing optional API"))) {
            val reported = mutableListOf<Throwable>()
            val result = HelpCenterEnchantmentSources.withExternal(local, { throw failure }, { reported += it }).getNow(null)
            result.facts.first() shouldBe local.facts.first()
            result.relatedTopics shouldBe local.relatedTopics
            result.single("unavailable").values["source"] shouldBe "external-sources"
            reported shouldBe listOf(failure)
        }
    }

    "an exceptional external future preserves local facts and unwraps its logged cause" {
        val local = HelpCenterEnchantmentAcquisition(listOf(HelpCenterEnchantmentSourceFact("natural-dust")))
        val failure = IllegalStateException("provider unavailable")
        val reported = mutableListOf<Throwable>()
        val result = HelpCenterEnchantmentSources.withExternal(local, {
            CompletableFuture.failedFuture(CompletionException(failure))
        }, { reported += it }).getNow(null)
        result.facts.first() shouldBe local.facts.first()
        result.single("unavailable").values["source"] shouldBe "external-sources"
        reported shouldBe listOf(failure)
    }

    "a successful external snapshot replaces only pending external availability facts" {
        val local = HelpCenterEnchantmentAcquisition(listOf(
            HelpCenterEnchantmentSourceFact("pool-item"),
            HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "rank-quests")),
        ), listOf("ae_lootbox"))
        val external = HelpCenterEnchantmentAcquisition(listOf(HelpCenterEnchantmentSourceFact("quest-source")), listOf("enchant_supply"))
        val result = HelpCenterEnchantmentSources.withExternal(local, { CompletableFuture.completedFuture(external) }, { error("No failure expected") }).getNow(null)
        result.facts.map { it.key } shouldBe listOf("pool-item", "quest-source")
        result.relatedTopics shouldBe listOf("ae_lootbox", "enchant_supply")
    }

    "external cancellation remains cancelled without an availability warning or failure log" {
        var reports = 0
        val result = HelpCenterEnchantmentSources.withExternal(HelpCenterEnchantmentAcquisition(emptyList()), {
            CompletableFuture.failedFuture(CompletionException(CancellationException("closed")))
        }, { reports++ })
        result.isCancelled shouldBe true
        reports shouldBe 0
    }
})

private fun item(name: String, weight: Int, amount: Int = 1, args: List<AeArg> = emptyList()) =
    Treasure.Ae(AeKind.ITEM, name, amount, args, weight = weight)

private fun box(id: String, pool: String, min: Int, max: Int) = ItemDefinition(
    id, null, JsonParser.parseString("""{"customData":{"arc:treasure_key":"$pool","arc:treasure_rolls_min":$min,"arc:treasure_rolls_max":$max}}""").asJsonObject,
)

private fun sources(pools: List<TreasurePool>, presets: List<ItemPresetDefinition>) =
    HelpCenterEnchantmentSourceSnapshot(pools, presets, null)

private fun HelpCenterEnchantmentAcquisition.single(key: String) = facts.single { it.key == key }
