package ru.arc.gui

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.NamedTextColor
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Applies the installed utility-button theme after Escape policy resolves the final controls. */
internal class ArcDialogPresentation(font: String) {
    private val decoration = font.takeIf(String::isNotEmpty)?.let {
        DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10)
    }

    fun screen(
        screen: PaperDialogScreen,
        footerIsBack: Boolean,
        movedBackId: PaperDialogActionId? = null,
    ): PaperDialogScreen {
        if (decoration == null) return screen
        val footer = screen.exitButton ?: if (footerIsBack) {
            val ids = screen.buttons.mapTo(HashSet()) { it.id.value }
            val index = generateSequence(0, Int::inc).first { "arc_history_exit_$it" !in ids }
            PaperDialogButton(PaperDialogActionId.of("arc_history_exit_$index"),
                Component.text("Назад", NamedTextColor.WHITE), width = 200, onClick = { })
        } else null
        return screen.copy(
            buttons = screen.buttons.map { button(it, back = it.id == movedBackId) },
            exitButton = footer?.let {
                val defaultLabel = (it.label as? TranslatableComponent)?.key() == "gui.back"
                if (footerIsBack) {
                    val back = if (it.closeDialogBeforeAction || defaultLabel) it.copy(
                        label = Component.text("Назад", NamedTextColor.WHITE), tooltip = Component.empty(),
                        closeDialogBeforeAction = false,
                    ) else it
                    decorateButton(back, 24, back = true)
                } else decorateButton(it.copy(
                    label = if (defaultLabel) Component.text("Закрыть меню", NamedTextColor.WHITE) else it.label,
                    closeDialogBeforeAction = true,
                ), 22)
            },
        )
    }

    fun button(button: PaperDialogButton, back: Boolean = false): PaperDialogButton {
        val id = button.id.value
        val isBack = back || id in setOf("back", "root", "back_command")
        val action = when {
            isBack -> 24
            id in setOf("search", "find_player", "search_again", "empty_search") -> 27
            id == "settings" -> 30
            id == "previous" -> 24
            id == "next" -> 25
            id == "save" -> 26
            id in setOf("confirm", "relocate_confirm", "tpa_accept", "duel_accept") -> 21
            id in setOf("close_menu", "loading_cancel", "tpa_deny", "duel_deny") -> 22
            id in setOf("delete", "delete_confirm") -> 38
            id in setOf("create_home", "rec_home") -> 34
            id in setOf("invite", "create") -> 23
            id.endsWith("_reset") || id.startsWith("container_preview_reset") -> 39
            else -> null
        } ?: return button
        return decorateButton(button, action, isBack)
    }

    private fun decorateButton(button: PaperDialogButton, action: Int, back: Boolean = false): PaperDialogButton {
        val decoration = decoration ?: return button
        if (button.width !in 64..1024) return button
        val id = button.id.value
        val base = if (back) 0xF9050 else 0xF9000 + (action - 21) * 4
        val icon = glyph(0xF9060 + action - 21, if (action == 38) 10 else 11)
        // These are old configured ornamental prefixes, not part of the action's name.
        val prefix = legacyPrefixes[id] ?: when {
            back || id in setOf("back", "root") -> "‹ "
            action == 27 -> "✧ "
            action == 30 -> "☷ "
            else -> null
        }
        val nativeLabel = if (action == 22 && (button.label as? TranslatableComponent)?.key() == "gui.cancel")
            Component.text("Отмена", NamedTextColor.WHITE) else button.label
        var label = if (prefix == null) nativeLabel else nativeLabel.replaceText {
            it.match("^" + Regex.escape(prefix)).replacement(Component.empty()).once()
        }
        if (id == "previous") label = label.replaceText {
            it.match("^‹ ").replacement(Component.empty()).once()
        }
        val suffix = if (id == "next") "(?: →| ›)" else " ›"
        label = label.replaceText {
            it.match(suffix + "$").replacement(Component.empty()).once()
        }
        val frame = DialogDecoration.ButtonFrame(glyph(base, 16), glyph(base + 1, 17), glyph(base + 3, 15))
        return button.copy(label = decoration.buttonLabel(label, button.width, frame, icon, action == 25))
    }

    // Explicit static action IDs only: never strip a player's, home's or warp's name.
    private val legacyPrefixes = mapOf(
        "back_command" to "↶ ", "previous" to "← ", "create_home" to "＋ ", "rec_home" to "＋ ",
    )

    private fun glyph(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
}
