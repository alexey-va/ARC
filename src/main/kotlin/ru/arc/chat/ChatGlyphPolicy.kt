package ru.arc.chat

import java.util.Collections

/** Immutable metadata exposed to the chat renderer for an exact configured sticker ID. */
internal data class ChatStickerFontImage(
    val id: String,
    val unicode: String,
    val height: Int,
    val ascent: Int,
)

/**
 * One font image known to the active ItemsAdder registry.
 *
 * [permission] is the effective ItemsAdder permission node returned by the
 * runtime image metadata. It already includes the `ia.user.image.use.` prefix;
 * `null` means the image has no per-image permission requirement.
 */
internal data class ChatGlyphDefinition(
    val id: String,
    val unicode: String,
    val permission: String?,
    val technical: Boolean,
)

internal enum class ChatGlyphViolationReason {
    UNAUTHORIZED,
    TECHNICAL,
    UNKNOWN_PRIVATE_USE,
    STICKER_MUST_BE_STANDALONE,
}

internal data class ChatGlyphViolation(
    val reason: ChatGlyphViolationReason,
    val glyphId: String? = null,
)

/**
 * Checks player-supplied chat text against the current font-image definitions.
 *
 * The policy stores only immutable lookup tables. Permission checks remain a
 * callback so callers observe current effective permissions on each message.
 * Duplicate Unicode values and aliases are kept together and require every
 * matching definition to be usable, preventing an ambiguous alias from
 * selecting a less-restricted definition.
 */
