package ru.arc.helpcenter

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogScreen

/** HelpCenter alone opts into the installed dialog-ui library; domain actions and labels remain unchanged. */
internal class HelpCenterPresentation(settings: HelpCenterSettings) {
    private val decoration = settings.decorationFont.takeIf(String::isNotEmpty)?.let {
        DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10)
    }
    private val prose = TextColor.color(0xe8dfd2)
    private val corners = DialogDecoration.Corners(glyph(0xF9100, 13), glyph(0xF9101, 13), glyph(0xF9102, 13), glyph(0xF9103, 13))
    private val divider = DialogDecoration.Divider(glyph(0xF9104, 13), glyph(0xF9106, 13), glyph(0xF9105, 13))

    fun screen(screen: PaperDialogScreen, escapeGoesBack: Boolean): PaperDialogScreen {
        if (decoration == null) return screen
        val exitWidth = if (escapeGoesBack) screen.exitButton?.width else screen.buttons.firstOrNull()?.width ?: screen.exitButton?.width
        return screen.copy(
            buttons = screen.buttons.map { button(it, screen.id) },
            // Escape=close moves the original Back into the grid and adopts its width.
            exitButton = screen.exitButton?.let { button(it.copy(width = exitWidth ?: it.width), screen.id, back = true) },
        )
    }

    fun closeButton(label: Component, onClose: () -> Unit): PaperDialogButton = button(
        PaperDialogButton(PaperDialogActionId.of("close_menu"), label, width = 200,
            closeDialogBeforeAction = true, onClick = { onClose() }), "help.close",
    )

    private fun button(button: PaperDialogButton, screenId: String, back: Boolean = false): PaperDialogButton {
        val decoration = decoration ?: return button
        if (button.width !in 64..1024) return button
        val id = button.id.value
        val action = when {
            back || id in setOf("back", "root", "back_command") -> 24
            id in setOf("search", "find_player", "search_again", "empty_search") -> 27
            id == "settings" -> 30
            id == "previous" -> 24
            id == "next" -> 25
            id in setOf("confirm", "relocate_confirm", "tpa_accept", "duel_accept") -> 21
            id in setOf("close_menu", "tpa_deny", "duel_deny") -> 22
            id in setOf("delete", "delete_confirm") -> 38
            id in setOf("create_home", "rec_home") -> 34
            id == "invite" -> 23
            id.endsWith("_reset") || id.startsWith("container_preview_reset") -> 39
            else -> null
        }
        val base = if (back) 0xF9050 else if (action != null) 0xF9000 + (action - 21) * 4 else when {
            id in setOf("rank", "jobs", "skills", "quests", "enchants") -> 0xF908C
            id in setOf("root_activities", "root_trade", "root_technology") -> 0xF9084
            screenId.startsWith("help.settings") || id.startsWith("settings_") -> 0xF9088
            else -> 0xF9080
        }
        val frame = DialogDecoration.ButtonFrame(glyph(base, 16), glyph(base + 1, 17), glyph(base + 3, 15))
        val icon = action?.let { glyph(0xF9060 + it - 21, if (it in setOf(29, 33, 37, 38, 40)) 10 else 11) }
        // These are old configured ornamental prefixes, not part of the action's name.
        val prefix = legacyPrefixes[id] ?: when {
            back || id in setOf("back", "root") -> "‹ "
            action == 27 -> "✧ "
            action == 30 -> "☷ "
            else -> null
        }
        var label = if (prefix == null) button.label else button.label.replaceText {
            it.match("^" + Regex.escape(prefix)).replacement(Component.empty()).once()
        }
        val suffix = if (id == "next") " →" else if (action != null) " ›" else null
        if (suffix != null) label = label.replaceText {
            it.match(Regex.escape(suffix) + "$").replacement(Component.empty()).once()
        }
        return button.copy(label = decoration.buttonLabel(label, button.width, frame, icon, action == 25))
    }

    /** Each inner list is one semantic line, retaining every original value and its styling. */
    fun facts(rows: List<List<Pair<Component, Component>>>, width: Int, corners: Boolean = false): PaperDialogBody {
        val content = Component.empty().color(prose).children(rows.flatMapIndexed { index, row ->
            val line = Component.empty().children(row.flatMapIndexed { column, (label, value) ->
                (if (column == 0) emptyList() else listOf(Component.text("   ·   "))) +
                    listOf(label, Component.text(": "), value)
            })
            if (index == 0) listOf(line) else listOf(Component.newline(), line)
        })
        return if (corners && decoration != null) decoration.openCorners(content, width, this.corners) else PaperDialogBody(content, width)
    }

    fun separator(width: Int): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, divider), width) }

    // Explicit static action IDs only: never strip a player's, home's or warp's name.
    private val legacyPrefixes = mapOf(
        "back_command" to "↶ ", "previous" to "← ", "create_home" to "＋ ", "rec_home" to "＋ ",
        "teams" to "⚑ ", "now" to "◆ ", "travel" to "◇ ", "privat" to "⌂ ",
        "help_guide" to "✦ ", "help_goals" to "⚡ ", "help_recovery" to "◆ ",
        "help_commands" to "✧ ", "help_now" to "◇ ", "help_context" to "⌖ ",
        "help_players" to "☻ ", "help_requests" to "✉ ", "help_menu" to "⌂ ",
        "category_trade" to "◎ ", "category_progress" to "✦ ", "category_activities" to "⚡ ",
        "category_technology" to "⚒ ", "category_settings" to "☷ ",
        "command_events" to "⚡ ", "command_duels" to "⚔ ", "command_giveaways" to "☆ ",
        "command_dungeons" to "◆ ", "command_farms" to "⌂ ", "command_parkour" to "◇ ",
        "command_shops" to "⌂ ", "command_loot" to "◆ ", "command_sell" to "↑ ",
        "command_auction" to "◇ ", "command_bank" to "▣ ", "command_slimefun" to "⚙ ",
        "command_enchants" to "✦ ", "command_enchanter" to "◇ ", "command_builder" to "⚒ ",
        "command_mounts" to "♞ ", "command_vote" to "☀ ", "builder_wand" to "⚒ ", "builder_help" to "◇ ",
        "warps" to "◇ ", "spawn" to "⌂ ", "rtp" to "⌖ ", "public_homes" to "⌂ ",
        "rtp_vanilla" to "⌂ ", "rtp_mining" to "⛏ ", "rtp_biomes" to "✦ ",
        "context_travel" to "◇ ", "context_privat" to "⌂ ", "dungeons_portals" to "› ", "dungeons_guide" to "◇ ",
    )

    private fun glyph(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
}
