package ru.arc.listeners

internal const val LEGACY_MONEY_ALIAS_ENABLED_PATH = "economy.legacy-money-alias.enabled"
internal const val LEGACY_MONEY_ADMIN_PERMISSION = "rediseconomy.admin"

internal enum class LegacyMoneyAction(val token: String) {
    GIVE("give"),
    TAKE("take"),
    SET("set"),
    ;

    companion object {
        val tokens: List<String> = entries.map(LegacyMoneyAction::token)

        fun parse(value: String?): LegacyMoneyAction? =
            entries.firstOrNull { it.token.equals(value, ignoreCase = true) }
    }
}

internal enum class LegacyMoneyCommandError {
    USAGE,
    INVALID_TARGET,
    INVALID_AMOUNT,
    NEGATIVE_AMOUNT,
    GIVE_ALL_REQUIRES_GIVE,
}

internal sealed interface LegacyMoneyCommandResult {
    data object NotLegacy : LegacyMoneyCommandResult

    data class Invalid(val error: LegacyMoneyCommandError) : LegacyMoneyCommandResult

    data class Valid(val command: LegacyMoneyCommand) : LegacyMoneyCommandResult
}

internal data class LegacyMoneyCommand(
    val action: LegacyMoneyAction,
    val target: String,
    val amount: Double,
) {
    val canonical: String
        get() = "money $target vault ${action.token} $amount"
}

private val playerNamePattern = Regex("\\.?[A-Za-z0-9_]{1,16}")
private val commandWhitespace = Regex("\\s+")
private val amountExamples = listOf("100", "1000", "10000")

internal fun tokenizeCommand(commandLine: String): List<String> {
    val normalized = commandLine.trim()
    if (normalized.isEmpty()) return emptyList()
    return normalized.split(commandWhitespace)
}

internal fun parseLegacyMoneyCommand(commandLine: String): LegacyMoneyCommandResult {
    val args = tokenizeCommand(commandLine)
    if (!args.firstOrNull().isMoneyRoot()) return LegacyMoneyCommandResult.NotLegacy

    val action = LegacyMoneyAction.parse(args.getOrNull(1)) ?: return LegacyMoneyCommandResult.NotLegacy
    if (args.size != 4) return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.USAGE)

    val target = args[2]
    if (target == "*" && action != LegacyMoneyAction.GIVE) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.GIVE_ALL_REQUIRES_GIVE)
    }
    if (target != "*" && !playerNamePattern.matches(target)) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_TARGET)
    }

    val amount = args[3].toDoubleOrNull()
    if (amount == null || !amount.isFinite()) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_AMOUNT)
    }
    if (action != LegacyMoneyAction.SET && amount < 0.0) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.NEGATIVE_AMOUNT)
    }

    return LegacyMoneyCommandResult.Valid(LegacyMoneyCommand(action, target, amount))
}

/**
 * Returns null when RedisEconomy's native grammar owns the current completion.
 * A non-null result replaces the event completions for the recognized legacy grammar.
 */
internal fun legacyMoneyCompletions(
    buffer: String,
    nativeCompletions: List<String>,
    playerNames: Collection<String>,
    allowGiveAll: Boolean,
): List<String>? {
    val args = tokenizeCompletion(buffer)
    if (!args.firstOrNull().isMoneyRoot() || args.size == 1) return null

    if (args.size == 2) {
        val prefix = args[1]
        val actionMatches = LegacyMoneyAction.tokens.filterByPrefix(prefix)
        return distinctCompletions(actionMatches + nativeCompletions)
    }

    val action = LegacyMoneyAction.parse(args[1]) ?: return null
    return when (args.size) {
        3 -> {
            val targets =
                buildList {
                    if (allowGiveAll && action == LegacyMoneyAction.GIVE) add("*")
                    addAll(playerNames.filter(playerNamePattern::matches).sortedWith(String.CASE_INSENSITIVE_ORDER))
                }
            distinctCompletions(targets.filterByPrefix(args[2]))
        }

        4 -> amountExamples.filterByPrefix(args[3])
        else -> emptyList()
    }
}

private fun tokenizeCompletion(buffer: String): List<String> {
    val normalized = buffer.trimStart()
    if (!normalized.startsWith('/')) return emptyList()

    val trailingWhitespace = normalized.lastOrNull()?.isWhitespace() == true
    val tokens = tokenizeCommand(normalized)
    return if (trailingWhitespace) tokens + "" else tokens
}

private fun String?.isMoneyRoot(): Boolean = this?.removePrefix("/")?.equals("money", ignoreCase = true) == true

private fun Iterable<String>.filterByPrefix(prefix: String): List<String> =
    filter { it.startsWith(prefix, ignoreCase = true) }

private fun distinctCompletions(values: Iterable<String>): List<String> =
    values.distinctBy(String::lowercase)
