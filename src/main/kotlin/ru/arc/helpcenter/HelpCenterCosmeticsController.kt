package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.cosmetics.CosmeticCategory
import ru.arc.cosmetics.CosmeticChange
import ru.arc.cosmetics.CosmeticEntry
import ru.arc.cosmetics.CosmeticOption
import ru.arc.cosmetics.CosmeticsCoreGateway
import ru.arc.cosmetics.ParticlePresetChange
import ru.arc.cosmetics.ParticlePresetOption
import ru.arc.cosmetics.PlayerParticlesGateway
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogNavigation
import ru.arc.paper.menu.PaperDialogScreen
import java.util.concurrent.CompletableFuture

/** Native wardrobe controls; the installed providers retain rendering, entitlements and persistence. */
internal class HelpCenterCosmeticsController(
    private val settings: HelpCenterSettings,
    private val navigation: HelpCenterNavigation,
    private val show: (Player, PaperDialogScreen) -> Unit,
    private val cosmetics: CosmeticsCoreGateway,
    private val particles: PlayerParticlesGateway,
) : AutoCloseable {
    private val presentation = HelpCenterPresentation(settings)
    private val miniMessage = MiniMessage.miniMessage()
    private val tasks = LifecycleTaskScope()
    private var active = true

    fun canOpen(player: Player): Boolean = cosmetics.canOpen(player) || particles.snapshot(player).available

    fun open(player: Player, returnTo: () -> Unit) {
        navigation.visit(player) { open(player, returnTo) }
        val wardrobe = cosmetics.snapshot(player)
        val effects = particles.snapshot(player)
        val body = buildList {
            if (wardrobe.available) {
                add(presentation.filledFacts(CosmeticCategory.entries.map { category -> listOf(
                    text("category-${category.key}") to text("value", "value" to wardrobe.equipped(category)
                        .joinToString(", ") { it.entry.name }.ifEmpty { plain("nothing-equipped") }),
                ) }, 468, DialogTables.PanelSkin.PLUM))
                if (wardrobe.entries.isEmpty()) add(panel("empty-wardrobe"))
                if (!wardrobe.worldAllowed) add(panel("world"))
            } else add(panel("unavailable"))
        }
        val buttons = buildList {
            if (wardrobe.available) {
                if (wardrobe.entries.any { it.equipped }) add(button("equipped", "equipped-label") {
                    openCatalog(player, null, returnTo)
                })
                for (category in CosmeticCategory.entries) {
                    if (wardrobe.entries.any { it.entry.category == category }) add(button("category_${category.key}", "category-${category.key}-label") {
                        openCatalog(player, category, returnTo)
                    })
                }
            }
            if (effects.available) add(button("effects", "effects-label") { openEffects(player, returnTo) })
            add(button("refresh", "refresh") { open(player, returnTo) })
        }
        show(player, PaperDialogScreen(id = "help.cosmetics", title = text("title"), body = body,
            buttons = buttons, columns = 2, exitButton = back(returnTo)))
    }

    private fun openCatalog(player: Player, category: CosmeticCategory?, returnTo: () -> Unit, requestedPage: Int = 0) {
        val snapshot = cosmetics.snapshot(player)
        val entries = snapshot.entries.filter { if (category == null) it.equipped else it.entry.category == category }
            .sortedWith(compareByDescending<CosmeticOption> { it.equipped }.thenByDescending { it.owned }.thenBy { it.entry.name })
        val pages = ((entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        navigation.visit(player) { openCatalog(player, category, returnTo, page) }
        val body = buildList {
            when {
                !snapshot.available -> add(panel("unavailable"))
                entries.isEmpty() -> add(panel("empty-category"))
                else -> {
                    if (category != null) add(panel("slots", "used" to snapshot.equipped(category).size.toString(),
                        "limit" to snapshot.limits.getValue(category).toString()))
                    add(PaperDialogBody(text("page", "page" to (page + 1).toString(), "pages" to pages.toString()), 468))
                }
            }
        }
        val buttons = entries.drop(page * PAGE_SIZE).take(PAGE_SIZE).mapIndexed { index, option ->
            button("item_$index", if (option.equipped) "entry-equipped" else if (option.owned) "entry" else "entry-locked",
                "name" to option.entry.name) { openItem(player, option.entry, category, page, returnTo) }
        } + button("refresh", "refresh") { openCatalog(player, category, returnTo, page) }
        val screen = PaperDialogScreen(id = "help.cosmetics.catalog.${category?.key ?: "equipped"}",
            title = text(category?.let { "category-${it.key}" } ?: "equipped-title"), body = body,
            buttons = buttons, columns = 2, exitButton = back { open(player, returnTo) })
        show(player, withPages(screen, page, pages) { openCatalog(player, category, returnTo, it) })
    }

    private fun openItem(player: Player, entry: CosmeticEntry, category: CosmeticCategory?, page: Int, returnTo: () -> Unit,
                         notice: String? = null) {
        navigation.visit(player) { openItem(player, entry, category, page, returnTo) }
        val snapshot = cosmetics.snapshot(player)
        val option = snapshot.entries.firstOrNull { it.entry == entry }
        val blocked = when {
            !snapshot.available -> "unavailable"
            option == null -> "changed"
            option.equipped -> null
            !option.owned -> "not-owned"
            !snapshot.worldAllowed -> "world"
            !snapshot.hasSlot(entry) -> "limit"
            else -> null
        }
        val body = buildList {
            add(presentation.filledFacts(listOf(
                listOf(text("type") to text("category-${entry.category.key}")),
                listOf(text("state") to text(when { option == null -> "state-unknown"; option.equipped -> "state-equipped"; else -> "state-removed" })),
            ), 468, DialogTables.PanelSkin.PLUM))
            if (blocked != null) add(panel(blocked))
            else if (option?.equipped == false && snapshot.replacedWings(entry).isNotEmpty()) add(panel("wing-replace",
                "names" to snapshot.replacedWings(entry).joinToString(", ") { it.entry.name }))
            if (notice != null) add(panel(notice))
        }
        val buttons = buildList {
            if (option != null && blocked == null) {
                val equip = !option.equipped
                add(button("change", if (equip) "equip" else "remove") {
                    val result = cosmetics.change(player, entry, equip)
                    val feedback = if (result == CosmeticChange.APPLIED) if (equip) "applied-equip" else "applied-remove"
                        else result.name.lowercase().replace('_', '-')
                    openItem(player, entry, category, page, returnTo, feedback)
                })
            }
            add(button("refresh", "refresh") { openItem(player, entry, category, page, returnTo) })
        }
        show(player, PaperDialogScreen(id = "help.cosmetics.item", title = text("item-title", "name" to entry.name),
            body = body, buttons = buttons, columns = 2,
            exitButton = back { openCatalog(player, category, returnTo, page) }))
    }

    private fun openEffects(player: Player, returnTo: () -> Unit, requestedPage: Int = 0, notice: String? = null) {
        val snapshot = particles.snapshot(player)
        val presets = snapshot.presets.sortedWith(compareByDescending<ParticlePresetOption> { it.active }.thenBy { it.name })
        val pages = ((presets.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        navigation.visit(player) { openEffects(player, returnTo, page) }
        val body = buildList {
            if (!snapshot.available) add(panel("effects-unavailable")) else {
                add(presentation.filledFacts(listOf(
                    listOf(text("effects-current") to text("value", "value" to snapshot.activeCount.toString())),
                    listOf(text("effects-visibility") to text(if (snapshot.visible) "yes" else "no")),
                ), 468, DialogTables.PanelSkin.PLUM))
                if (presets.isEmpty()) add(panel("effects-empty"))
                else add(PaperDialogBody(text("page", "page" to (page + 1).toString(), "pages" to pages.toString()), 468))
            }
            if (notice != null) add(panel(notice))
        }
        val buttons = buildList {
            if (snapshot.available) {
                presets.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, preset ->
                    add(button("preset_$index", if (preset.active) "entry-equipped" else "entry", "name" to preset.name) {
                        openPreset(player, preset, page, returnTo)
                    })
                }
                if (snapshot.activeCount > 0) add(button("reset", "effects-reset") {
                    openResetEffects(player, page, returnTo)
                })
                add(button("visibility", if (snapshot.visible) "effects-hide" else "effects-show") {
                    changeEffects(player, "help.cosmetics.effects", "effects-title", page, returnTo) {
                        particles.setVisible(player, !snapshot.visible)
                    }
                })
            }
            add(button("refresh", "refresh") { openEffects(player, returnTo, page) })
        }
        val screen = PaperDialogScreen(id = "help.cosmetics.effects", title = text("effects-title"), body = body,
            buttons = buttons, columns = 2, exitButton = back { open(player, returnTo) })
        show(player, withPages(screen, page, pages) { openEffects(player, returnTo, it) })
    }

    private fun openPreset(player: Player, captured: ParticlePresetOption, page: Int, returnTo: () -> Unit) {
        navigation.visit(player) { openPreset(player, captured, page, returnTo) }
        val snapshot = particles.snapshot(player)
        val preset = snapshot.presets.firstOrNull { it.id == captured.id }
        val body = when {
            !snapshot.available -> "effects-unavailable"
            preset == null -> "not-owned"
            preset.id in snapshot.shadowedPresetIds -> "effects-shadowed"
            preset.active && snapshot.presetsReplaceActive -> "effects-active"
            snapshot.presetsReplaceActive -> "effects-replace"
            else -> "effects-add"
        }
        val buttons = if (snapshot.available && preset != null && preset.id !in snapshot.shadowedPresetIds &&
            (!snapshot.presetsReplaceActive || !preset.active)) listOf(button("apply", "effects-apply") {
            changeEffects(player, "help.cosmetics.preset", "effects-title", page, returnTo) { particles.applyPreset(player, preset.id, snapshot) }
        }) else emptyList()
        show(player, PaperDialogScreen(id = "help.cosmetics.preset", title = text("item-title", "name" to captured.name),
            body = listOf(panel(body)), buttons = buttons, exitButton = back { openEffects(player, returnTo, page) }))
    }

    private fun openResetEffects(player: Player, page: Int, returnTo: () -> Unit) {
        navigation.visit(player) { openResetEffects(player, page, returnTo) }
        val snapshot = particles.snapshot(player)
        show(player, PaperDialogScreen(id = "help.cosmetics.effects.reset", title = text("effects-reset-title"),
            body = listOf(if (snapshot.available) panel("effects-reset-body", "count" to snapshot.activeCount.toString()) else panel("effects-unavailable")),
            buttons = if (snapshot.available && snapshot.activeCount > 0) listOf(button("confirm", "effects-reset-confirm") {
                changeEffects(player, "help.cosmetics.effects.reset", "effects-reset-title", page, returnTo) { particles.reset(player, snapshot) }
            }) else emptyList(), exitButton = back { openEffects(player, returnTo, page) }))
    }

    private fun changeEffects(player: Player, screenId: String, titleKey: String, page: Int, returnTo: () -> Unit,
                              operation: () -> CompletableFuture<ParticlePresetChange>) {
        val token = navigation.visit(player) { openEffects(player, returnTo, page) }
        show(player, PaperDialogScreen(id = screenId, title = text(titleKey), body = listOf(panel("effects-loading")),
            buttons = emptyList(), exitButton = back { openEffects(player, returnTo, page) }))
        val future = runCatching(operation).getOrElse { CompletableFuture.failedFuture(it) }
        future.whenCompleteSync(tasks) { result, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            openEffects(player, returnTo, page, if (failure == null && result?.applied == true) "effects-applied" else "effects-unconfirmed")
        }
    }

    private fun withPages(screen: PaperDialogScreen, page: Int, pages: Int, open: (Int) -> Unit): PaperDialogScreen =
        PaperDialogNavigation.bottom(screen,
            if (page > 0) PaperDialogNavigation.previous(button("previous", "previous") { open(page - 1) }, text("previous")) else null,
            if (page + 1 < pages) PaperDialogNavigation.next(button("next", "next") { open(page + 1) }, text("next")) else null)

    private fun button(id: String, key: String, vararg values: Pair<String, String>, action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), text(key, *values), width = 230, onClick = { if (active) action() })

    private fun back(action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of("back"), text("back"), width = 200, onClick = { action() })

    private fun panel(key: String, vararg values: Pair<String, String>): PaperDialogBody =
        presentation.panel(text(key, *values), 468, DialogTables.PanelSkin.PLUM)

    private fun text(key: String, vararg values: Pair<String, String>): Component = miniMessage.deserialize(
        settings.text("cosmetics-$key"), TagResolver.resolver(values.map { (name, value) -> Placeholder.unparsed(name, value) }),
    ).decoration(TextDecoration.ITALIC, false)

    private fun plain(key: String): String = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text(key))

    override fun close() { active = false; tasks.close(); particles.close() }

    companion object { private const val PAGE_SIZE = 10 }
}
