package ru.arc.misc

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.subcommands.JoinMessageSubCommand
import ru.arc.commands.arc.subcommands.QuitMessageSubCommand
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.sync
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture

/** Native dialogs for network-wide join/leave selections. The name preserves existing command callers. */
object JoinMessageGuiFactory {
    private val dialogs by lazy {
        JoinMessageDialogs(ConfigManager.of(ARC.instance.dataFolder.toPath(), "modules/join-message-dialog.yml"))
    }

    fun show(player: Player, isJoin: Boolean = true, startPage: Int = 0) = dialogs.show(player, isJoin, startPage)
}

internal class JoinMessageDialogs(
    private val config: Config,
    private val runOnMain: (() -> Unit) -> Unit = { block ->
        if (Bukkit.isPrimaryThread()) block() else sync { block() }
    },
) {
    init {
        config.mergeMissingFromBundled("modules/join-message-dialog.yml")
        val loader = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
        fun bundledText(resource: String): Map<*, *> = checkNotNull(javaClass.getResourceAsStream("/modules/$resource")).use {
            (loader.loadFromInputStream(it) as Map<*, *>)["text"] as Map<*, *>
        }
        val defaults = bundledText("join-message-dialog.yml")
        var changed = false
        listOf("join-message-dialog-legacy.yml", "join-message-dialog-v2.yml").flatMap { bundledText(it).entries }.forEach { (key, old) ->
            val replacement = defaults[key] as? String
            if (replacement != null && replacement != old && config.string("text.$key").replace("<color:#", "<#") == old) {
                config.setString("text.$key", replacement)
                changed = true
            }
        }
        if (changed) config.saveStrict()
    }

    private val inputId = PaperDialogInputId.of("message")
    private val generations = mutableMapOf<java.util.UUID, Long>()
    private var nextGeneration = 0L
    private val width get() = config.int("button-width", 600).coerceIn(300, 1024)
    private val pageSize get() = config.int("page-size", 6).coerceIn(1, 10)

    fun show(player: Player, isJoin: Boolean = true, startPage: Int = 0) {
        runOnMain {
            val generation = advance(player)
            if (!allowed(player, isJoin)) return@runOnMain
            showLoading(
                player,
                "messages.catalog.${if (isJoin) "join" else "leave"}",
                text(if (isJoin) "join-title" else "leave-title"),
                reopen = { show(player, isJoin, startPage) },
            )
            JoinMessageCatalogManager.currentAsync()
                .thenCombine(JoinMessagesManager.getOrCreateAsync(player.name)) { catalog, data ->
                    Triple(catalog.entries(isJoin).map { it.copy() } to catalog.prefix(isJoin), data.selectedMessages(isJoin), data.customMessages(isJoin))
                }.whenComplete { result, failure ->
                    sync {
                        if (!current(player, generation)) return@sync
                        if (failure != null || result == null) {
                            reportFailure(player, failure ?: IllegalStateException("Missing message catalog"))
                        } else if (allowed(player, isJoin)) {
                            val known = result.first.first.map { it.message }.toSet() + result.third.map(CustomJoinMessage::selectionKey)
                            val removed = result.second - known
                            if (removed.isEmpty()) openCatalog(player, isJoin, result.first.first, result.second, result.third, startPage, result.first.second)
                            else finish(player, JoinMessagesManager.removeMessagesAsync(player.name, removed, isJoin)) {
                                show(player, isJoin, startPage)
                            }
                        }
                    }
                }
        }
    }

    private fun openCatalog(
        player: Player,
        isJoin: Boolean,
        catalog: List<JoinMessageCatalogEntry>,
        selected: Set<String>,
        custom: Set<String>,
        requestedPage: Int,
        legacyPrefix: String,
    ) {
        advance(player)
        val entries = catalog.filter { config.bool("show-unavailable", true) || it.permission == null || player.hasPermission(it.permission!!) }
        val total = custom.size + entries.size
        val pages = maxOf(1, (total + pageSize - 1) / pageSize)
        val page = requestedPage.coerceIn(0, pages - 1)
        val selectedCount = (entries.map { it.message } + custom.map(CustomJoinMessage::selectionKey)).toSet().count { it in selected }
        val buttons = custom.drop(page * pageSize).take(pageSize).mapIndexed { index, message ->
            val current = CustomJoinMessage.selectionKey(message) in selected
            val available = player.hasPermission(CUSTOM_PERMISSION)
            val preview = CustomJoinMessage.render(message, player.name, legacyPrefix)
            val label = text(if (current) "custom-selected" else if (available) "custom-unselected" else "custom-locked", "message" to plainLabel(preview))
            button("own_$index", label, isJoin, custom = true, tooltip = preview.append(Component.newline())
                .append(text("custom-description")).append(Component.newline())
                .append(text(if (!available) "unavailable" else if (current) "turn-off" else "turn-on"))) { context ->
                finish(context.player, JoinMessagesManager.selectCustomMessageAsync(context.player.name, message, isJoin, !current)) {
                    show(context.player, isJoin, page)
                }
            }
        }.toMutableList()
        val catalogOffset = (page * pageSize - custom.size).coerceAtLeast(0)
        buttons += entries.drop(catalogOffset).take(pageSize - buttons.size).mapIndexed { index, entry ->
            val available = entry.permission == null || player.hasPermission(entry.permission!!)
            val current = entry.message in selected
            val preview = preview(entry.message, player.name)
            val label = text(if (current) "selected" else if (!available) "locked" else "unselected", "message" to plainLabel(preview))
            button("phrase_$index", label, isJoin, tooltip = preview.append(Component.newline()).append(text(
                if (current) "turn-off" else if (!available) "unavailable" else "turn-on",
            ))) { context ->
                selectCatalogMessage(context.player, entry, isJoin, !current, page)
            }
        }
        if (player.hasPermission(CUSTOM_PERMISSION)) {
            buttons += button("custom", text("custom-list"), isJoin, custom = true) { showCustom(it.player, isJoin, page) }
        }
        buttons += button("switch", text(if (isJoin) "switch-leave" else "switch-join"), isJoin) { show(it.player, !isJoin) }
        buttons += button("previous", text("previous"), isJoin) { show(it.player, isJoin, (page + pages - 1) % pages) }
        buttons += button("next", text("next"), isJoin) { show(it.player, isJoin, (page + 1) % pages) }
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "messages.catalog.${if (isJoin) "join" else "leave"}",
            title = text(if (isJoin) "join-title" else "leave-title"),
            body = listOf(
                PaperDialogBody(text("catalog-help"), width),
                PaperDialogBody(text("page", "page" to (page + 1), "pages" to pages, "selected" to selectedCount), width),
            ) + if (total == 0) listOf(PaperDialogBody(text("empty"), width)) else emptyList(),
            buttons = buttons,
            columns = 1,
        ), reopen = { show(player, isJoin, page) }, onDismiss = { invalidate(player) })
    }

    private fun selectCatalogMessage(player: Player, entry: JoinMessageCatalogEntry, isJoin: Boolean, selected: Boolean, page: Int) {
        val generation = advance(player)
        JoinMessageCatalogManager.currentAsync().whenComplete { catalog, failure ->
            sync {
                if (!current(player, generation, isJoin)) return@sync
                if (failure != null || catalog == null) {
                    reportFailure(player, failure ?: IllegalStateException("Missing message catalog"))
                    return@sync
                }
                val current = catalog.entries(isJoin).find { it.id == entry.id && it.message == entry.message }
                if (selected && (current == null || current.permission?.let { !player.hasPermission(it) } == true)) {
                    player.sendMessage(text("unavailable"))
                    show(player, isJoin, page)
                } else finish(player, JoinMessagesManager.updateMessageAsync(player.name, entry.message, isJoin, selected)) {
                    show(player, isJoin, page)
                }
            }
        }
    }

    private fun showCustom(player: Player, isJoin: Boolean, catalogPage: Int) {
        val generation = advance(player)
        if (!allowed(player, isJoin, custom = true)) return
        showLoading(
            player,
            "messages.custom.${if (isJoin) "join" else "leave"}",
            text(if (isJoin) "custom-join-title" else "custom-leave-title"),
            reopen = { showCustom(player, isJoin, catalogPage) },
        )
        JoinMessagesManager.getOrCreateAsync(player.name)
            .thenCombine(JoinMessageCatalogManager.currentAsync()) { data, catalog -> data to catalog.prefix(isJoin) }
            .whenComplete { result, failure ->
            sync {
                if (!current(player, generation, isJoin, custom = true)) return@sync
                if (failure != null || result == null) {
                    reportFailure(player, failure ?: IllegalStateException("Missing message preferences"))
                    return@sync
                }
                if (!allowed(player, isJoin, custom = true)) return@sync
                val (data, legacyPrefix) = result
                val custom = data.customMessages(isJoin)
                val selected = data.selectedMessages(isJoin)
                val buttons = custom.mapIndexed { index, message ->
                    val current = CustomJoinMessage.selectionKey(message) in selected
                    val preview = CustomJoinMessage.render(message, player.name, legacyPrefix)
                    button("custom_$index", text(if (current) "manage-selected" else "manage-unselected", "message" to plainLabel(preview)), isJoin,
                        custom = true, tooltip = preview.append(Component.newline()).append(text("manage"))) {
                        openCustomMessage(it.player, isJoin, catalogPage, message, current, legacyPrefix)
                    }
                }.toMutableList()
                if (custom.size < CustomJoinMessage.MAX_SAVED) {
                    buttons += button("create", text("create"), isJoin, custom = true) { openEditor(it.player, isJoin, catalogPage) }
                }
                ArcMenus.openDialog(player, PaperDialogScreen(
                    id = "messages.custom.${if (isJoin) "join" else "leave"}",
                    title = text(if (isJoin) "custom-join-title" else "custom-leave-title"),
                    body = listOf(PaperDialogBody(text("custom-help", "count" to custom.size, "limit" to CustomJoinMessage.MAX_SAVED), width)) +
                        if (custom.isEmpty()) listOf(PaperDialogBody(text("custom-empty"), width)) else emptyList(),
                    buttons = buttons,
                    exitButton = button("back", text("back-catalog"), isJoin) { show(it.player, isJoin, catalogPage) },
                ), reopen = { showCustom(player, isJoin, catalogPage) }, onDismiss = { invalidate(player) })
            }
        }
    }

    private fun openCustomMessage(player: Player, isJoin: Boolean, catalogPage: Int, message: String, selected: Boolean, legacyPrefix: String) {
        advance(player)
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "messages.custom.detail.${if (isJoin) "join" else "leave"}",
            title = text("custom-detail-title"),
            body = listOf(PaperDialogBody(CustomJoinMessage.render(message, player.name, legacyPrefix), width)),
            buttons = listOf(
                button("toggle", text(if (selected) "turn-off" else "turn-on"), isJoin, custom = true) {
                    finish(it.player, JoinMessagesManager.selectCustomMessageAsync(it.player.name, message, isJoin, !selected)) {
                        showCustom(player, isJoin, catalogPage)
                    }
                },
                button("edit", text("edit-message"), isJoin, custom = true) {
                    openEditor(it.player, isJoin, catalogPage, CustomJoinMessage.editable(message, legacyPrefix), original = message)
                },
                button("delete", text("delete"), isJoin, custom = true) {
                    finish(it.player, JoinMessagesManager.deleteCustomMessageAsync(it.player.name, message, isJoin)) {
                        showCustom(player, isJoin, catalogPage)
                    }
                },
            ),
            exitButton = button("back", text("back-custom"), isJoin) { showCustom(it.player, isJoin, catalogPage) },
        ), reopen = { reopenCustomMessage(player, isJoin, catalogPage, message) }, onDismiss = { invalidate(player) })
    }

    private fun reopenCustomMessage(player: Player, isJoin: Boolean, catalogPage: Int, message: String) {
        val generation = advance(player)
        showLoading(
            player,
            "messages.custom.detail.${if (isJoin) "join" else "leave"}",
            text("custom-detail-title"),
            reopen = { reopenCustomMessage(player, isJoin, catalogPage, message) },
        )
        JoinMessagesManager.getOrCreateAsync(player.name)
            .thenCombine(JoinMessageCatalogManager.currentAsync()) { data, catalog -> data to catalog.prefix(isJoin) }
            .whenComplete { result, failure ->
            sync {
                if (!current(player, generation, isJoin, custom = true)) return@sync
                val data = result?.first
                val current = data?.customMessages(isJoin)?.firstOrNull { it == message }
                if (failure != null || current == null) showCustom(player, isJoin, catalogPage)
                else openCustomMessage(player, isJoin, catalogPage, current, CustomJoinMessage.selectionKey(current) in data.selectedMessages(isJoin), result.second)
            }
        }
    }

    private fun openEditor(
        player: Player,
        isJoin: Boolean,
        catalogPage: Int,
        initial: String = config.string(if (isJoin) "default-join-template" else "default-leave-template"),
        invalid: Boolean = false,
        original: String? = null,
        conflict: Boolean = false,
    ) {
        advance(player)
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "messages.editor.${if (isJoin) "join" else "leave"}",
            title = text(if (original == null) "editor-title" else "edit-title"),
            body = listOf(PaperDialogBody(text("editor-help", "limit" to CustomJoinMessage.MAX_LENGTH), width)) +
                if (invalid || conflict) listOf(PaperDialogBody(text(if (conflict) "edit-conflict" else "invalid"), width)) else emptyList(),
            inputs = listOf(PaperDialogTextInput(inputId, text("input"), initial, width, CustomJoinMessage.MAX_LENGTH)),
            buttons = listOf(button("preview", text("preview"), isJoin, custom = true) { context ->
                val raw = context.text(inputId).orEmpty()
                val message = runCatching { require(CustomJoinMessage.PLAYER in raw); CustomJoinMessage.normalize(raw) }.getOrNull()
                if (message == null) openEditor(context.player, isJoin, catalogPage, raw.take(CustomJoinMessage.MAX_LENGTH), invalid = true, original = original)
                else openPreview(context.player, isJoin, catalogPage, message, original)
            }, button("formatting", text("formatting"), isJoin, custom = true) {
                openFormatting(it.player, isJoin, catalogPage, it.text(inputId).orEmpty().take(CustomJoinMessage.MAX_LENGTH), original)
            }),
            exitButton = button("back", text("back-custom"), isJoin) {
                if (original == null) showCustom(it.player, isJoin, catalogPage)
                else reopenCustomMessage(it.player, isJoin, catalogPage, original)
            },
        ), onDismiss = { invalidate(player) })
    }

    private fun openFormatting(player: Player, isJoin: Boolean, catalogPage: Int, draft: String, original: String?) {
        advance(player)
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "messages.formatting",
            title = text("formatting-title"),
            body = listOf("formatting-help", "formatting-colors", "formatting-styles", "formatting-example").map {
                PaperDialogBody(text(it), width)
            },
            buttons = listOf(button("back", text("back-custom"), isJoin, custom = true) {
                openEditor(it.player, isJoin, catalogPage, draft, original = original)
            }),
        ), onDismiss = { invalidate(player) })
    }

    private fun openPreview(player: Player, isJoin: Boolean, catalogPage: Int, message: String, original: String?) {
        advance(player)
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "messages.preview.${if (isJoin) "join" else "leave"}",
            title = text("preview-title"),
            body = listOf(
                PaperDialogBody(CustomJoinMessage.render(message, player.name), width),
                PaperDialogBody(text(if (original == null) "preview-help" else "edit-preview-help"), width),
            ),
            buttons = listOf(button("save", text(if (original == null) "save" else "save-changes"), isJoin, custom = true) {
                val operation = if (original == null) JoinMessagesManager.addCustomMessageAsync(it.player.name, message, isJoin)
                    else JoinMessagesManager.editCustomMessageAsync(it.player.name, original, message, isJoin)
                finish(it.player, operation, rejected = {
                    openEditor(player, isJoin, catalogPage, message, original = original, conflict = true)
                }) {
                    showCustom(player, isJoin, catalogPage)
                }
            }),
            exitButton = button("edit", text("edit"), isJoin, custom = true) { openEditor(it.player, isJoin, catalogPage, message, original = original) },
        ), onDismiss = { invalidate(player) })
    }

    private fun button(
        id: String,
        label: Component,
        isJoin: Boolean,
        custom: Boolean = false,
        tooltip: Component = Component.empty(),
        action: (PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, width = width) { context ->
        if (allowed(context.player, isJoin, custom)) action(context)
    }

    private fun allowed(player: Player, isJoin: Boolean, custom: Boolean = false): Boolean {
        if (!player.isOnline) return false
        val permission = if (isJoin) JoinMessageSubCommand.permission else QuitMessageSubCommand.permission
        if ((permission != null && !player.hasPermission(permission)) || (custom && !player.hasPermission(CUSTOM_PERMISSION))) {
            player.sendMessage(text("no-permission"))
            return false
        }
        return true
    }

    private fun finish(player: Player, operation: CompletableFuture<Unit>, rejected: (() -> Unit)? = null, next: () -> Unit) {
        val generation = advance(player)
        operation.whenComplete { _, failure ->
            sync {
                if (!current(player, generation)) return@sync
                if (failure != null) {
                    val cause = (failure as? java.util.concurrent.CompletionException)?.cause ?: failure
                    if (cause is IllegalArgumentException && rejected != null) rejected() else reportFailure(player, failure)
                } else next()
            }
        }
    }

    private fun showLoading(
        player: Player,
        id: String,
        title: Component,
        reopen: () -> Unit,
    ) {
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = id,
            title = title,
            body = listOf(PaperDialogBody(Component.text("…"), width)),
            buttons = listOf(PaperDialogButton(
                PaperDialogActionId.of("loading_cancel"),
                Component.translatable("gui.cancel"),
                width = width,
                closeDialogBeforeAction = true,
            ) { invalidate(player) }),
        ), reopen = reopen, onDismiss = { invalidate(player) })
    }

    private fun advance(player: Player): Long {
        val next = ++nextGeneration
        generations[player.uniqueId] = next
        return next
    }

    private fun invalidate(player: Player) { generations.remove(player.uniqueId) }

    private fun current(player: Player, generation: Long, isJoin: Boolean? = null, custom: Boolean = false): Boolean =
        generations[player.uniqueId] == generation && player.isOnline &&
            (isJoin == null || allowed(player, isJoin, custom))

    private fun reportFailure(player: Player, failure: Throwable) {
        error("Failed to update/load join-message preferences for {}", player.name, failure)
        player.sendMessage(text("error"))
    }

    private fun text(key: String, vararg values: Pair<String, Any>): Component = TextUtil.mm(
        config.string("text.$key", key),
        TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value as? Component ?: Component.text(value.toString())) }),
    ).decoration(TextDecoration.ITALIC, false)

    private fun plainLabel(component: Component): Component =
        Component.text(PlainTextComponentSerializer.plainText().serialize(component))

    private fun preview(template: String, playerName: String): Component = Component.text(
        PlainTextComponentSerializer.plainText().serialize(TextUtil.mm(template.replace("%player_name%", playerName), true)),
    )
    companion object {
        const val CUSTOM_PERMISSION = "arc.join.message.custom"
    }

}
