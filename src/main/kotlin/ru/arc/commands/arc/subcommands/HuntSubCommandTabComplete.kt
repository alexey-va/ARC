package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.tabComplete
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry

/**
 * Контекстные подсказки для /arc hunt — понимает ветку команды и предлагает релевантные значения.
 */
internal object HuntSubCommandTabComplete {
    private const val CUSTOM = "custom"
    private const val GENERATE = "generate"
    private val HERE_TOKENS = setOf("here", "@here")
    private val RADIUS_HINTS = listOf("50", "80", "100", "150", "200")
    private val CHEST_HINTS = listOf("5", "10", "15", "20", "30", "50")
    private val PRIORITY_CHEST_MODELS = listOf("vanilla", "pumpkin_1", "pumpkin_2", "easter", "skull_in_jar")

    fun complete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? {
        if (args.isEmpty()) return null

        val partial = args.last()
        val stepIndex = args.size - 1

        return when (val branch = resolveBranch(args)) {
            HuntBranch.TopLevel -> topLevel(partial)
            HuntBranch.StartChooseMode -> startChooseMode(partial)
            is HuntBranch.StartPreset -> presetChestHints(branch.presetId, partial)
            HuntBranch.StartCustomRoot -> customRoot(partial)
            is HuntBranch.CustomPool -> customPool(branch.poolId, stepIndex, partial)
            HuntBranch.CustomGenerateAnchor -> generateAnchor(sender, partial)
            HuntBranch.CustomGenerateHere -> generateHere(stepIndex, partial)
            is HuntBranch.CustomGenerateCoords -> generateCoords(sender, stepIndex, partial)
            is HuntBranch.Stop -> branch.activePools.tabComplete(partial)
        }?.takeIf { it.isNotEmpty() }
    }

    private sealed class HuntBranch {
        data object TopLevel : HuntBranch()

        data object StartChooseMode : HuntBranch()

        data class StartPreset(
            val presetId: String,
        ) : HuntBranch()

        data object StartCustomRoot : HuntBranch()

        data class CustomPool(
            val poolId: String,
        ) : HuntBranch()

        data object CustomGenerateAnchor : HuntBranch()

        data object CustomGenerateHere : HuntBranch()

        data object CustomGenerateCoords : HuntBranch()

        data class Stop(
            val activePools: List<String>,
        ) : HuntBranch()
    }

    private fun resolveBranch(args: Array<String>): HuntBranch =
        when (args[0].lowercase()) {
            "start" -> resolveStartBranch(args)
            "stop" -> HuntBranch.Stop(activeHuntLocationPools())
            else -> HuntBranch.TopLevel
        }

    private fun resolveStartBranch(args: Array<String>): HuntBranch {
        if (args.size == 1) return HuntBranch.StartChooseMode

        val second = args.getOrNull(1).orEmpty()
        if (!second.equals(CUSTOM, ignoreCase = true)) {
            TreasureHuntManager.getTreasureHuntType(second)?.let { return HuntBranch.StartPreset(second) }
            return HuntBranch.StartChooseMode
        }

        return resolveCustomBranch(args)
    }

    private fun resolveCustomBranch(args: Array<String>): HuntBranch {
        if (args.size <= 2) return HuntBranch.StartCustomRoot

        val third = args.getOrNull(2).orEmpty()
        if (third.isBlank()) return HuntBranch.StartCustomRoot

        if (matchesGenerateToken(third)) {
            if (!third.equals(GENERATE, ignoreCase = true)) {
                return HuntBranch.StartCustomRoot
            }

            val anchor = args.getOrNull(3)?.lowercase().orEmpty()
            if (args.size == 3) return HuntBranch.StartCustomRoot

            if (args.size == 4) {
                return HuntBranch.CustomGenerateAnchor
            }

            if (anchor in HERE_TOKENS) {
                return HuntBranch.CustomGenerateHere
            }

            return HuntBranch.CustomGenerateCoords
        }

        return HuntBranch.CustomPool(third)
    }

    private fun topLevel(partial: String): List<String> = listOf("status", "types", "start", "stop", "stopall").tabComplete(partial)

    private fun startChooseMode(partial: String): List<String> {
        val presets = TreasureHuntManager.getTreasureHuntTypes()
        return (presets + CUSTOM).tabComplete(partial)
    }

