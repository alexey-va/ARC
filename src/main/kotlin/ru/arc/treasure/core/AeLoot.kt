package ru.arc.treasure.core

import java.util.concurrent.ThreadLocalRandom

/**
 * Rolls AdvancedEnchantments arguments for native AE treasure types.
 * Tier weights match legacy Denizen [parse_ae_arg] rlevel distribution.
 */
object AeLoot {
    private val TIER_THRESHOLDS =
        listOf(
            0.50 to "SIMPLE",
            0.65 to "UNIQUE",
            0.79 to "ELITE",
            0.88 to "ULTIMATE",
            0.97 to "LEGENDARY",
            1.00 to "FABLED",
        )

    private val SLOT_VALUES = listOf("ARMOR", "TOOL", "WEAPON")

    fun randomTier(): String {
        val roll = ThreadLocalRandom.current().nextDouble()
        return TIER_THRESHOLDS.first { roll < it.first }.second
    }

    fun randomSlot(): String {
        val index = ThreadLocalRandom.current().nextInt(SLOT_VALUES.size)
        return SLOT_VALUES[index]
    }

    fun rollInt(
        min: Int,
        max: Int,
    ): Int {
        if (min >= max) return min
        return ThreadLocalRandom.current().nextInt(min, max + 1)
    }

    fun resolveArg(arg: AeArg): String =
        when (arg) {
            AeArg.RandomTier -> randomTier()
            AeArg.RandomSlot -> randomSlot()
            is AeArg.IntRange -> rollInt(arg.min, arg.max).toString()
        }

    fun buildCommand(
        playerName: String,
        treasure: Treasure.Ae,
    ): String {
        val resolvedArgs = treasure.args.map { resolveArg(it) }
        return when (treasure.kind) {
            AeKind.ITEM -> {
                val name = treasure.itemName ?: error("AE item name required")
                buildString {
                    append("ae giveitem ")
                    append(playerName)
                    append(' ')
                    append(name)
                    append(' ')
                    append(treasure.amount)
                    resolvedArgs.forEach { arg ->
                        append(' ')
                        append(arg)
                    }
                }
            }

            AeKind.RANDOM_BOOK -> {
                buildString {
                    append("ae giverandombook ")
                    append(playerName)
                    resolvedArgs.forEach { arg ->
                        append(' ')
                        append(arg)
                    }
                }
            }
        }
    }
}

enum class AeKind {
    ITEM,
    RANDOM_BOOK,
}

sealed class AeArg {
    data object RandomTier : AeArg()

    data object RandomSlot : AeArg()

    data class IntRange(
        val min: Int,
        val max: Int,
    ) : AeArg()

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parseList(raw: Any?): List<AeArg> {
            val entries = raw as? List<*> ?: return emptyList()
            return entries.mapNotNull { entry ->
                when (entry) {
                    is Map<*, *> -> parseEntry(entry as Map<String, Any?>)
                    is String -> parseIntRangeString(entry)?.let { IntRange(it.first, it.second) }
                    else -> null
                }
            }
        }

        private fun parseEntry(map: Map<String, Any?>): AeArg? {
            when {
                map["tier"] == "random" -> return RandomTier
                map["slot"] == "random" -> return RandomSlot
                map.containsKey("int") -> {
                    val range = parseIntValue(map["int"]) ?: return null
                    return IntRange(range.first, range.second)
                }
            }
            return null
        }

        private fun parseIntValue(value: Any?): Pair<Int, Int>? =
            when (value) {
                is Number -> {
                    val intVal = value.toInt()
                    intVal to intVal
                }

                is String -> parseIntRangeString(value)

                else -> null
            }

        private fun parseIntRangeString(value: String): Pair<Int, Int>? {
            val trimmed = value.trim()
            if (trimmed.contains('-')) {
                val parts = trimmed.split('-', limit = 2)
                val min = parts[0].trim().toIntOrNull() ?: return null
                val max = parts[1].trim().toIntOrNull() ?: return null
                return min to max
            }
            val single = trimmed.toIntOrNull() ?: return null
            return single to single
        }

        fun toMapList(args: List<AeArg>): List<Map<String, Any>> =
            args.map { arg ->
                when (arg) {
                    RandomTier -> mapOf("tier" to "random")
                    RandomSlot -> mapOf("slot" to "random")
                    is IntRange ->
                        if (arg.min == arg.max) {
                            mapOf("int" to arg.min)
                        } else {
                            mapOf("int" to "${arg.min}-${arg.max}")
                        }
                }
            }
    }
}
