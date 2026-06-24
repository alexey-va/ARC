package ru.arc.util

import ru.arc.config.Config
import ru.arc.config.formatAvailableTagsComment

/**
 * Collects MiniMessage placeholder names for item config comments on inject.
 */
internal object ItemConfigTagComment {
    private val MINIMESSAGE_BUILTIN =
        setOf(
            "black",
            "dark_blue",
            "dark_green",
            "dark_aqua",
            "dark_red",
            "dark_purple",
            "gold",
            "gray",
            "grey",
            "dark_gray",
            "dark_grey",
            "blue",
            "green",
            "aqua",
            "cyan",
            "red",
            "light_purple",
            "purple",
            "pink",
            "yellow",
            "white",
            "bold",
            "b",
            "italic",
            "em",
            "i",
            "underlined",
            "u",
            "strikethrough",
            "st",
            "obfuscated",
            "obf",
            "reset",
            "newline",
            "br",
            "color",
            "gradient",
            "rainbow",
            "hover",
            "click",
            "insert",
            "insertion",
            "key",
            "lang",
            "lang_or",
            "selector",
            "score",
            "nbt",
            "translatable",
            "transition",
            "font",
            "shadow",
            "pride",
        )

    private val TAG_PATTERN = Regex("""<([a-z][a-z0-9_]*)>""", RegexOption.IGNORE_CASE)

    fun collect(
        registered: Collection<String>,
        display: String?,
        lore: List<String>?,
    ): List<String> {
        val tags = registered.map { it.lowercase() }.toMutableSet()
        collectFromText(display)?.let { tags.addAll(it) }
        lore?.forEach { line -> collectFromText(line)?.let { tags.addAll(it) } }
        return tags.sorted()
    }

    fun applyOnInject(
        config: Config,
        path: String,
        registered: Collection<String>,
        display: String?,
        lore: List<String>?,
    ) {
        formatAvailableTagsComment(collect(registered, display, lore))?.let { comment ->
            config.setComment(path, comment)
        }
    }

    private fun collectFromText(text: String?): Set<String>? {
        if (text.isNullOrBlank()) return null
        val tags =
            TAG_PATTERN
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .filter { it !in MINIMESSAGE_BUILTIN }
                .toSet()
        return tags.takeIf { it.isNotEmpty() }
    }
}
