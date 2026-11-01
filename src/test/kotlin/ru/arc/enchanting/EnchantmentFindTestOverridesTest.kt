package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class EnchantmentFindTestOverridesTest : StringSpec({
    "only enabled exact named profiles replace the selected activity" {
        val raw = mapOf(
            "enabled" to true,
            "players" to mapOf("CodexQA_728" to mapOf("mining-cache" to odds(100.0, 0.0))),
        )
        val enabled = EnchantmentFindTestOverrides.parse(raw)
        val mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02, .001, enabled.mining)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), .01, 86_400, .0005, enabled.farming)
        mining.chancesFor("CODEXqa_728") shouldBe EnchantmentFindChances(100.0, 0.0)
        mining.chancesFor("SomebodyElse") shouldBe EnchantmentFindChances(.02, .001)
        farming.chancesFor("CodexQA_728") shouldBe EnchantmentFindChances(.01, .0005)
        mining.chancePercent shouldBe .02
        mining.keyChancePercent shouldBe .001
        mining.allowsWorld("arc_qa_flat") shouldBe false
        EnchantmentFindTestOverrides.parse(raw + ("enabled" to false)).mining shouldBe emptyMap()
        EnchantmentFindTestOverrides.parse(null).farming shouldBe emptyMap()
    }

    "test chances retain exclusive box and key ranges" {
        for (chances in listOf(EnchantmentFindChances(100.0, 0.0), EnchantmentFindChances(0.0, 100.0))) {
            val expected = if (chances.chancePercent == 100.0) EnchantmentFindReward.LOOTBOX else EnchantmentFindReward.KEY
            for (roll in listOf(0.0, .5, .999999)) {
                enchantmentFindReward(roll, chances.chancePercent, chances.keyChancePercent) shouldBe expected
            }
        }
    }

    "invalid names duplicate folded names malformed profiles and invalid chance pairs are rejected" {
        fun parsePlayers(players: Any) = EnchantmentFindTestOverrides.parse(mapOf("enabled" to true, "players" to players))
        for (name in listOf("ab", "seventeen_chars_x", "a.b", "../world", "Player Name")) {
            shouldThrow<IllegalArgumentException> { parsePlayers(mapOf(name to emptyMap<String, Any>())) }
        }
        shouldThrow<IllegalArgumentException> {
            parsePlayers(mapOf("Finder" to emptyMap<String, Any>(), "FINDER" to emptyMap<String, Any>()))
        }
        for (pair in listOf(-1.0 to 0.0, Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 100.0 to 1.0)) {
            shouldThrow<IllegalArgumentException> {
                parsePlayers(mapOf("Finder" to mapOf("mining-cache" to odds(pair.first, pair.second))))
            }
        }
        for (profile in listOf<Any>(
            "all-admins", mapOf("unknown" to odds(100.0, 0.0)),
            mapOf("mining-cache" to mapOf("chance-percent" to 100)),
            mapOf("farming-cache" to odds(100.0, 0.0) + ("chance" to 50)),
            mapOf("mining-cache" to mapOf("chance-percent" to "100", "key-chance-percent" to 0)),
        )) {
            shouldThrow<IllegalArgumentException> { parsePlayers(mapOf("Finder" to profile)) }
        }
        shouldThrow<IllegalArgumentException> { parsePlayers(listOf("Finder")) }
        shouldThrow<IllegalArgumentException> { EnchantmentFindTestOverrides.parse(mapOf("enabled" to "true")) }
    }

    "both settings retain immutable independent override snapshots" {
        val input = mutableMapOf("Finder" to EnchantmentFindChances(100.0, 0.0))
        val mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02, .001, input)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), .01, 86_400, .0005, input)
        input["Finder"] = EnchantmentFindChances(0.0, 100.0)
        input.clear()
        mining.chancesFor("finder") shouldBe EnchantmentFindChances(100.0, 0.0)
        farming.chancesFor("finder") shouldBe EnchantmentFindChances(100.0, 0.0)
        for (snapshot in listOf(mining.testOverrides, farming.testOverrides)) {
            shouldThrow<UnsupportedOperationException> {
                (snapshot as MutableMap<String, EnchantmentFindChances>)["other"] = EnchantmentFindChances(0.0, 100.0)
            }
        }
        shouldThrow<IllegalArgumentException> {
            EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02, .001,
                mapOf("Finder" to EnchantmentFindChances(100.0, 0.0), "FINDER" to EnchantmentFindChances(0.0, 100.0)))
        }
    }
})

private fun odds(box: Double, key: Double): Map<String, Double> = mapOf("chance-percent" to box, "key-chance-percent" to key)
