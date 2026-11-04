package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.graves.GravePreferences
import ru.arc.graves.GraveStyle
import ru.arc.graves.GraveTextMode
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Cosmetic preferences only; the grave runtime captures these for each future death. */
internal class HelpCenterGraveSettings(
    private val settings: HelpCenterSettings,
    private val legacy: HelpCenterLegacySettings,
    private val navigation: HelpCenterNavigation,
    private val show: (Player, PaperDialogScreen) -> Unit,
    private val openSettings: (Player) -> Unit,
) : AutoCloseable {
    private val presentation = HelpCenterPresentation(settings)
    private val miniMessage = MiniMessage.miniMessage()
    private val tasks = LifecycleTaskScope()
    private val pendingSaves = ConcurrentHashMap.newKeySet<UUID>()
    private var active = true

    fun open(player: Player) {
        navigation.visit(player) { open(player) }
        val preferences = legacy.gravePreferences(player)
        val body = buildList {
            add(presentation.compactFacts(listOf(
                text("model-field") to value(styleName(preferences.style)),
                text("text-field") to value(modeName(preferences.textMode)),
            ), WIDTH, DialogTables.PanelSkin.PLUM))
            if (preferences.textMode == GraveTextMode.CUSTOM) add(inscription(preferences.customText))
            add(panel("future"))
        }
        show(player, PaperDialogScreen(
            id = ROOT, title = text("title"), body = body,
            buttons = listOf(
                button("grave_models", text("models-label"), text("models-tooltip")) { openModels(player) },
                button("grave_text", text("text-label"), text("text-tooltip")) { openTextModes(player) },
            ), exitButton = back { openSettings(player) }, columns = 2,
        ))
    }

    private fun openModels(player: Player, notice: String? = null) {
        navigation.visit(player) { openModels(player) }
        val preferences = legacy.gravePreferences(player)
        show(player, PaperDialogScreen(
            id = MODELS, title = text("models-title"),
            body = listOf(panel("models-body")) + notice(notice),
            buttons = GraveStyle.entries.map { style ->
                button("grave_style_${id(style.name)}", choice(styleName(style), preferences.style == style),
                    text("style-${id(style.name)}-description")) {
                    // Native callbacks are single-use, including a selected radio's no-write action.
                    if (style == preferences.style) openModels(player)
                    else save(player, preferences.copy(style = style), MODELS, "models-title",
                        { open(player) }) { openModels(player, it) }
                }
            }, exitButton = back { open(player) }, columns = 2,
        ))
    }

    private fun openTextModes(player: Player, notice: String? = null) {
        navigation.visit(player) { openTextModes(player) }
        val preferences = legacy.gravePreferences(player)
        show(player, PaperDialogScreen(
            id = MODES, title = text("text-title"),
            body = listOf(panel("text-body")) + notice(notice),
            buttons = GraveTextMode.entries.map { mode ->
                val label = if (mode == GraveTextMode.CUSTOM) modeName(mode).append(Component.text(" ›")) else modeName(mode)
                button("grave_mode_${id(mode.name)}", choice(label, preferences.textMode == mode),
                    text("mode-${id(mode.name)}-description")) {
                    if (mode == GraveTextMode.CUSTOM) openEditor(player, preferences.customText)
                    else if (mode == preferences.textMode) openTextModes(player)
                    else save(player, preferences.copy(textMode = mode), MODES, "text-title",
                        { open(player) }) { openTextModes(player, it) }
                }
            }, exitButton = back { open(player) },
        ))
    }

    private fun openEditor(player: Player, initial: String, notice: String? = null) {
        // Core restores the submitted input snapshot on Back from the preview.
        navigation.visit(player)
        show(player, PaperDialogScreen(
            id = EDITOR, title = text("editor-title"),
            body = listOf(panel("editor-body")) + notice(notice),
            inputs = listOf(PaperDialogTextInput(INPUT, text("input-label"), initial = initial,
                width = WIDTH, maxLength = GravePreferences.MAX_TEXT_LENGTH)),
            buttons = listOf(PaperDialogButton(PaperDialogActionId.of("grave_preview"), text("preview-label"),
                width = 220, onClick = { context ->
                    val raw = context.text(INPUT).orEmpty()
                    val accepted = acceptedText(raw)
                    if (accepted == null) openEditor(player, raw.takeIf(GravePreferences::validText) ?: initial, "invalid-text")
                    else openPreview(player, accepted)
                })),
            exitButton = back { openTextModes(player) },
        ))
    }

    private fun openPreview(player: Player, customText: String, notice: String? = null) {
        navigation.visit(player) { openPreview(player, customText) }
        val current = legacy.gravePreferences(player)
        val saved = current.textMode == GraveTextMode.CUSTOM && current.customText == customText
        show(player, PaperDialogScreen(
            id = PREVIEW, title = text("preview-title"),
            body = listOf(inscription(customText), panel("preview-body")) + notice(notice),
            buttons = buildList {
                if (!saved) add(button("grave_save_text", text("save-label")) {
                    // Re-read the other preference: previewing text must not restore an old model choice.
                    val latest = legacy.gravePreferences(player)
                    save(player, latest.copy(textMode = GraveTextMode.CUSTOM, customText = customText), PREVIEW,
                        "preview-title", { openEditor(player, customText) }) { openPreview(player, customText, it) }
                })
                add(button("grave_edit_text", text("edit-label")) { openEditor(player, customText) })
            }, exitButton = back { openEditor(player, customText) }, columns = 2,
        ))
    }

    private fun save(
        player: Player,
        preferences: GravePreferences,
        screenId: String,
        titleKey: String,
        returnTo: () -> Unit,
        refresh: (String?) -> Unit,
    ) {
        val playerId = player.uniqueId
        if (!pendingSaves.add(playerId)) {
            refresh("saving")
            return
        }
        val token = navigation.visit(player) { refresh(null) }
        show(player, PaperDialogScreen(id = screenId, title = text(titleKey),
            body = listOf(panel("saving")), buttons = emptyList(), exitButton = back(returnTo)))
        val future = runCatching { legacy.saveGravePreferences(player, preferences) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        future.whenComplete { _, failure ->
            pendingSaves.remove(playerId)
            if (failure != null) Bukkit.getLogger().log(Level.WARNING, "Failed to save grave appearance for $playerId", failure)
        }.whenCompleteSync(tasks) { accepted, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            refresh(if (failure == null && accepted == true) "saved" else "save-failed")
        }
    }

    private fun inscription(raw: String): PaperDialogBody =
        presentation.panel(Component.text(raw).color(TextColor.color(0xffffff)).decoration(TextDecoration.ITALIC, false),
            WIDTH, DialogTables.PanelSkin.PLUM)

    private fun styleName(style: GraveStyle): Component = text("style-${id(style.name)}")
    private fun modeName(mode: GraveTextMode): Component = text("mode-${id(mode.name)}")
    private fun id(value: String): String = value.lowercase(Locale.ROOT)
    private fun value(component: Component): Component = text("value", "value" to component)
    private fun choice(label: Component, selected: Boolean): Component = text(
        if (selected) "choice-selected" else "choice", "label" to label,
    )
    private fun notice(key: String?): List<PaperDialogBody> = listOfNotNull(key?.let { panel(it) })
    private fun panel(key: String): PaperDialogBody = presentation.panel(text(key), WIDTH, DialogTables.PanelSkin.PLUM)
    private fun back(action: () -> Unit) = button("back", text("back"), action = action)
    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, width = 220, onClick = { action() })
    private fun text(key: String, vararg values: Pair<String, Component>): Component = miniMessage.deserialize(
        settings.text("settings-grave-$key"),
        *values.map { (name, value) -> Placeholder.component(name, value) }.toTypedArray(),
    ).decoration(TextDecoration.ITALIC, false)

    override fun close() { active = false; tasks.close() }

    companion object {
        private const val WIDTH = 360
        private const val ROOT = "help.settings.grave"
        private const val MODELS = "$ROOT.models"
        private const val MODES = "$ROOT.text"
        private const val EDITOR = "$ROOT.editor"
        private const val PREVIEW = "$ROOT.preview"
        private val INPUT = PaperDialogInputId.of("grave_inscription")

        internal fun acceptedText(raw: String): String? {
            if (!GravePreferences.validText(raw)) return null
            return raw.trim().takeIf { it.isNotEmpty() && DialogTextLayout.naturalWidth(Component.text(it)) != null }
        }
    }
}
