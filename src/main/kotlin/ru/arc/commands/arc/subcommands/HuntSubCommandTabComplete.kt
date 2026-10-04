package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry

/** Completions for the named-only `/arc hunt` command grammar. */
internal object HuntSubCommandTabComplete {
    private val startKeyOrder =
        listOf("preset", "locations", "chests", "chest", "rewards", "replace", "generate", "center", "world", "x", "y", "z", "radius")
    private val presetKeys = listOf("preset", "locations", "chests", "chest", "rewards", "replace", "generate")
    private val generatedKeys = listOf("preset", "generate", "center", "world", "x", "y", "z", "radius", "chests", "chest", "rewards", "replace")
    private val poolKeys = listOf("preset", "locations", "chests", "chest", "rewards", "replace")
    private val locationKeys = setOf("center", "world", "x", "y", "z", "radius")
    private val commonChestCountHints = listOf(6, 12, 18, 50)
    private val radiusHints = listOf("50", "80", "100", "150", "200")

    fun complete(sender: CommandSender, args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val partial = args.last()
        val completed = args.drop(1).dropLast(1)
        val suggestions =
            when (args[0].lowercase()) {
                "start" -> completeStart(sender, completed, partial)
                "stop" -> completeStop(completed, partial)
                else -> if (args.size == 1) topLevel(partial) else emptyList()
            }
        return suggestions.filterPrefix(partial)
    }

    private fun completeStart(
        sender: CommandSender,
        completed: List<String>,
        partial: String,
    ): List<String> {
        val parsed = when (val result = HuntNamedArguments.parse(completed, startKeyOrder.toSet())) {
            is HuntNamedArgumentsResult.Parsed -> result.values
            is HuntNamedArgumentsResult.Invalid -> return emptyList()
        }
        val allowed = allowedStartKeys(parsed) ?: return emptyList()
        return completeNamedArguments(sender, parsed, partial, allowed)
    }

    private fun allowedStartKeys(arguments: Map<String, String>): List<String>? {
        val hasGeometry = arguments.keys.any { it in locationKeys }
        if ("locations" in arguments && ("generate" in arguments || hasGeometry)) return null
        if ("generate" in arguments && arguments["generate"] != "true") return null

        val geometryMode = "generate" in arguments || hasGeometry
        if (geometryMode) {
            val centerSpecified = "center" in arguments
            val coordinatesSpecified = arguments.keys.any { it in setOf("world", "x", "y", "z") }
            if (centerSpecified && coordinatesSpecified) return null
            return when {
                centerSpecified -> generatedKeys - setOf("world", "x", "y", "z")
                coordinatesSpecified -> generatedKeys - "center"
                else -> generatedKeys
            }
        }
        if ("preset" in arguments) return if ("locations" in arguments) poolKeys else presetKeys
        return if ("locations" in arguments) poolKeys else startKeyOrder
    }

    private fun completeStop(completed: List<String>, partial: String): List<String> {
        val parsed = when (val result = HuntNamedArguments.parse(completed, setOf("locations"))) {
            is HuntNamedArgumentsResult.Parsed -> result.values
            is HuntNamedArgumentsResult.Invalid -> return emptyList()
        }
        return completeNamedArguments(
            sender = null,
            arguments = parsed,
            partial = partial,
            allowed = listOf("locations"),
            values = mapOf("locations" to activeHuntPools()),
        )
    }

    private fun completeNamedArguments(
        sender: CommandSender?,
        arguments: Map<String, String>,
        partial: String,
        allowed: List<String>,
        values: Map<String, List<String>> = emptyMap(),
    ): List<String> {
        if ('=' in partial) {
            val key = partial.substringBefore('=').lowercase()
            if (key !in allowed || key in arguments) return emptyList()
            val valuePrefix = partial.substringAfter('=')
            val candidates = values[key] ?: valuesFor(sender, key, arguments)
            return candidates.filterPrefix(valuePrefix).map { "$key=$it" }
        }

        return allowed.filterNot(arguments::containsKey).map { "$it=" }.filterPrefix(partial)
    }

    private fun valuesFor(
        sender: CommandSender?,
        key: String,
        arguments: Map<String, String>,
    ): List<String> =
        when (key) {
            "preset" -> presetIds()
            "locations" -> persistentPools()
            "chests" -> chestCountHints(arguments)
            "chest" -> chestModels()
            "rewards" -> rewardPools()
            "replace" -> listOf("true", "false")
            "generate" -> listOf("true")
            "center" -> if (sender is Player) listOf("here") else emptyList()
            "world" -> Bukkit.getWorlds().map { it.name }.sorted()
            "x" -> playerCoordinate(sender) { it.location.blockX }
            "y" -> playerCoordinate(sender) { it.location.blockY }
            "z" -> playerCoordinate(sender) { it.location.blockZ }
            "radius" -> radiusHints
            else -> emptyList()
        }

    private fun chestCountHints(arguments: Map<String, String>): List<String> {
        if (arguments["generate"] == "true" || arguments.keys.any { it in locationKeys }) {
            return commonChestCountHints.map(Int::toString)
        }
        val locationPoolId =
            arguments["locations"]
                ?: arguments["preset"]?.let { TreasureHuntManager.getTreasureHuntType(it)?.locationPoolId }
        if (locationPoolId == null) return commonChestCountHints.map(Int::toString)
        val poolSize = LocationPoolManager.getPool(locationPoolId)?.size ?: return emptyList()
        if (poolSize <= 0) return emptyList()
        return (commonChestCountHints.filter { it <= poolSize } + poolSize).distinct().map(Int::toString)
    }

    private fun playerCoordinate(sender: CommandSender?, value: (Player) -> Int): List<String> =
        (sender as? Player)?.let { listOf(value(it).toString()) } ?: emptyList()

    private fun topLevel(partial: String) = listOf("status", "types", "start", "stop", "stopall").filterPrefix(partial)

    private fun presetIds() = TreasureHuntManager.getTreasureHuntTypes().sorted()

    private fun persistentPools() =
        LocationPoolManager.getAll()
            .filter { !LocationPoolManager.isEphemeralPool(it.id) && it.size > 0 }
            .map { it.id }
            .sorted()

    private fun activeHuntPools() =
        TreasureHuntManager.getActiveHunts().map { it.config.locationPoolId }.distinct().sorted()

    private fun chestModels() =
        (TreasureHuntRegistry.getAliases().filter { it.key.isNotBlank() && it.value.isNotBlank() }.keys + "vanilla")
            .distinct()
            .sorted()

    private fun rewardPools() = TreasureHuntManager.getTreasurePools().map { it.id }.sorted()

    private fun List<String>.filterPrefix(prefix: String): List<String> =
        filter { it.startsWith(prefix, ignoreCase = true) }
}
