package ru.arc.listeners

internal const val LEGACY_MONEY_ALIAS_ENABLED_PATH = "economy.legacy-money-alias.enabled"
internal const val LEGACY_MONEY_ADMIN_PERMISSION = "rediseconomy.admin"
internal const val LEGACY_MONEY_DEFAULT_CURRENCY = "vault"

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
    INVALID_CURRENCY,
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
    val currency: String = LEGACY_MONEY_DEFAULT_CURRENCY,
) {
    val canonical: String
        get() = "money $target $currency ${action.token} $amount"
}

private val playerNamePattern = Regex("\\.?[A-Za-z0-9_]{1,16}")
private val currencyNamePattern = Regex("[A-Za-z0-9_-]{1,64}")
private val commandWhitespace = Regex("\\s+")
private val amountExamples = listOf("100", "1000", "10000")

internal fun tokenizeCommand(commandLine: String): List<String> {
    val normalized = commandLine.trim()
    if (normalized.isEmpty()) return emptyList()
    return normalized.split(commandWhitespace)
}

internal fun parseLegacyMoneyCommand(
    commandLine: String,
    currencyNames: Collection<String> = listOf(LEGACY_MONEY_DEFAULT_CURRENCY),
): LegacyMoneyCommandResult {
    val args = tokenizeCommand(commandLine)
    if (!args.firstOrNull().isMoneyRoot()) return LegacyMoneyCommandResult.NotLegacy

    val action = LegacyMoneyAction.parse(args.getOrNull(1)) ?: return LegacyMoneyCommandResult.NotLegacy
    if (args.size !in 4..5) return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.USAGE)

    val target = args[2]
    if (target == "*" && action != LegacyMoneyAction.GIVE) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.GIVE_ALL_REQUIRES_GIVE)
    }
    if (target != "*" && !playerNamePattern.matches(target)) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_TARGET)
    }

    val currencies = normalizedCurrencyNames(currencyNames)
    val (amountToken, currency) =
        if (args.size == 4) {
            args[3] to LEGACY_MONEY_DEFAULT_CURRENCY
        } else {
            val currencyBeforeAmount = currencies.resolve(args[3])
            val currencyAfterAmount = currencies.resolve(args[4])
            when {
                currencyBeforeAmount != null -> args[4] to currencyBeforeAmount
                currencyAfterAmount != null -> args[3] to currencyAfterAmount
                else -> return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_CURRENCY)
            }
        }

    val amount = amountToken.toDoubleOrNull()
    if (amount == null || !amount.isFinite()) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_AMOUNT)
    }
    if (action != LegacyMoneyAction.SET && amount < 0.0) {
        return LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.NEGATIVE_AMOUNT)
    }

    return LegacyMoneyCommandResult.Valid(LegacyMoneyCommand(action, target, amount, currency))
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
    currencyNames: Collection<String> = listOf(LEGACY_MONEY_DEFAULT_CURRENCY),
): List<String>? {
    val args = tokenizeCompletion(buffer)
    if (!args.firstOrNull().isMoneyRoot() || args.size == 1) return null

    if (args.size == 2) {
        val prefix = args[1]
        val actionMatches = LegacyMoneyAction.tokens.filterByPrefix(prefix)
        return distinctCompletions(actionMatches + nativeCompletions)
    }

    val action = LegacyMoneyAction.parse(args[1]) ?: return null
    val currencies = normalizedCurrencyNames(currencyNames)
    return when (args.size) {
        3 -> {
            val targets =
                buildList {
                    if (allowGiveAll && action == LegacyMoneyAction.GIVE) add("*")
                    addAll(playerNames.filter(playerNamePattern::matches).sortedWith(String.CASE_INSENSITIVE_ORDER))
                }
            distinctCompletions(targets.filterByPrefix(args[2]))
        }

        4 -> distinctCompletions((currencies + amountExamples).filterByPrefix(args[3]))
        5 -> {
            val previous = args[3]
            if (currencies.resolve(previous) != null) {
                amountExamples.filterByPrefix(args[4])
            } else if (previous.toDoubleOrNull()?.isFinite() == true) {
                currencies.filterByPrefix(args[4])
            } else {
                emptyList()
            }
        }
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

private fun normalizedCurrencyNames(currencyNames: Collection<String>): List<String> =
    distinctCompletions(
        listOf(LEGACY_MONEY_DEFAULT_CURRENCY) + currencyNames.filter(currencyNamePattern::matches),
    )

private fun Collection<String>.resolve(value: String): String? =
    firstOrNull { it.equals(value, ignoreCase = true) }

private fun distinctCompletions(values: Iterable<String>): List<String> =
    values.distinctBy(String::lowercase)
