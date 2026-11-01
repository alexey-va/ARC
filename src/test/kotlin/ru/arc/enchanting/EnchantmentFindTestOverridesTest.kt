package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
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

    "Creative opt-in is named case insensitive and limited to explicitly configured activities" {
        val raw = mapOf("enabled" to true, "players" to mapOf(
            "GrocerMC" to mapOf("allow-creative" to true, "mining-cache" to odds(50.0, 50.0), "world-cache" to mapOf("one-in" to 1)),
            "OtherPlayer" to mapOf("farming-cache" to odds(100.0, 0.0)),
            "FlagOnly" to mapOf("allow-creative" to true),
        ))
        val profiles = EnchantmentFindTestOverrides.parse(raw)
        profiles.miningCreativePlayers shouldBe setOf("grocermc")
        profiles.farmingCreativePlayers shouldBe emptySet()
        profiles.worldCacheCreativePlayers shouldBe setOf("grocermc")
        val mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02, .001,
            profiles.mining, profiles.miningCreativePlayers)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), .01, 86_400, .0005,
            profiles.farming, profiles.farmingCreativePlayers)
        val world = EnchantmentWorldCacheSettings(true, true, setOf("survival"), 128,
            profiles.worldCaches, profiles.worldCacheCreativePlayers)
        mining.chancesFor("GROCermc") shouldBe EnchantmentFindChances(50.0, 50.0)
        mining.allowsGameMode("GROCermc", GameMode.CREATIVE) shouldBe true
        farming.allowsGameMode("GrocerMC", GameMode.CREATIVE) shouldBe false
        farming.allowsGameMode("OtherPlayer", GameMode.CREATIVE) shouldBe false
        world.denominatorFor("GROCermc") shouldBe 1
        world.denominatorFor("OtherPlayer") shouldBe 128
        for (mode in listOf(GameMode.ADVENTURE, GameMode.SPECTATOR)) {
            mining.allowsGameMode("GrocerMC", mode) shouldBe false
            world.allowsGameMode("GrocerMC", mode) shouldBe false
        }
        mining.allowsGameMode("FlagOnly", GameMode.CREATIVE) shouldBe false
        mining.allowsGameMode("UnlistedAdmin", GameMode.CREATIVE) shouldBe false
        mining.allowsGameMode("UnlistedAdmin", GameMode.SURVIVAL) shouldBe true
        val off = EnchantmentFindTestOverrides.parse(raw + ("enabled" to false))
        off.mining shouldBe emptyMap()
        off.worldCaches shouldBe emptyMap()
        off.miningCreativePlayers shouldBe emptySet()
        off.worldCacheCreativePlayers shouldBe emptySet()
    }

    "Creative flags and world denominators are strict and do not weaken chance pairs" {
        fun parse(profile: Map<String, Any?>) = EnchantmentFindTestOverrides.parse(
            mapOf("enabled" to true, "players" to mapOf("GrocerMC" to profile)))
        for (flag in listOf(null, "true", 1, emptyMap<String, Any>())) {
            shouldThrow<IllegalArgumentException> { parse(mapOf("allow-creative" to flag)) }
        }
        for (oneIn in listOf(0, -1, 1.0, "1", Long.MAX_VALUE, true)) {
            shouldThrow<IllegalArgumentException> { parse(mapOf("world-cache" to mapOf("one-in" to oneIn))) }
        }
        parse(mapOf("world-cache" to mapOf("one-in" to 1L))).worldCaches.getValue("grocermc") shouldBe 1
        shouldThrow<IllegalArgumentException> { parse(mapOf("world-cache" to mapOf("one-in" to 1, "key-chance-percent" to 50))) }
        shouldThrow<IllegalArgumentException> { parse(mapOf("allow-creative" to true, "mining-cache" to mapOf("chance-percent" to 50))) }
        shouldThrow<IllegalArgumentException> {
            EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02,
                creativeTestPlayers = setOf("GrocerMC"))
        }
    }

    "profile collections and all activity Creative policies are immutable detached snapshots" {
        val odds = mutableMapOf<String, Any>("chance-percent" to 50, "key-chance-percent" to 50)
        val worldOdds = mutableMapOf("one-in" to 1)
        val profile = mutableMapOf<String, Any>("allow-creative" to true, "mining-cache" to odds,
            "farming-cache" to odds, "world-cache" to worldOdds)
        val parsed = EnchantmentFindTestOverrides.parse(mapOf("enabled" to true, "players" to mapOf("GrocerMC" to profile)))
        odds.clear(); worldOdds.clear(); profile.clear()
        parsed.mining.getValue("grocermc") shouldBe EnchantmentFindChances(50.0, 50.0)
        parsed.worldCaches.getValue("grocermc") shouldBe 1
        shouldThrow<UnsupportedOperationException> { (parsed.worldCaches as MutableMap<String, Int>).clear() }
        for (names in listOf(parsed.miningCreativePlayers, parsed.farmingCreativePlayers, parsed.worldCacheCreativePlayers)) {
            names shouldBe setOf("grocermc")
            shouldThrow<UnsupportedOperationException> { (names as MutableSet<String>).clear() }
        }
        val names = mutableSetOf("GrocerMC")
        val input = mutableMapOf("GrocerMC" to EnchantmentFindChances(50.0, 50.0))
        val mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), .02, .001, input, names)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), .01, 86_400, .0005, input, names)
        val world = EnchantmentWorldCacheSettings(true, true, setOf("survival"), 128, mapOf("GrocerMC" to 1), names)
        names.clear(); input.clear()
        mining.allowsGameMode("GrocerMC", GameMode.CREATIVE) shouldBe true
        farming.allowsGameMode("GrocerMC", GameMode.CREATIVE) shouldBe true
        world.allowsGameMode("GrocerMC", GameMode.CREATIVE) shouldBe true
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