    private fun presetChestHints(
        presetId: String,
        partial: String,
    ): List<String> {
        val poolSize =
            TreasureHuntManager
                .getTreasureHuntType(presetId)
                ?.getLocationPool()
                ?.size
                ?.takeIf { it > 0 }
                ?.toString()

        return listOfNotNull(poolSize).plus(CHEST_HINTS).distinct().tabComplete(partial)
    }

    private fun customRoot(partial: String): List<String> {
        if (partial.isNotBlank() && (matchesGenerateToken(partial) || partial.startsWith("g", ignoreCase = true))) {
            return listOf(GENERATE).tabComplete(partial)
        }

        val pools = persistentLocationPools()
        val suggestions =
            if (partial.isBlank()) {
                listOf(GENERATE) + pools.sorted()
            } else {
                pools.filter { it.startsWith(partial, ignoreCase = true) } +
                    listOf(GENERATE).filter { GENERATE.startsWith(partial, ignoreCase = true) }
            }

        return suggestions.distinct().tabComplete(partial)
    }

    private fun customPool(
        poolId: String,
        stepIndex: Int,
        partial: String,
    ): List<String>? =
        when (stepIndex) {
            3 -> chestCountHints(poolId).tabComplete(partial)
            4 -> chestModels().tabComplete(partial)
            5 -> treasurePools().tabComplete(partial)
            else -> null
        }

    private fun generateAnchor(
        sender: CommandSender,
        partial: String,
    ): List<String> {
        val suggestions = mutableListOf("here", "@here")
        (sender as? Player)
            ?.location
            ?.blockX
            ?.toString()
            ?.let { suggestions += it }
        return suggestions.distinct().tabComplete(partial)
    }

    private fun generateHere(
        stepIndex: Int,
        partial: String,
    ): List<String>? =
        when (stepIndex) {
            4 -> RADIUS_HINTS.tabComplete(partial)
            5 -> CHEST_HINTS.tabComplete(partial)
            6 -> chestModels().tabComplete(partial)
            7 -> treasurePools().tabComplete(partial)
            else -> null
        }

    private fun generateCoords(
        sender: CommandSender,
        stepIndex: Int,
        partial: String,
    ): List<String>? {
        val player = sender as? Player
        return when (stepIndex) {
            3 -> coordHint(player?.location?.blockX?.toString(), partial)
            4 -> coordHint(player?.location?.blockY?.toString(), partial)
            5 -> coordHint(player?.location?.blockZ?.toString(), partial)
            6 -> RADIUS_HINTS.tabComplete(partial)
            7 -> CHEST_HINTS.tabComplete(partial)
            8 -> chestModels().tabComplete(partial)
            9 -> treasurePools().tabComplete(partial)
            else -> null
        }
    }

    private fun coordHint(
        playerValue: String?,
        partial: String,
    ): List<String> {
        val hints = listOfNotNull(playerValue)
        return if (hints.isEmpty()) emptyList() else hints.tabComplete(partial)
    }

    private fun chestCountHints(poolId: String): List<String> {
        val poolSize =
            LocationPoolManager
                .getPool(poolId)
                ?.size
                ?.takeIf { it > 0 }
                ?.toString()
        return listOfNotNull(poolSize).plus(CHEST_HINTS).distinct()
    }

    private fun chestModels(): List<String> {
        val aliases = TreasureHuntRegistry.getAliases().keys
        return (PRIORITY_CHEST_MODELS + aliases + listOf("vanilla")).distinct()
    }

    private fun treasurePools(): List<String> = TreasureHuntManager.getTreasurePools().map { it.id }.sorted()

    private fun persistentLocationPools(): List<String> =
        LocationPoolManager
            .getAll()
            .map { it.id }
            .filter { !LocationPoolManager.isEphemeralPool(it) }

    private fun activeHuntLocationPools(): List<String> =
        TreasureHuntManager
            .getActiveHunts()
            .map { it.config.locationPoolId }
            .distinct()
            .sorted()

    private fun matchesGenerateToken(token: String): Boolean =
        token.equals(GENERATE, ignoreCase = true) ||
            (token.isNotBlank() && token.length < GENERATE.length && GENERATE.startsWith(token, ignoreCase = true))
}
