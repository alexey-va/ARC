package ru.arc.enchanting

import org.bukkit.GameMode
import java.util.Collections
import java.util.Locale

internal data class EnchantmentFindChances(val chancePercent: Double, val keyChancePercent: Double) {
    init { validateEnchantmentFindChances(chancePercent, keyChancePercent) }
}

internal fun <T> immutableEnchantmentFindTestOverrides(overrides: Map<String, T>): Map<String, T> {
    require(overrides.keys.all { ENCHANTMENT_FIND_TEST_PLAYER.matches(it) }) { "Find test player names must be 3-16 Minecraft name characters" }
    val normalized = overrides.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }
    require(normalized.size == overrides.size) { "Find test player names must be unique ignoring case" }
    return Collections.unmodifiableMap(normalized)
}

internal fun immutableEnchantmentFindCreativePlayers(players: Set<String>, activityNames: Set<String>): Set<String> {
    val normalized = immutableEnchantmentFindTestOverrides(players.associateWith { Unit }).keys
    require(activityNames.containsAll(normalized)) { "Creative find players require an explicit override for that activity" }
    return Collections.unmodifiableSet(normalized.toSet())
}

internal fun allowsEnchantmentFindGameMode(playerName: String, gameMode: GameMode, creativePlayers: Set<String>): Boolean =
    gameMode == GameMode.SURVIVAL || gameMode == GameMode.CREATIVE && playerName.lowercase(Locale.ROOT) in creativePlayers

/** Personal test profiles; omitted activities retain ordinary chances and Survival-only eligibility. */
internal class EnchantmentFindTestOverrides private constructor(
    val mining: Map<String, EnchantmentFindChances>,
    val farming: Map<String, EnchantmentFindChances>,
    val worldCaches: Map<String, Int>,
    val miningCreativePlayers: Set<String>,
    val farmingCreativePlayers: Set<String>,
    val worldCacheCreativePlayers: Set<String>,
) {
    companion object {
        private val EMPTY = EnchantmentFindTestOverrides(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptySet(), emptySet())

        fun parse(raw: Any?): EnchantmentFindTestOverrides {
            if (raw == null) return EMPTY
            val section = raw as? Map<*, *> ?: throw IllegalArgumentException("loot.test-overrides must be a map")
            require(section.keys.all { it == "enabled" || it == "players" }) { "Unknown loot.test-overrides field" }
            val enabled = section["enabled"] ?: false
            require(enabled is Boolean) { "loot.test-overrides.enabled must be a boolean" }
            val players = section["players"] ?: emptyMap<String, Any>()
            require(players is Map<*, *>) { "loot.test-overrides.players must be a map" }
            val mining = linkedMapOf<String, EnchantmentFindChances>()
            val farming = linkedMapOf<String, EnchantmentFindChances>()
            val worldCaches = linkedMapOf<String, Int>()
            val creativePlayers = mutableSetOf<String>()
            val seenNames = mutableSetOf<String>()
            players.forEach { (rawName, rawProfile) ->
                val name = rawName as? String ?: throw IllegalArgumentException("Find test player name must be a string")
                require(ENCHANTMENT_FIND_TEST_PLAYER.matches(name)) { "Invalid find test player name: $name" }
                require(seenNames.add(name.lowercase(Locale.ROOT))) { "Find test player names must be unique ignoring case" }
                val profile = rawProfile as? Map<*, *> ?: throw IllegalArgumentException("Find test profile for '$name' must be a map")
                require(profile.keys.all { it in setOf("mining-cache", "farming-cache", "world-cache", "allow-creative") }) {
                    "Unknown find test activity for '$name'"
                }
                val allowCreative = if (profile.containsKey("allow-creative")) profile["allow-creative"] else false
                require(allowCreative is Boolean) { "Find test allow-creative for '$name' must be a boolean" }
                if (allowCreative) creativePlayers += name.lowercase(Locale.ROOT)
                profile.forEach activity@{ (activity, rawChances) ->
                    if (activity == "allow-creative") return@activity
                    val chances = rawChances as? Map<*, *> ?: throw IllegalArgumentException("Find test chances for '$name/$activity' must be a map")
                    if (activity == "world-cache") {
                        require(chances.keys == setOf("one-in")) { "Find test world-cache for '$name' requires only one-in" }
                        val oneIn = chances["one-in"]
                        require(oneIn is Int || oneIn is Long) { "Find test world-cache one-in for '$name' must be a positive integer" }
                        val denominator = (oneIn as Number).toLong()
                        require(denominator in 1L..Int.MAX_VALUE.toLong()) { "Find test world-cache one-in for '$name' must fit a positive integer" }
                        worldCaches[name] = denominator.toInt()
                        return@activity
                    }
                    require(chances.keys == setOf("chance-percent", "key-chance-percent")) {
                        "Find test chances for '$name/$activity' require only chance-percent and key-chance-percent"
                    }
                    val box = chances["chance-percent"] as? Number
                        ?: throw IllegalArgumentException("Find test chance-percent for '$name/$activity' must be numeric")
                    val key = chances["key-chance-percent"] as? Number
                        ?: throw IllegalArgumentException("Find test key-chance-percent for '$name/$activity' must be numeric")
                    val target = if (activity == "mining-cache") mining else farming
                    target[name] = EnchantmentFindChances(box.toDouble(), key.toDouble())
                }
            }
            if (!enabled) return EMPTY
            val miningSnapshot = immutableEnchantmentFindTestOverrides(mining)
            val farmingSnapshot = immutableEnchantmentFindTestOverrides(farming)
            val worldSnapshot = immutableEnchantmentFindTestOverrides(worldCaches)
            return EnchantmentFindTestOverrides(miningSnapshot, farmingSnapshot, worldSnapshot,
                immutableEnchantmentFindCreativePlayers(creativePlayers.intersect(miningSnapshot.keys), miningSnapshot.keys),
                immutableEnchantmentFindCreativePlayers(creativePlayers.intersect(farmingSnapshot.keys), farmingSnapshot.keys),
                immutableEnchantmentFindCreativePlayers(creativePlayers.intersect(worldSnapshot.keys), worldSnapshot.keys))
        }
    }
}

private val ENCHANTMENT_FIND_TEST_PLAYER = Regex("[A-Za-z0-9_]{3,16}")
