package ru.arc.gui

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogNavigation
import ru.arc.paper.menu.PaperDialogScreen

/** Native paging stays quiet; the installed theme emphasizes selected actions and the history footer. */
internal class ArcDialogPresentation(font: String) {
    private val decoration = font.takeIf(String::isNotEmpty)?.let {
        DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10)
    }

    fun screen(screen: PaperDialogScreen, footerIsBack: Boolean): PaperDialogScreen {
        val ordered = paginationIds.fold(screen) { current, (previous, next) ->
            PaperDialogNavigation.bottom(current,
                current.buttons.firstOrNull { it.id.value == previous },
                current.buttons.firstOrNull { it.id.value == next })
        }
        if (decoration == null) return ordered
        return ordered.copy(
            buttons = ordered.buttons.map(::button),
            // Core supplies a missing footer using actual history, so a root cannot acquire a false Back.
            exitButton = ordered.exitButton?.let { if (footerIsBack) decorateButton(it, 24, back = true) else it },
        )
    }

    fun button(button: PaperDialogButton): PaperDialogButton {
        val action = when (button.id.value) {
            "search", "find_player", "search_again", "empty_search" -> 27
            "settings" -> 30
            "graves" -> 33
            "save" -> 26
            "confirm", "relocate_confirm", "tpa_accept", "duel_accept" -> 21
            "delete", "delete_confirm" -> 38
            else -> return button
        }
        return decorateButton(button, action)
    }

    private fun decorateButton(button: PaperDialogButton, action: Int, back: Boolean = false): PaperDialogButton {
        val decoration = decoration ?: return button
        if (button.width !in 64..1024) return button
        val base = if (back) 0xF9050 else 0xF9000 + (action - 21) * 4
        val icon = glyph(0xF9060 + action - 21, if (action == 33 || action == 38) 10 else 11)
        // Replace known ornamental prefixes only on these explicit static controls.
        val prefix = when {
            back -> "‹ "
            action == 27 -> "✧ "
            action == 30 -> "☷ "
            else -> null
        }
        var label = if (prefix == null) button.label else button.label.replaceText {
            it.match("^" + Regex.escape(prefix)).replacement(Component.empty()).once()
        }
        label = label.replaceText {
            it.match(" ?›$").replacement(Component.empty()).once()
        }
        val frame = DialogDecoration.ButtonFrame(glyph(base, 16), glyph(base + 1, 17), glyph(base + 3, 15))
        return button.copy(label = decoration.buttonLabel(label, button.width, frame, icon))
    }

    // Producer-owned page roles; never classify translated captions or dynamic domain names.
    private val paginationIds = listOf(
        "previous" to "next", "previous_page" to "next_page", "items_previous" to "items_next",
        "previous_regions" to "next_regions", "previous_levels" to "next_levels", "network_previous" to "network_next",
    )

    private fun glyph(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
}
