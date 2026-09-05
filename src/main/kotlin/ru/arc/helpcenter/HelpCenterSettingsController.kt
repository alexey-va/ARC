package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.MenuShortcutAction
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.text.NumberFormat
import java.util.Locale

/** Personal settings own their child routes and async refreshes, sharing the hub's visit generation. */
internal class HelpCenterSettingsController(
    private val settings: HelpCenterSettings,
    private val gateway: HelpCenterGateway,
    private val legacy: HelpCenterLegacySettings,
    private val navigation: HelpCenterNavigation,
    private val showDialog: (Player, PaperDialogScreen) -> Unit,
    private val executeCatalog: (Player, String) -> Unit,
    private val executeInventory: (Player, String) -> Boolean,
    private val openRoot: (Player) -> Unit,
) : AutoCloseable {
    private enum class Section(val key: String, val entries: List<String>) {
        CONTROLS("controls", listOf("shortcut", "escape", "shift-sign-edit", "stairs-sit")),
        INTERFACE("interface", listOf("scoreboard", "tablist", "particles", "totem", "resource-pack")),
        SOCIAL("social", listOf("chat", "notifications", "tpa")),
        WORLD("world", listOf("trails", "flight", "lands", "portal-style", "portal-by-other", "portal-for-other")),
    }
    private val miniMessage = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()
    private val tasks = LifecycleTaskScope()
    private var active = true

    fun open(player: Player) {
        navigation.visit(player) { open(player) }
        val entries = legacy.entries(player).associateBy { it.id }
        val buttons = Section.entries.map { section ->
            button("settings_${section.key}", text("settings-${section.key}-label"), text("settings-${section.key}-tooltip")) {
                openSection(player, section)
            }
        } + if (player.hasPermission("tab.group.admin")) listOf(
            button("legacy_admin", text("legacy-settings-admin", "state" to state(entries.getValue("admin"))), text("legacy-settings-admin-tooltip")) {
                legacy.execute(player, "admin")
            },
        ) else emptyList()
        showDialog(player, PaperDialogScreen(
            id = "help.category.settings", title = text("category-settings-title"),
            body = listOf(PaperDialogBody(text("settings-hub-body",
                "shortcut" to state(entries.getValue("shortcut")), "escape" to state(entries.getValue("escape"))), 468)),
            buttons = buttons, exitButton = button("root", text("root-label")) { openRoot(player) }, columns = 2,
        ))
    }

    private fun openSection(player: Player, section: Section) {
        navigation.visit(player) { openSection(player, section) }
        val entries = legacy.entries(player).associateBy { it.id }
        val snapshot = gateway.settings(player)
        val buttons = section.entries.map { id ->
            when (id) {
                "chat" -> {
                    val global = snapshot.chatMode == HelpCenterChatMode.GLOBAL
                    val action = if (global) "chat-local" else "chat-global"
                    button("setting_$action", text("setting-chat-current", "state" to text(if (global) "chat-global-state" else "chat-local-state")), text("setting-$action-tooltip")) {
                        val token = navigation.visit(player) { openSection(player, section) }
                        gateway.selectChatMode(player, if (global) HelpCenterChatMode.LOCAL else HelpCenterChatMode.GLOBAL)
                            .whenCompleteSync(tasks) { _, failure ->
                                if (active && player.isOnline && navigation.isCurrent(player, token)) {
                                    if (failure != null) player.sendMessage(text("action-failed"))
                                    openSection(player, section)
                                }
                            }
                    }
                }
                "particles" -> button("setting_particles", text("setting-particles-current", "state" to booleanState(snapshot.particlesEnabled)), text("setting-particles-tooltip")) {
                    executeCatalog(player, "particles")
                    openSection(player, section)
                }
                "trails" -> {
                    val available = HelpCenterFeature.TRAILS in gateway.features()
                    val enabled = snapshot.trailsEnabled
                    val action = if (enabled == true) "trails-off" else "trails-on"
                    val known = available && enabled != null
                    button(if (known) "setting_$action" else "setting_trails_unavailable",
                        text("setting-trails-current", "state" to if (available) booleanState(enabled) else text("settings-unavailable-state")),
                        text(if (known) "setting-$action-tooltip" else "settings-trails-unavailable-tooltip")) {
                        if (known) {
                            executeCatalog(player, action)
                            openSection(player, section)
                        }
                    }
                }
                else -> legacyButton(player, entries.getValue(id), section)
            }
        }
        showDialog(player, PaperDialogScreen(
            id = "help.settings.section.${section.key}", title = text("settings-${section.key}-title"),
            body = listOf(PaperDialogBody(text("settings-${section.key}-body"), 468)), buttons = buttons,
            exitButton = button("back", text("settings-back-label")) { open(player) }, columns = 2,
        ))
    }

    private fun legacyButton(player: Player, entry: HelpCenterLegacySettingEntry, section: Section): PaperDialogButton =
        button("legacy_${entry.id}", text(entry.labelKey, "state" to state(entry)), text(entry.tooltipKey)) {
            when (entry.id) {
                "shortcut" -> openOptions(player, entry.id, MenuShortcutAction.entries.map { "shortcut-${it.id}" }, section)
                "escape" -> openOptions(player, entry.id, listOf("escape-close", "escape-back"), section)
                "scoreboard", "tablist" -> openOptions(player, entry.id, (1..20).map { "${entry.id}-$it" } + "${entry.id}-off", section)
                "lands" -> openOptions(player, entry.id, listOf("lands-show", "lands-hide"), section)
                "portal-style" -> openOptions(player, entry.id, HelpCenterLegacySettings.PORTAL_STYLES.map { "portal-style-$it" }, section)
                "flight" -> openFlight(player)
                else -> apply(player, entry.id) { openSection(player, section) }
            }
        }

    private fun openOptions(player: Player, group: String, actions: List<String>, section: Section) {
        navigation.visit(player) { openOptions(player, group, actions, section) }
        val entry = legacy.entries(player).first { it.id == group }
        val recharge = group == "flight"
        showDialog(player, PaperDialogScreen(
            id = if (recharge) "help.settings.flight.recharge" else "help.settings.$group",
            title = text("settings-options-$group-title"),
            body = if (recharge) listOf(
                PaperDialogBody(text("settings-flight-auto-current", "state" to state(entry)), 468),
                PaperDialogBody(text("flight-body-recharge"), 468),
                PaperDialogBody(text("settings-flight-auto-off-help"), 468),
            ) else if (group == "lands") listOf(PaperDialogBody(text("settings-options-lands-body"), 468))
            else listOf(PaperDialogBody(text("settings-options-body", "state" to state(entry)), 468),
                PaperDialogBody(text("settings-options-$group-body"), 468)),
            buttons = actions.map { id ->
                val mode = id.substringAfterLast('-').toIntOrNull()
                val name = if (mode != null) text("legacy-settings-$group-mode-$mode")
                    else if (group == "portal-style") text("legacy-state-${id.removePrefix("portal-style-")}")
                    else text("legacy-action-$id")
                val label = if (id == "$group-${entry.state}") text("settings-selected-option", "label" to Component.text(plain.serialize(name))) else name
                val tooltip = when {
                    group in setOf("shortcut", "escape", "flight") -> text("legacy-action-$id-tooltip")
                    group == "portal-style" -> text("settings-portal-style-option-tooltip", "state" to name)
                    else -> text("settings-options-$group-body")
                }
                button("legacy_$id", label, tooltip) {
                    apply(player, id) { openOptions(player, group, actions, section) }
                }
            },
            exitButton = button("back", text(if (recharge) "settings-flight-back-label" else "settings-${section.key}-back-label")) {
                if (recharge) openFlight(player) else openSection(player, section)
            }, columns = 2,
        ))
    }

    private fun openFlight(player: Player) {
        navigation.visit(player) { openFlight(player) }
        val snapshot = legacy.flightSnapshot(player)
        val entry = legacy.entries(player).first { it.id == "flight" }
        val valid = snapshot?.takeIf { it.charge.isFinite() && it.maximum > 0 && it.charge >= 0 }
        val data = if (valid == null) text("settings-flight-unavailable") else {
            val ratio = (valid.charge / valid.maximum).coerceIn(0.0, 1.0)
            val filled = (ratio * 16).toInt()
            val numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))
            text("settings-flight-status", "charge" to Component.text(numbers.format(valid.charge.toLong())),
                "maximum" to Component.text(numbers.format(valid.maximum)), "percent" to Component.text((ratio * 100).toInt().toString()),
                "filled" to Component.text("■".repeat(filled)), "empty" to Component.text("□".repeat(16 - filled)),
                "state" to booleanState(valid.enabled))
        }
        showDialog(player, PaperDialogScreen(
            id = "help.settings.flight", title = text("settings-flight-title"),
            body = listOf(PaperDialogBody(text("settings-flight-breadcrumb"), 468), PaperDialogBody(data, 468),
                PaperDialogBody(text("settings-flight-how"), 468)),
            buttons = listOf(
                button("legacy_flight_toggle", text(when (valid?.enabled) {
                    true -> "settings-flight-disable-label"
                    false -> "settings-flight-enable-label"
                    null -> "settings-flight-unavailable-label"
                }), text("legacy-action-flight-toggle-tooltip")) {
                    if (valid != null) apply(player, if (valid.enabled) "flight-disable" else "flight-enable") { openFlight(player) }
                },
                button("legacy_flight_recharge", text("legacy-action-flight-recharge"), text("legacy-action-flight-recharge-tooltip")) {
                    executeInventory(player, HelpCenterLegacySettings.PlayerCommand.FLIGHT_RECHARGE.value)
                },
                button("flight_auto", text("settings-flight-auto-label", "state" to state(entry)), text("legacy-settings-flight-tooltip")) {
                    openOptions(player, "flight", listOf("flight-exp", "flight-money", "flight-off"), Section.WORLD)
                },
            ), exitButton = button("back", text("settings-world-back-label")) { openSection(player, Section.WORLD) }, columns = 2,
        ))
    }

    private fun apply(player: Player, id: String, refresh: () -> Unit) {
        val token = navigation.visit(player, refresh)
        legacy.execute(player, id).whenCompleteSync(tasks) { accepted, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure != null || accepted != true) player.sendMessage(text("action-failed"))
            refresh()
        }
    }

    private fun state(entry: HelpCenterLegacySettingEntry): Component = when {
        entry.id in setOf("shortcut", "escape") -> text("${entry.id}-state-${entry.state}")
        entry.state?.toIntOrNull() != null && entry.id in setOf("scoreboard", "tablist") -> text("legacy-settings-${entry.id}-mode-${entry.state}")
        else -> text("legacy-state-${entry.state ?: "unknown"}")
    }
    private fun booleanState(enabled: Boolean?): Component = text(when (enabled) {
        true -> "legacy-state-on"
        false -> "legacy-state-off"
        null -> "legacy-state-unknown"
    })
    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id.replace('-', '_')), label, tooltip, width = 230, onClick = { action() })
    private fun text(key: String, vararg values: Pair<String, Component>): Component = miniMessage.deserialize(
        settings.text(key), *values.map { (key, value) -> Placeholder.component(key, value) }.toTypedArray(),
    ).decoration(TextDecoration.ITALIC, false)

    override fun close() { active = false; tasks.close() }
}
