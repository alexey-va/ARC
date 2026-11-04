package ru.arc.graves

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.Base64

/** A cosmetic choice captured at death; never participates in item/XP recovery policy. */
internal data class GraveAppearanceChoice(
    val modelId: String? = null,
    val engraved: Boolean? = null,
    val epitaph: String,
) {
    fun validate() {
        require(modelId == null || GraveStyle.entries.any { it.modelId == modelId }) { "Unknown grave model" }
        require(GravePreferences.validText(epitaph)) { "Invalid grave inscription" }
    }
}

/** Independent optional metadata; corrupt cosmetics must never prevent item recovery. */
internal object GraveAppearancePayload {
    private val codec = BoundedJsonCodec(Gson(), GraveAppearanceChoice::class.java,
        JsonObjectContract(setOf("modelId", "engraved", "epitaph"), requiredFields = setOf("epitaph")),
        JsonResourceBounds(maxCharacters = 2048, maxDepth = 2, maxContainerEntries = 3, maxTotalNodes = 4, maxStringCharacters = 80),
        { it.validate() },
    )
    fun encode(choice: GraveAppearanceChoice): String = codec.encode(choice)
    fun decodeOrNull(raw: String?): GraveAppearanceChoice? = raw?.let { runCatching { codec.decode(it) }.getOrNull() }
}

internal enum class GraveStyle(val modelId: String?, val engraved: Boolean?) {
    RANDOM(null, null),
    SLATE("slate", false), OBELISK("obelisk", false), CAIRN("cairn", false),
    TIMBER("timber", false), BROKEN("broken", false),
    ENGRAVED_SLATE("slate", true), ENGRAVED_OBELISK("obelisk", true), ENGRAVED_CAIRN("cairn", true),
    ENGRAVED_TIMBER("timber", true), ENGRAVED_BROKEN("broken", true),
}

internal enum class GraveTextMode { AUTO, CUSTOM, NONE }

/** One bounded LuckPerms meta value, using the existing network-wide personal-settings owner. */
internal data class GravePreferences(
    val style: GraveStyle = GraveStyle.RANDOM,
    val textMode: GraveTextMode = GraveTextMode.AUTO,
    val customText: String = "",
) {
    init {
        require(validText(customText)) { "Invalid grave inscription" }
        require(textMode != GraveTextMode.CUSTOM || customText.isNotBlank()) { "Custom grave inscription is empty" }
    }

    fun stored(): String = listOf(style.name, textMode.name,
        Base64.getEncoder().encodeToString(customText.toByteArray(Charsets.UTF_8))).joinToString("|")

    fun appearance(automatic: () -> String): GraveAppearanceChoice = GraveAppearanceChoice(
        style.modelId, style.engraved,
        when (textMode) { GraveTextMode.AUTO -> automatic(); GraveTextMode.CUSTOM -> customText; GraveTextMode.NONE -> "" },
    )

    companion object {
        const val META_KEY = "arc-grave-appearance"
        const val MAX_TEXT_LENGTH = 80

        fun validText(text: String): Boolean = text.length <= MAX_TEXT_LENGTH && text.none { character ->
            character.isISOControl() || character in "§<>" || Character.getType(character) in INVALID_CHARACTER_TYPES
        }

        private val INVALID_CHARACTER_TYPES = setOf(
            Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(),
        )

        fun fromStored(raw: String?): GravePreferences {
            if (raw == null || raw.length > 512) return GravePreferences()
            return runCatching {
                val parts = raw.split('|')
                require(parts.size == 3)
                val bytes = Base64.getDecoder().decode(parts[2])
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                GravePreferences(GraveStyle.valueOf(parts[0]), GraveTextMode.valueOf(parts[1]), text)
            }.getOrDefault(GravePreferences())
        }
    }
}
