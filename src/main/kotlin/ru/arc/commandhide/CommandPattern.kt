package ru.arc.commandhide

import net.kyori.adventure.text.Component
import java.util.Locale

internal sealed interface PatternToken {
    data class Literal(
        val value: String,
    ) : PatternToken

    data object SingleWord : PatternToken

    data object RemainingWords : PatternToken
}

internal sealed interface CommandTreeToken {
    data class Literal(
        val value: String,
    ) : CommandTreeToken

    data object Argument : CommandTreeToken
}

internal data class CommandPattern(
    val tokens: List<PatternToken>,
) {
    val canonical: String =
        tokens.joinToString(" ") { token ->
            when (token) {
                is PatternToken.Literal -> token.value
                PatternToken.SingleWord -> "*"
                PatternToken.RemainingWords -> "**"
            }
        }

    val subtreePrefix: List<PatternToken>?
        get() = if (tokens.lastOrNull() == PatternToken.RemainingWords) tokens.dropLast(1) else null

    companion object {
        fun parse(
            source: String,
            stripCommandNamespace: Boolean,
        ): CommandPattern {
            val rawTokens = rawCommandTokens(source)
            require(rawTokens.isNotEmpty()) { "Command pattern must not be blank" }

            val tokens =
                rawTokens.mapIndexed { index, raw ->
                    when (raw) {
                        "*" -> PatternToken.SingleWord
                        "**" -> PatternToken.RemainingWords
                        else -> {
                            require('*' !in raw) {
                                "Wildcard must occupy a complete word in command pattern '$source'"
                            }
                            val normalized = normalizeLiteral(raw, index == 0 && stripCommandNamespace)
                            require(normalized.isNotEmpty()) { "Command pattern '$source' contains an empty word" }
                            PatternToken.Literal(normalized)
                        }
                    }
                }

            val remainingIndex = tokens.indexOf(PatternToken.RemainingWords)
            require(remainingIndex < 0 || remainingIndex == tokens.lastIndex) {
                "** must be the final word in command pattern '$source'"
            }

            return CommandPattern(tokens = tokens)
        }
    }
}

/**
 * Immutable token trie used for command execution and server-side completion checks.
 * Literal and single-word edges are followed without regular expressions or backtracking.
 */
internal class CommandPatternIndex private constructor(
    private val root: Node,
    private val subtreePrefixes: List<List<PatternToken>>,
    private val exactTreePatterns: List<List<PatternToken>>,
    val patternCount: Int,
) {
    fun matches(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return root.terminal || root.remainingWords

        var active = listOf(root)
        for (token in tokens) {
            if (active.any(Node::remainingWords)) return true

            val next = LinkedHashSet<Node>(active.size * 2)
            for (node in active) {
                node.literalChildren[token]?.let(next::add)
                node.singleWordChild?.let(next::add)
            }
            if (next.isEmpty()) return false
            active = next.toList()
        }

        return active.any { it.terminal || it.remainingWords }
    }

    /**
     * Returns true only when every command below [path] is blocked by a pattern ending in `**`.
     * This makes it safe to remove the corresponding Brigadier node and all of its descendants.
     */
    fun blocksSubtree(path: List<CommandTreeToken>): Boolean =
        subtreePrefixes.any { prefix ->
            if (prefix.size > path.size) return@any false
            prefix.indices.all { index -> prefix[index].covers(path[index]) }
        }

    /** Returns true when the exact tree path is blocked for every value represented by argument nodes. */
    fun blocksExactTreePath(path: List<CommandTreeToken>): Boolean =
        exactTreePatterns.any { pattern ->
            pattern.size == path.size && pattern.indices.all { index -> pattern[index].covers(path[index]) }
        }

    private fun PatternToken.covers(treeToken: CommandTreeToken): Boolean =
        when (this) {
            is PatternToken.Literal -> treeToken is CommandTreeToken.Literal && value == treeToken.value
            PatternToken.SingleWord -> true
            PatternToken.RemainingWords -> true
        }

    private class Node {
        val literalChildren = HashMap<String, Node>()
        var singleWordChild: Node? = null
        var terminal: Boolean = false
        var remainingWords: Boolean = false
    }

    companion object {
        val EMPTY = CommandPatternIndex(Node(), emptyList(), emptyList(), 0)

        fun compile(patterns: Collection<CommandPattern>): CommandPatternIndex {
            val distinct = patterns.distinctBy(CommandPattern::canonical)
            if (distinct.isEmpty()) return EMPTY

            val root = Node()
            for (pattern in distinct) {
                var node = root
                for (token in pattern.tokens) {
                    when (token) {
                        is PatternToken.Literal ->
                            node.literalChildren.getOrPut(token.value) { Node() }.also { node = it }

                        PatternToken.SingleWord ->
                            (node.singleWordChild ?: Node().also { node.singleWordChild = it }).also { node = it }

                        PatternToken.RemainingWords -> node.remainingWords = true
                    }
                }
                if (pattern.tokens.lastOrNull() != PatternToken.RemainingWords) {
                    node.terminal = true
                }
            }

            return CommandPatternIndex(
                root = root,
                subtreePrefixes = distinct.mapNotNull(CommandPattern::subtreePrefix),
                exactTreePatterns = distinct.filter { it.subtreePrefix == null }.map(CommandPattern::tokens),
                patternCount = distinct.size,
            )
        }
    }
}

