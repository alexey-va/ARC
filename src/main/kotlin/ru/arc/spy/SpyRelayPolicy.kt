package ru.arc.spy

import ru.arc.chat.ChatMode
import java.util.Locale
import java.util.UUID

data class PrivateSpyMessage(
    val targetName: String,
    val content: String,
)

object SpyRelayPolicy {
    // This floor cannot be removed by an operator-supplied observation blacklist.
    val mandatorySensitiveCommands: Set<String> = CrossServerSpyConfig.DEFAULT_SENSITIVE_COMMANDS + setOf(
        "auth", "authme", "limboauth", "nlogin", "resetpassword", "resetpass", "setpassword", "setpass",
        "forcepassword", "forcelogin", "forceregister", "usermeta", "token", "secret", "apikey", "api-key",
        "execute", "sudo", "runas", "eval", "dispatch", "script", "skript", "denizen", "ex", "exs",
    )

    fun shouldPublishLocalChat(
        content: String,
        mode: ChatMode,
        senderHidden: Boolean,
    ): Boolean =
        !senderHidden &&
            mode == ChatMode.LOCAL &&
            !content.startsWith('!')

    fun shouldPublishCommand(
        command: String,
        senderHidden: Boolean,
        cmiBlacklisted: Collection<String>,
        sensitiveCommands: Collection<String>,
    ): Boolean {
        if (senderHidden) return false
        val normalized = normalizeCommand(command) ?: return false
        return (cmiBlacklisted + sensitiveCommands + mandatorySensitiveCommands).none { matchesCommand(normalized, it) }
    }

    fun commandVisibleToRestrictedViewer(command: String, commandList: Collection<String>): Boolean {
        if (commandList.isEmpty()) return true
        val normalized = normalizeCommand(command) ?: return false
        return commandList.any { matchesCommand(normalized, it) }
    }

    fun shouldDeliver(
        message: SpyRelayMessage,
        viewerUuid: UUID,
        viewerName: String,
        chatSpyEnabled: Boolean,
        commandSpyEnabled: Boolean,
        canSeeUnlistedCommands: Boolean,
        commandList: Collection<String>,
    ): Boolean {
        if (viewerUuid == message.senderUuid) return false
        return when (message.type) {
            SpyRelayType.CHAT ->
                chatSpyEnabled &&
                    message.targetUuid != viewerUuid &&
                    !message.targetName.equals(viewerName, ignoreCase = true)
            SpyRelayType.COMMAND ->
                commandSpyEnabled &&
                    (
                        canSeeUnlistedCommands ||
                            commandVisibleToRestrictedViewer(message.content, commandList)
                    )
        }
    }

    fun parsePrivateMessage(
        commandLine: String,
        directCommands: Set<String>,
        replyCommands: Set<String>,
        replyTarget: () -> String?,
    ): PrivateSpyMessage? {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return null

        val first = unnamespace(tokens[0].removePrefix("/").lowercase(Locale.ROOT))
        val commandIndex = if (first == "cmi") 1 else 0
        if (commandIndex >= tokens.size) return null
        val command = unnamespace(tokens[commandIndex].removePrefix("/").lowercase(Locale.ROOT))

        val target: String
        val messageStart: Int
        when (command) {
            in directCommands -> {
                if (tokens.size <= commandIndex + 2) return null
                target = tokens[commandIndex + 1]
                messageStart = commandIndex + 2
            }
            in replyCommands -> {
                if (tokens.size <= commandIndex + 1) return null
                target = replyTarget() ?: return null
                messageStart = commandIndex + 1
            }
            else -> return null
        }
        if (!PLAYER_NAME.matches(target)) return null
        val rawMessage = tokens.drop(messageStart).joinToString(" ")
        val message =
            when {
                rawMessage.startsWith("!!") -> rawMessage.drop(2)
                rawMessage.startsWith('!') -> rawMessage.drop(1)
                else -> rawMessage
            }.trim()
        if (message.isEmpty()) return null
        return PrivateSpyMessage(target, message)
    }

    internal fun normalizeCommand(raw: String): String? {
        if (raw.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        val tokens = tokenize(raw.removePrefix("/").lowercase(Locale.ROOT)).toMutableList()
        if (tokens.isEmpty()) return null
        tokens[0] = unnamespace(tokens[0].removePrefix("/"))
        if (tokens[0] == "cmi" && tokens.size > 1) tokens[1] = unnamespace(tokens[1].removePrefix("/"))
        return tokens.joinToString(" ")
    }

    internal fun matchesCommand(normalizedCommand: String, configured: String): Boolean {
        val normalizedRule = normalizeCommand(configured) ?: return false
        // CMI's bare aliases and /cmi <command> enter the same command owner.
        val command = normalizedCommand.removePrefix("cmi ")
        val rule = normalizedRule.removePrefix("cmi ")
        return command == rule || command.startsWith("$rule ") ||
            normalizedCommand == normalizedRule || normalizedCommand.startsWith("$normalizedRule ")
    }

    private fun tokenize(commandLine: String): List<String> =
        commandLine.trim().split(WHITESPACE).filter(String::isNotEmpty)

    private fun unnamespace(token: String): String = token.substringAfter(':', token)

    private val WHITESPACE = Regex("\\s+")
    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
}
