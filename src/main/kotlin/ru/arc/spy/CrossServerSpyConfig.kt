package ru.arc.spy

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path
import java.util.Locale

data class CrossServerSpySettings(
    val enabled: Boolean,
    val channel: String,
    val allowedServers: Set<String>,
    val serverLabels: Map<String, String>,
    val maxPayloadBytes: Int,
    val maxContentLength: Int,
    val maxMessageAgeMillis: Long,
    val maxFutureSkewMillis: Long,
    val privateMessageCommands: Set<String>,
    val replyCommands: Set<String>,
    val sensitiveCommands: Set<String>,
    val commandTemplate: String,
    val privateMessageTemplate: String,
) {
    fun serverLabel(server: String): String = serverLabels[server] ?: server
}

open class CrossServerSpyConfig(
    private val config: Config,
) {
    open val settings: CrossServerSpySettings
        get() =
            CrossServerSpySettings(
                enabled = config.bool("enabled", true),
                channel = normalizeChannel(config.string("channel", DEFAULT_CHANNEL)),
                allowedServers =
                    normalizeTokens(
                        config.stringList("allowed-servers", listOf("spawn", "survival", "parkour")),
                        DEFAULT_ALLOWED_SERVERS,
                    ),
                serverLabels = parseServerLabels(config.stringList("server-labels", DEFAULT_SERVER_LABELS)),
                maxPayloadBytes = config.int("limits.max-payload-bytes", 8192).coerceIn(512, 16_384),
                maxContentLength = config.int("limits.max-content-length", 1000).coerceIn(64, 2000),
                maxMessageAgeMillis =
                    config.long("limits.max-message-age-millis", 15_000L).coerceIn(1000L, 120_000L),
                maxFutureSkewMillis =
                    config.long("limits.max-future-skew-millis", 2000L).coerceIn(0L, 10_000L),
                privateMessageCommands =
                    normalizeTokens(
                        config.stringList("private-messages.direct-commands", DEFAULT_DIRECT_COMMANDS.toList()),
                        DEFAULT_DIRECT_COMMANDS,
                    ),
                replyCommands =
                    normalizeTokens(
                        config.stringList("private-messages.reply-commands", DEFAULT_REPLY_COMMANDS.toList()),
                        DEFAULT_REPLY_COMMANDS,
                    ),
                sensitiveCommands =
                    normalizeCommandRules(
                        config.stringList("security.sensitive-commands", DEFAULT_SENSITIVE_COMMANDS.toList()),
                        DEFAULT_SENSITIVE_COMMANDS,
                    ),
                commandTemplate =
                    normalizeTemplate(
                        config.string("display.command", DEFAULT_COMMAND_TEMPLATE),
                        DEFAULT_COMMAND_TEMPLATE,
                        setOf("server", "sender", "content"),
                    ),
                privateMessageTemplate =
                    normalizeTemplate(
                        config.string("display.private-message", DEFAULT_PRIVATE_MESSAGE_TEMPLATE),
                        DEFAULT_PRIVATE_MESSAGE_TEMPLATE,
                        setOf("server", "sender", "target", "content"),
                    ),
            )

    companion object {
        const val DEFAULT_CHANNEL = "arc.spy.v1"
        val DEFAULT_ALLOWED_SERVERS = setOf("spawn", "survival", "parkour")
        val DEFAULT_DIRECT_COMMANDS = setOf("msg", "tell", "w", "whisper", "pm")
        val DEFAULT_REPLY_COMMANDS = setOf("reply", "r")
        val DEFAULT_SENSITIVE_COMMANDS =
            setOf(
                "l",
                "login",
                "register",
                "reg",
                "changepassword",
                "changepass",
                "password",
                "unregister",
                "2fa",
                "totp",
            )
        val DEFAULT_SERVER_LABELS = listOf("spawn=Спавн", "survival=Выживание", "parkour=Паркур")
        const val DEFAULT_COMMAND_TEMPLATE =
            "<dark_purple>К<dark_green>Шпион<gray>[<dark_gray><sender><gray>]" +
                "[<dark_gray><server><gray>]: <white><content>"
        const val DEFAULT_PRIVATE_MESSAGE_TEMPLATE =
            "<dark_green>Шпион<gray>[<dark_gray><sender> <gray>-> <dark_gray><target><gray>]" +
                "[<dark_gray><server><gray>] <white><content>"

        fun load(dataPath: Path): CrossServerSpyConfig =
            CrossServerSpyConfig(ConfigManager.ofModule(dataPath, "cross-server-spy.yml"))

        private fun normalizeChannel(raw: String): String =
            raw.trim().takeIf { CHANNEL_PATTERN.matches(it) } ?: DEFAULT_CHANNEL

        private fun normalizeTokens(raw: Collection<String>, fallback: Set<String>): Set<String> =
            raw
                .asSequence()
                .map(::normalizeToken)
                .filter { TOKEN_PATTERN.matches(it) }
                .toCollection(linkedSetOf())
                .ifEmpty { fallback }

        private fun parseServerLabels(entries: Collection<String>): Map<String, String> =
            buildMap {
                entries.forEach { entry ->
                    val separator = entry.indexOf('=')
                    if (separator <= 0) return@forEach
                    val server = normalizeToken(entry.substring(0, separator))
                    val label = entry.substring(separator + 1).trim()
                    if (TOKEN_PATTERN.matches(server) && label.length in 1..24 && label.none(Char::isISOControl)) {
                        put(server, label)
                    }
                }
            }

        private fun normalizeCommandRules(raw: Collection<String>, fallback: Set<String>): Set<String> =
            raw
                .asSequence()
                .mapNotNull(SpyRelayPolicy::normalizeCommand)
                .filter(COMMAND_RULE_PATTERN::matches)
                .toCollection(linkedSetOf())
                .ifEmpty { fallback }

        private fun normalizeTemplate(
            raw: String,
            fallback: String,
            requiredPlaceholders: Set<String>,
        ): String =
            raw.takeIf { candidate ->
                candidate.length in 1..512 &&
                    candidate.none(Char::isISOControl) &&
                    requiredPlaceholders.all { placeholder -> "<$placeholder>" in candidate }
            } ?: fallback

        internal fun normalizeToken(raw: String): String =
            raw.trim().removePrefix("/").lowercase(Locale.ROOT)

        private val CHANNEL_PATTERN = Regex("[a-zA-Z0-9._:-]{1,64}")
        private val TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9_:-]{0,63}")
        private val COMMAND_RULE_PATTERN = Regex("[a-z0-9_.:+-]+(?: [a-z0-9_.:+-]+)*")
    }
}

class TestCrossServerSpyConfig(
    override val settings: CrossServerSpySettings,
) : CrossServerSpyConfig(EmptyConfig)