internal class CommandHidePolicy(
    patterns: Collection<CommandPattern>,
    val stripCommandNamespace: Boolean,
    val blockedMessage: Component?,
) {
    private val index = CommandPatternIndex.compile(patterns)

    val patternCount: Int get() = index.patternCount

    val isEmpty: Boolean get() = patternCount == 0

    fun blocks(command: String): Boolean = blocksTokens(tokenizeCommand(command, stripCommandNamespace))

    fun blocksTokens(tokens: List<String>): Boolean = index.matches(normalizeCommandTokens(tokens, stripCommandNamespace))

    fun blocksSubtree(path: List<CommandTreeToken>): Boolean = index.blocksSubtree(path)

    fun blocksExactTreePath(path: List<CommandTreeToken>): Boolean = index.blocksExactTreePath(path)

    fun hidesRoot(commandLabel: String): Boolean =
        blocksSubtree(
            listOf(
                CommandTreeToken.Literal(normalizeLiteral(commandLabel, stripCommandNamespace)),
            ),
        )

    fun filterCompletions(
        buffer: String,
        completions: List<String>,
    ): List<String> {
        return completions.filterNot { completion ->
            completionCandidate(buffer, completion, stripCommandNamespace)?.let(::blocksTokens) == true
        }
    }

    companion object {
        fun empty(stripCommandNamespace: Boolean = true): CommandHidePolicy =
            CommandHidePolicy(emptyList(), stripCommandNamespace, null)
    }
}

internal fun tokenizeCommand(
    command: String,
    stripCommandNamespace: Boolean,
): List<String> = normalizeCommandTokens(rawCommandTokens(command), stripCommandNamespace)

internal fun normalizeCommandTokens(
    tokens: List<String>,
    stripCommandNamespace: Boolean,
): List<String> =
    tokens.mapIndexed { index, token -> normalizeLiteral(token, index == 0 && stripCommandNamespace) }

internal fun normalizeTreeLiteral(
    value: String,
    root: Boolean,
    stripCommandNamespace: Boolean,
): String = normalizeLiteral(value, root && stripCommandNamespace)

private fun rawCommandTokens(command: String): List<String> {
    if (command.isEmpty()) return emptyList()

    val result = ArrayList<String>(4)
    var index = 0
    while (index < command.length && command[index].isWhitespace()) index++
    if (index < command.length && command[index] == '/') index++

    while (index < command.length) {
        while (index < command.length && command[index].isWhitespace()) index++
        if (index >= command.length) break

        val start = index
        while (index < command.length && !command[index].isWhitespace()) index++
        result += command.substring(start, index)
    }
    return result
}

private fun normalizeLiteral(
    raw: String,
    stripNamespace: Boolean,
): String {
    val normalized = raw.lowercase(Locale.ROOT)
    return if (stripNamespace) normalized.substringAfterLast(':') else normalized
}

private fun completionCandidate(
    buffer: String,
    completion: String,
    stripCommandNamespace: Boolean,
): List<String>? {
    val command = buffer.trimStart()
    if (!command.startsWith('/')) return null

    if (completion.trimStart().startsWith('/')) {
        return tokenizeCommand(completion, stripCommandNamespace)
    }

    val content = command.removePrefix("/")
    val append = content.lastOrNull()?.isWhitespace() == true
    val base = rawCommandTokens(content)
    val suggestion = rawCommandTokens(completion)
    if (suggestion.isEmpty()) return null

    val combined =
        when {
            append -> base + suggestion
            base.isEmpty() -> suggestion
            else -> base.dropLast(1) + suggestion
        }
    return normalizeCommandTokens(combined, stripCommandNamespace)
}