internal class ChatGlyphPolicy(
    definitions: Collection<ChatGlyphDefinition>,
    stickerFontImageMetrics: Map<String, ChatStickerFontMetrics> = emptyMap(),
) {
    private val definitionsByUnicode: Map<String, List<ChatGlyphDefinition>>
    private val definitionsByAlias: Map<String, List<ChatGlyphDefinition>>
    private val stickerImagesById: Map<String, ChatStickerFontImage>

    /** Active, configured and non-technical images, keyed by their exact ItemsAdder ID. */
    fun stickerFontImages(): Map<String, ChatStickerFontImage> = stickerImagesById

    /** Resolves one configured sticker in its original signed-message form. */
    fun standaloneSticker(message: String): ChatStickerFontImage? = standaloneStickerAt(message)

    init {
        val ordered = definitions.sortedWith(DEFINITION_ORDER)
        require(ordered.all { it.unicode.isSingleUnicodeScalar() }) {
            "Every chat glyph definition must contain exactly one Unicode scalar"
        }

        definitionsByUnicode = ordered.groupBy(ChatGlyphDefinition::unicode)

        val aliases = mutableMapOf<String, MutableList<ChatGlyphDefinition>>()
        ordered.forEach { definition ->
            val id = definition.id.lowercase()
            aliases.getOrPut(id) { mutableListOf() }.add(definition)
            val unqualifiedId = id.substringAfterLast(':')
            if (unqualifiedId != id) {
                aliases.getOrPut(unqualifiedId) { mutableListOf() }.add(definition)
            }
        }
        definitionsByAlias = aliases.mapValues { (_, values) -> values.toList() }
        stickerImagesById = Collections.unmodifiableMap(
            ordered.asSequence()
                .filterNot(ChatGlyphDefinition::technical)
                .mapNotNull { definition ->
                    stickerFontImageMetrics[definition.id]?.let { metrics ->
                        definition.id to ChatStickerFontImage(
                            id = definition.id,
                            unicode = definition.unicode,
                            height = metrics.height,
                            ascent = metrics.ascent,
                        )
                    }
                }
                .toMap(LinkedHashMap()),
        )
    }

    /** Returns the first policy violation in [message], if there is one. */
    fun violation(
        message: String,
        hasPermission: (String) -> Boolean,
        isOperator: Boolean = false,
        channelPermission: String = DEFAULT_CHANNEL_PERMISSION,
    ): ChatGlyphViolation? {
        if (isOperator) return null

        var index = 0
        var stickerGlyphId: String? = null
        while (index < message.length) {
            val placeholder = placeholderAt(message, index)
            if (placeholder != null) {
                if (placeholder.technical) {
                    return ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL)
                }
                val definitions = placeholder.alias?.let(definitionsByAlias::get)
                if (definitions != null) {
                    stickerGlyphId = stickerGlyphId ?: configuredStickerId(definitions)
                    definitionViolation(definitions, hasPermission, channelPermission)?.let { return it }
                }
            }

            val codePoint = message.codePointAt(index)
            val glyph = String(Character.toChars(codePoint))
            val definitions = definitionsByUnicode[glyph]
            if (definitions != null) {
                stickerGlyphId = stickerGlyphId ?: configuredStickerId(definitions)
                definitionViolation(definitions, hasPermission, channelPermission)?.let {
                    return it
                }
            } else if (codePoint.isPrivateUseScalar()) {
                return ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
            }

            index += Character.charCount(codePoint)
        }
        if (stickerGlyphId != null && standaloneSticker(message) == null) {
            return ChatGlyphViolation(ChatGlyphViolationReason.STICKER_MUST_BE_STANDALONE, stickerGlyphId)
        }
        return null
    }

    private fun configuredStickerId(definitions: List<ChatGlyphDefinition>): String? =
        definitions.firstOrNull { it.id in stickerImagesById }?.id

    private fun standaloneStickerAt(message: String): ChatStickerFontImage? {
        var index = skipWhitespace(message, 0)
        if (index < message.length && message[index] == '!') {
            index = skipWhitespace(message, index + 1)
        }
        if (index >= message.length) return null

        val placeholder = placeholderAt(message, index)
        val sticker = if (placeholder != null) {
            if (placeholder.technical) return null
            val definitions = placeholder.alias?.let(definitionsByAlias::get) ?: return null
            val id = configuredStickerId(definitions) ?: return null
            index = placeholder.endExclusive
            stickerImagesById[id]
        } else {
            val codePoint = message.codePointAt(index)
            val glyph = String(Character.toChars(codePoint))
            val definitions = definitionsByUnicode[glyph] ?: return null
            val id = configuredStickerId(definitions) ?: return null
            index += Character.charCount(codePoint)
            stickerImagesById[id]
        }

        return sticker?.takeIf { skipWhitespace(message, index) == message.length }
    }

    private fun skipWhitespace(message: String, startIndex: Int): Int {
        var index = startIndex
        while (index < message.length && message[index].isWhitespace()) index++
        return index
    }

    private fun definitionViolation(
        definitions: List<ChatGlyphDefinition>,
        hasPermission: (String) -> Boolean,
        channelPermission: String,
    ): ChatGlyphViolation? {
        definitions.firstOrNull(ChatGlyphDefinition::technical)?.let {
            return ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL, it.id)
        }

        val glyphId = definitions.first().id
        if (!hasPermission(channelPermission)) {
            return ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, glyphId)
        }

        definitions.firstOrNull { definition ->
            definition.permission?.let { permission ->
                !hasPermission(permission)
            } ?: false
        }?.let {
            return ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, it.id)
        }

        return null
    }

    private fun placeholderAt(message: String, startIndex: Int): Placeholder? {
        if (message[startIndex] == ':') {
            val endIndex = message.indexOf(':', startIndex + 1)
            if (endIndex > startIndex + 1) {
                val token = message.substring(startIndex + 1, endIndex)
                if (token.startsWith(OFFSET_ALIAS_PREFIX, ignoreCase = true)) {
                    return Placeholder(technical = true, endExclusive = endIndex + 1)
                }
                token.asFontImageAlias()?.let { return Placeholder(alias = it, endExclusive = endIndex + 1) }
            }
        }

        if (
            message[startIndex] == '%' &&
            message.regionMatches(
                startIndex,
                IMAGE_PLACEHOLDER_PREFIX,
                0,
                IMAGE_PLACEHOLDER_PREFIX.length,
                ignoreCase = true,
            )
        ) {
            val contentStart = startIndex + IMAGE_PLACEHOLDER_PREFIX.length
            val endIndex = message.indexOf('%', contentStart)
            if (endIndex > contentStart) {
                val token = message.substring(contentStart, endIndex)
                if (token.startsWith(OFFSET_ALIAS_PREFIX, ignoreCase = true)) {
                    return Placeholder(technical = true, endExclusive = endIndex + 1)
                }
                token.asFontImageAlias()?.let { return Placeholder(alias = it, endExclusive = endIndex + 1) }
            }
        }

        return null
    }

    private data class Placeholder(
        val alias: String? = null,
        val technical: Boolean = false,
        val endExclusive: Int,
    )

    private companion object {
        const val DEFAULT_CHANNEL_PERMISSION = "ia.user.image.chat"
        const val IMAGE_PLACEHOLDER_PREFIX = "%img_"
        const val OFFSET_ALIAS_PREFIX = "offset_"

        val DEFINITION_ORDER = compareBy<ChatGlyphDefinition>(
            ChatGlyphDefinition::id,
            { it.permission.orEmpty() },
            ChatGlyphDefinition::technical,
        )

        fun String.isSingleUnicodeScalar(): Boolean {
            if (isEmpty()) return false
            val codePoint = codePointAt(0)
            return Character.charCount(codePoint) == length &&
                codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        }

        fun String.asFontImageAlias(): String? {
            val normalized = lowercase()
            return normalized.takeIf { alias ->
                alias.isNotEmpty() && alias.all { character ->
                    character in 'a'..'z' ||
                        character in '0'..'9' ||
                        character == '_' ||
                        character == '-' ||
                        character == ':'
                }
            }
        }

        fun Int.isPrivateUseScalar(): Boolean =
            this in BMP_PRIVATE_USE_RANGE ||
                this in SUPPLEMENTARY_PRIVATE_USE_A_RANGE ||
                this in SUPPLEMENTARY_PRIVATE_USE_B_RANGE

        val BMP_PRIVATE_USE_RANGE = 0xE000..0xF8FF
        val SUPPLEMENTARY_PRIVATE_USE_A_RANGE = 0xF0000..0xFFFFD
        val SUPPLEMENTARY_PRIVATE_USE_B_RANGE = 0x100000..0x10FFFD
    }
}
