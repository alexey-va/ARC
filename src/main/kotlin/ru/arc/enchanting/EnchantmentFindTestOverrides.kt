package ru.arc.enchanting

import java.util.Collections
import java.util.Locale

internal data class EnchantmentFindChances(val chancePercent: Double, val keyChancePercent: Double) {
    init { validateEnchantmentFindChances(chancePercent, keyChancePercent) }
}

internal fun immutableEnchantmentFindTestOverrides(
    overrides: Map<String, EnchantmentFindChances>,
): Map<String, EnchantmentFindChances> {
    require(overrides.keys.all { ENCHANTMENT_FIND_TEST_PLAYER.matches(it) }) { "Find test player names must be 3-16 Minecraft name characters" }
    val normalized = overrides.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }
    require(normalized.size == overrides.size) { "Find test player names must be unique ignoring case" }
    return Collections.unmodifiableMap(normalized)
}

/** Operator-only personal odds; omitted activities retain their ordinary configured chances. */
internal class EnchantmentFindTestOverrides private constructor(
    val mining: Map<String, EnchantmentFindChances>,
    val farming: Map<String, EnchantmentFindChances>,
) {
    companion object {
        private val EMPTY = EnchantmentFindTestOverrides(emptyMap(), emptyMap())

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
            val seenNames = mutableSetOf<String>()
            players.forEach { (rawName, rawProfile) ->
                val name = rawName as? String ?: throw IllegalArgumentException("Find test player name must be a string")
                require(ENCHANTMENT_FIND_TEST_PLAYER.matches(name)) { "Invalid find test player name: $name" }
                require(seenNames.add(name.lowercase(Locale.ROOT))) { "Find test player names must be unique ignoring case" }
                val profile = rawProfile as? Map<*, *> ?: throw IllegalArgumentException("Find test profile for '$name' must be a map")
                require(profile.keys.all { it == "mining-cache" || it == "farming-cache" }) { "Unknown find test activity for '$name'" }
                profile.forEach { (activity, rawChances) ->
                    val chances = rawChances as? Map<*, *> ?: throw IllegalArgumentException("Find test chances for '$name/$activity' must be a map")
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
            return if (enabled) EnchantmentFindTestOverrides(
                immutableEnchantmentFindTestOverrides(mining), immutableEnchantmentFindTestOverrides(farming),
            ) else EMPTY
        }
    }
}

private val ENCHANTMENT_FIND_TEST_PLAYER = Regex("[A-Za-z0-9_]{3,16}")
