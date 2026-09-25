package ru.arc.chat

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

data class ChatMessageColorVariation(
    val enabled: Boolean,
    val hueAmplitudeDegrees: Double,
) {
    companion object {
        val DISABLED = ChatMessageColorVariation(enabled = false, hueAmplitudeDegrees = 0.0)
    }
}

open class ChatModeConfig(
    private val config: Config,
) {
    open val messageColorVariation: ChatMessageColorVariation
        get() =
            ChatMessageColorVariation(
                enabled = config.bool("message-color-variation.enabled", true),
                hueAmplitudeDegrees =
                    config
                        .double("message-color-variation.hue-amplitude-degrees", DEFAULT_HUE_AMPLITUDE_DEGREES)
                        .takeIf(Double::isFinite)
                        ?.coerceIn(0.0, MAX_HUE_AMPLITUDE_DEGREES)
                        ?: DEFAULT_HUE_AMPLITUDE_DEGREES,
            )

    open val glyphUnauthorizedMessage: String
        get() = config.string("glyph-protection.messages.unauthorized", "<red>Этот символ недоступен вашему рангу.")
    open val glyphTechnicalMessage: String
        get() = config.string("glyph-protection.messages.technical", "<red>Служебные символы интерфейса нельзя отправлять в чат.")
    open val glyphRegistryUnavailableMessage: String
        get() = config.string("glyph-protection.messages.unavailable", "<red>Список символов ещё загружается. Повторите сообщение через несколько секунд.")

    companion object {
        const val DEFAULT_HUE_AMPLITUDE_DEGREES = 12.0
        const val MAX_HUE_AMPLITUDE_DEGREES = 30.0

        fun load(dataPath: Path): ChatModeConfig =
            ChatModeConfig(ConfigManager.ofModule(dataPath, "chat-mode.yml"))
    }
}

class TestChatModeConfig(
    override val messageColorVariation: ChatMessageColorVariation = ChatMessageColorVariation.DISABLED,
) : ChatModeConfig(EmptyConfig)
