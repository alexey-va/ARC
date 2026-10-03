package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry

/** Named-argument completions for `/arc hunt`; positional forms remain accepted but are not advertised. */
internal object HuntSubCommandTabComplete {
    private const val CUSTOM = "custom"
    private const val GENERATE = "generate"
    private val HERE_TOKENS = setOf("here", "@here")
    private val RADIUS_HINTS = listOf("50", "80", "100", "150", "200")
    private val CHEST_HINTS = listOf("5", "10", "15", "20", "30", "50")
    private val PRIORITY_CHEST_MODELS = listOf("vanilla", "pumpkin_1", "pumpkin_2", "easter", "skull_in_jar")

    fun complete(sender: CommandSender, args: Array<String>): List<String>? {
        if (args.isEmpty()) return null
        val partial = args.last()
        val completed = args.drop(1).dropLast(1)
        val suggestions =
            when (args[0].lowercase()) {
                "start" -> completeStart(sender, completed, partial)
                "stop" -> completeStop(completed, partial)
                else -> if (args.size == 1) topLevel(partial) else emptyList()
            }
        return suggestions.distinct().filterPrefix(partial).takeIf { it.isNotEmpty() }
    }

    private fun completeStart(
        sender: CommandSender,
        completed: List<String>,
        partial: String,
    ): List<String> {
        if (completed.isEmpty()) {
            if (partial.contains('=')) {
                return namedArguments(sender, partial, emptyList(), setOf("preset", "chests", "replace"))
            }
            return listOf("custom", "preset=") + presetIds().map { "preset=$it" }
        }

        if (completed.first().equals(CUSTOM, ignoreCase = true)) {
            return completeCustom(sender, completed.drop(1), partial)
        }

        val namedPreset = completed.any { '=' in it } || partial.contains('=')
        if (!namedPreset) return emptyList()
        return namedArguments(sender, partial, completed, setOf("preset", "chests", "replace"))
    }

    private fun completeCustom(
        sender: CommandSender,
        completed: List<String>,
        partial: String,
    ): List<String> {
        if (completed.isEmpty()) {
            if (partial.startsWith("pool=", ignoreCase = true)) {
                return namedArguments(sender, partial, emptyList(), setOf("pool", "chests", "chest", "loot", "replace"))
            }
            if (partial.contains('=')) {
                return namedArguments(sender, partial, emptyList(), setOf("pool", "chests", "chest", "loot", "replace"))
            }
            return listOf(GENERATE, "pool=") + persistentPools().map { "pool=$it" }
        }

        if (completed.first().equals(GENERATE, ignoreCase = true)) {
            return completeGenerate(sender, completed.drop(1), partial)
        }

        if (completed.any { '=' in it } || partial.contains('=')) {
            return namedArguments(sender, partial, completed, setOf("pool", "chests", "chest", "loot", "replace"))
        }
        return emptyList()
    }

    private fun completeGenerate(
        sender: CommandSender,
        completed: List<String>,
        partial: String,
    ): List<String> {
        if (completed.firstOrNull()?.lowercase()?.let { it in HERE_TOKENS } == true) {
            return namedArguments(
                sender,
                partial,
                completed.drop(1),
                setOf("radius", "chests", "chest", "loot", "replace"),
            )
        }

        val coordinateKeys = setOf("world", "x", "y", "z", "radius", "chests", "chest", "loot", "replace")
        if (completed.any { '=' in it } || partial.contains('=')) {
            return namedArguments(sender, partial, completed, coordinateKeys)
        }

        return listOf("here", "@here") + worldCandidates() + coordinateCandidates(sender)
    }

    private fun completeStop(completed: List<String>, partial: String): List<String> {
        val activePools = activeHuntPools()
        if (completed.any { '=' in it } || partial.contains('=')) {
            return namedArguments(
                sender = null,
                partial = partial,
                completed = completed,
                allowed = setOf("pool"),
                values = mapOf("pool" to activePools),
            )
        }
        if (completed.isNotEmpty()) return emptyList()
        return listOf("pool=") + activePools.map { "pool=$it" }
    }

    private fun namedArguments(
        sender: CommandSender?,
        partial: String,
        completed: List<String>,
        allowed: Set<String>,
        values: Map<String, List<String>> = emptyMap(),
    ): List<String> {
        val used = completed.filter { '=' in it }.mapTo(mutableSetOf()) { it.substringBefore('=').lowercase() }
        if ('=' in partial) {
            val key = partial.substringBefore('=').lowercase()
            if (key !in allowed || key in used) return emptyList()
            val valuePrefix = partial.substringAfter('=')
            return (values[key] ?: valuesFor(sender, key, completed))
                .filterPrefix(valuePrefix)
                .map { "$key=$it" }
        }

        return allowed.filterNot { it in used }.map { "$it=" }.filterPrefix(partial)
    }

    private fun valuesFor(
        sender: CommandSender?,
        key: String,
        completed: List<String>,
    ): List<String> =
        when (key) {
            "preset" -> presetIds()
            "pool" -> persistentPools()
            "chests" -> {
                val poolId =
                    completed.firstOrNull { it.startsWith("pool=", ignoreCase = true) }?.substringAfter('=')
                        ?: completed.firstOrNull { it.startsWith("preset=", ignoreCase = true) }
                            ?.substringAfter('=')
                            ?.let { preset ->
                                TreasureHuntManager.getTreasureHuntType(preset)?.getLocationPool()?.id
                            }
                chestCountHints(poolId)
            }
            "chest" -> chestModels()
            "loot" -> treasurePools()
            "radius" -> RADIUS_HINTS
            "replace" -> listOf("true", "false")
            "world" -> Bukkit.getWorlds().map { it.name }.sorted()
            "x" -> listOf((sender as? Player)?.location?.blockX?.toString() ?: "0")
            "y" -> listOf((sender as? Player)?.location?.blockY?.toString() ?: "0")
            "z" -> listOf((sender as? Player)?.location?.blockZ?.toString() ?: "0")
            else -> emptyList()
        }

    private fun topLevel(partial: String) = listOf("status", "types", "start", "stop", "stopall").filterPrefix(partial)

    private fun presetIds() = TreasureHuntManager.getTreasureHuntTypes().sorted()

    private fun persistentPools() =
        LocationPoolManager.getAll().map { it.id }.filterNot { LocationPoolManager.isEphemeralPool(it) }.sorted()

    private fun activeHuntPools() =
        TreasureHuntManager.getActiveHunts().map { it.config.locationPoolId }.distinct().sorted()

    private fun chestCountHints(poolId: String?): List<String> {
        val poolSize = poolId?.let { LocationPoolManager.getPool(it) }?.size?.takeIf { it > 0 }?.toString()
        return listOfNotNull(poolSize).plus(CHEST_HINTS).distinct()
    }

    private fun chestModels(): List<String> =
        (PRIORITY_CHEST_MODELS + TreasureHuntRegistry.getAliases().keys + "vanilla").distinct()

    private fun treasurePools() = TreasureHuntManager.getTreasurePools().map { it.id }.sorted()

    private fun worldCandidates() =
        Bukkit.getWorlds().map { "world=${it.name}" }.plus("world=").filterPrefix("world=")

    private fun coordinateCandidates(sender: CommandSender) =
        listOf(
            "x=${(sender as? Player)?.location?.blockX ?: 0}",
            "y=${(sender as? Player)?.location?.blockY ?: 0}",
            "z=${(sender as? Player)?.location?.blockZ ?: 0}",
        )

    private fun List<String>.filterPrefix(prefix: String): List<String> =
        filter { it.startsWith(prefix, ignoreCase = true) }
}
