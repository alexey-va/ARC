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

internal data class CommandTreeMatch(
    val blocksSubtree: Boolean,
    val blocksExactPath: Boolean,
)

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
    val patternCount: Int,
) {
    fun matches(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return root.terminal || root.remainingWords

        var active = listOf(root)
        for (token in tokens) {
            if (active.any(Node::remainingWords)) return true

            val next = ArrayList<Node>(active.size * 2)
            for (node in active) {
                node.literalChildren[token]?.let(next::add)
                node.singleWordChild?.let(next::add)
            }
            if (next.isEmpty()) return false
            active = next
        }

        return active.any { it.terminal || it.remainingWords }
    }

    /**
     * Matches one Brigadier path through the compiled trie. The old implementation
     * scanned every configured pattern for every node in the command tree, which
     * made command refresh cost grow with both tree size and policy size.
     */
    fun matchTreePath(path: List<CommandTreeToken>): CommandTreeMatch {
        var active = listOf(root)
        if (root.remainingWords) return CommandTreeMatch(blocksSubtree = true, blocksExactPath = false)

        for (token in path) {
            val next = ArrayList<Node>(active.size * 2)
            for (node in active) {
                when (token) {
                    is CommandTreeToken.Literal -> node.literalChildren[token.value]?.let(next::add)
                    CommandTreeToken.Argument -> Unit
                }
                node.singleWordChild?.let(next::add)
            }
            if (next.isEmpty()) return CommandTreeMatch(blocksSubtree = false, blocksExactPath = false)
            if (next.any(Node::remainingWords)) {
                return CommandTreeMatch(blocksSubtree = true, blocksExactPath = false)
            }
            active = next
        }

        return CommandTreeMatch(
            blocksSubtree = false,
            blocksExactPath = active.any(Node::terminal),
        )
    }

    fun blocksSubtree(path: List<CommandTreeToken>): Boolean = matchTreePath(path).blocksSubtree

    fun blocksExactTreePath(path: List<CommandTreeToken>): Boolean = matchTreePath(path).blocksExactPath

    fun blocksRootSubtree(commandLabel: String): Boolean {
        if (root.remainingWords) return true
        return root.literalChildren[commandLabel]?.remainingWords == true ||
            root.singleWordChild?.remainingWords == true
    }

    private class Node {
        val literalChildren = HashMap<String, Node>()
        var singleWordChild: Node? = null
        var terminal: Boolean = false
        var remainingWords: Boolean = false
    }

    companion object {
        val EMPTY = CommandPatternIndex(Node(), 0)

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
                patternCount = distinct.size,
            )
        }
    }
}

internal class CommandHidePolicy(
    patterns: Collection<CommandPattern>,
    val stripCommandNamespace: Boolean,
    private val hideNamespacedRoots: Boolean,
    val blockedMessage: Component?,
) {
    private val index = CommandPatternIndex.compile(patterns)

    val patternCount: Int get() = index.patternCount

    val isEmpty: Boolean get() = patternCount == 0 && !hideNamespacedRoots

    fun blocks(command: String): Boolean = blocksTokens(tokenizeCommand(command, stripCommandNamespace))

    fun blocksTokens(tokens: List<String>): Boolean = index.matches(normalizeCommandTokens(tokens, stripCommandNamespace))

    fun blocksSubtree(path: List<CommandTreeToken>): Boolean = index.blocksSubtree(path)

    fun blocksExactTreePath(path: List<CommandTreeToken>): Boolean = index.blocksExactTreePath(path)

    fun matchTreePath(path: List<CommandTreeToken>): CommandTreeMatch = index.matchTreePath(path)

    fun hidesRoot(commandLabel: String): Boolean =
        (hideNamespacedRoots && ':' in commandLabel) ||
            index.blocksRootSubtree(normalizeLiteral(commandLabel, stripCommandNamespace))

    fun filterCompletions(
        buffer: String,
        completions: List<String>,
    ): List<String> {
        return completions.filterNot { completion ->
            val namespacedRoot =
                hideNamespacedRoots &&
                    completionCandidate(buffer, completion, stripCommandNamespace = false)
                        ?.firstOrNull()
                        ?.contains(':') == true
            namespacedRoot ||
                completionCandidate(buffer, completion, stripCommandNamespace)?.let(::blocksTokens) == true
        }
    }

    companion object {
        fun empty(stripCommandNamespace: Boolean = true): CommandHidePolicy =
            CommandHidePolicy(emptyList(), stripCommandNamespace, hideNamespacedRoots = false, blockedMessage = null)
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
