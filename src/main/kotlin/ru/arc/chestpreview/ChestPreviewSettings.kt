package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

/** Presentation limits and MiniMessage templates for the chest inspection card. */
internal data class ChestPreviewSettings(
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val maxDistance: Double = DEFAULT_MAX_DISTANCE,
    val verticalGap: Double = DEFAULT_VERTICAL_GAP,
    val titleTemplate: String = DEFAULT_TITLE_TEMPLATE,
    val entryTemplate: String = DEFAULT_ENTRY_TEMPLATE,
    val emptyTemplate: String = DEFAULT_EMPTY_TEMPLATE,
    val overflowTemplate: String = DEFAULT_OVERFLOW_TEMPLATE,
) {
    init {
        require(maxItems in MIN_MAX_ITEMS..MAX_MAX_ITEMS) { "Chest preview max-items must be in 1..12" }
        require(maxDistance in MIN_DISTANCE..MAX_DISTANCE && maxDistance.isFinite()) {
            "Chest preview max-distance must be in 1.0..4.5"
        }
        require(verticalGap in MIN_VERTICAL_GAP..MAX_VERTICAL_GAP && verticalGap.isFinite()) {
            "Chest preview vertical-gap must be in 0.0..0.5"
        }
        listOf(
            "title" to titleTemplate,
            "entry" to entryTemplate,
            "empty" to emptyTemplate,
            "overflow" to overflowTemplate,
        ).forEach { (name, value) ->
            require(value.isNotBlank() && value.length <= MAX_TEMPLATE_LENGTH) {
                "Chest preview $name template must be nonblank and at most $MAX_TEMPLATE_LENGTH characters"
            }
        }
        validateTemplates()
    }

    private fun validateTemplates() {
        val miniMessage = MiniMessage.miniMessage()
        miniMessage.deserialize(titleTemplate)
        miniMessage.deserialize(
            entryTemplate,
            Placeholder.component("name", Component.text("item")),
            Placeholder.unparsed("count", "1"),
        )
        miniMessage.deserialize(emptyTemplate)
        miniMessage.deserialize(overflowTemplate, Placeholder.unparsed("count", "1"))
    }

    companion object {
        const val MIN_MAX_ITEMS = 1
        const val MAX_MAX_ITEMS = 12
        const val DEFAULT_MAX_ITEMS = 6
        const val MIN_DISTANCE = 1.0
        const val MAX_DISTANCE = 4.5
        const val DEFAULT_MAX_DISTANCE = 4.5
        const val MIN_VERTICAL_GAP = 0.0
        const val MAX_VERTICAL_GAP = 0.5
        const val DEFAULT_VERTICAL_GAP = 0.15
        const val MAX_TEMPLATE_LENGTH = 500

        const val DEFAULT_TITLE_TEMPLATE = "<gold>Содержимое сундука"
        const val DEFAULT_ENTRY_TEMPLATE = "<white><name> <gray>× <count>"
        const val DEFAULT_EMPTY_TEMPLATE = "<gray>Пусто"
        const val DEFAULT_OVERFLOW_TEMPLATE = "<dark_gray>И ещё: <count>"
    }
}
