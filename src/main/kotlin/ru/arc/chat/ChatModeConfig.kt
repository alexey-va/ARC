package ru.arc.chat

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path
import java.util.Collections
import java.util.regex.Pattern

/** Explicit ItemsAdder dimensions for large standalone chat sticker images. */
internal data class ChatStickerFontMetrics(
    val height: Int,
    val ascent: Int,
) {
    init {
        require(height in MIN_HEIGHT..MAX_HEIGHT) {
            "Sticker font-image height must be in $MIN_HEIGHT..$MAX_HEIGHT"
        }
        require(ascent in 1..height) { "Sticker font-image ascent must be in 1..height" }
    }

    companion object {
        const val MIN_HEIGHT = 17
        const val MAX_HEIGHT = 128
    }
}

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

    open val isolatedGlyphProtectionEnabled: Boolean
        get() = config.bool("glyph-protection.isolated-enabled", false)

    open val glyphUnauthorizedMessage: String
        get() = config.string("glyph-protection.messages.unauthorized", "<red>Этот символ недоступен вашему рангу.")
    open val glyphTechnicalMessage: String
        get() = config.string("glyph-protection.messages.technical", "<red>Служебные символы интерфейса нельзя отправлять в чат.")
    open val glyphRegistryUnavailableMessage: String
        get() = config.string("glyph-protection.messages.unavailable", "<red>Список символов ещё загружается. Повторите сообщение через несколько секунд.")

    open val largeStickerStandaloneMessage: String
        get() = config.string(
            "stickers.messages.standalone-only",
            "<red>Крупный стикер отправляется только отдельным сообщением.",
        )

    open val largeStickerPublicChatMessage: String
        get() = config.string(
            "stickers.messages.public-chat-only",
            "<red>Большие стикеры доступны в обычном чате. Сначала выйдите из комнаты или чата персонала.",
        )

    /** Exact ItemsAdder namespaced IDs; an empty table preserves the height guard. */
    internal open val stickerFontImageMetrics: Map<String, ChatStickerFontMetrics>
        get() = loadStickerFontImageMetrics()

    private fun loadStickerFontImageMetrics(): Map<String, ChatStickerFontMetrics> {
        val raw = try {
            config.map<Any?>(STICKER_FONT_IMAGES_PATH)
        } catch (failure: ClassCastException) {
            throw IllegalArgumentException("$STICKER_FONT_IMAGES_PATH must be a map of exact ItemsAdder IDs", failure)
        }
        require(raw.size <= MAX_STICKER_FONT_IMAGES) {
            "$STICKER_FONT_IMAGES_PATH supports at most $MAX_STICKER_FONT_IMAGES entries"
        }
        val result = raw.entries.associate { (id, rawMetrics) ->
            require(id.length <= MAX_ID_LENGTH && NAMESPACED_IMAGE_ID.matcher(id).matches()) {
                "Sticker font-image ID '$id' must be a lowercase exact namespaced ID"
            }
            val namespace = id.substringBefore(':')
            val path = id.substringAfter(':')
            require(namespace != "_iainternal" && !path.startsWith("offset_")) {
                "Internal and offset font-images cannot be configured as stickers: $id"
            }
            val metrics = rawMetrics as? Map<*, *>
                ?: throw IllegalArgumentException("$STICKER_FONT_IMAGES_PATH.$id must contain height and ascent")
            require(metrics.keys == setOf("height", "ascent")) {
                "$STICKER_FONT_IMAGES_PATH.$id must contain exactly height and ascent"
            }
            val height = strictInt(metrics["height"], "$STICKER_FONT_IMAGES_PATH.$id.height")
            val ascent = strictInt(metrics["ascent"], "$STICKER_FONT_IMAGES_PATH.$id.ascent")
            id to ChatStickerFontMetrics(height, ascent)
        }
        return Collections.unmodifiableMap(result.toSortedMap())
    }

    private fun strictInt(value: Any?, path: String): Int {
        require(value is Byte || value is Short || value is Int || value is Long) { "$path must be an integer" }
        val exact = (value as Number).toLong()
        require(exact in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$path is outside the integer range" }
        return exact.toInt()
    }

    companion object {
        const val DEFAULT_HUE_AMPLITUDE_DEGREES = 12.0
        const val MAX_HUE_AMPLITUDE_DEGREES = 30.0
        private const val STICKER_FONT_IMAGES_PATH = "stickers.font-images"
        private const val MAX_STICKER_FONT_IMAGES = 256
        private const val MAX_ID_LENGTH = 256
        private val NAMESPACED_IMAGE_ID = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+")

        fun load(dataPath: Path): ChatModeConfig =
            ChatModeConfig(ConfigManager.ofModule(dataPath, "chat-mode.yml"))
    }
}

internal class TestChatModeConfig(
    override val messageColorVariation: ChatMessageColorVariation = ChatMessageColorVariation.DISABLED,
    internal override val stickerFontImageMetrics: Map<String, ChatStickerFontMetrics> = emptyMap(),
    override val largeStickerStandaloneMessage: String = "<red>Крупный стикер отправляется только отдельным сообщением.",
) : ChatModeConfig(EmptyConfig)
