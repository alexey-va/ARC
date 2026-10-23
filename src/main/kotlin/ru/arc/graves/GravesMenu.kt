package ru.arc.graves

import net.kyori.adventure.text.BlockNBTComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.EntityNBTComponent
import net.kyori.adventure.text.SelectorComponent
import net.kyori.adventure.text.StorageNBTComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.TranslationArgument
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.math.BigDecimal
import java.util.UUID

/** Native, history-aware presentation for the GravesX records supplied by the owning module. */
internal class GravesMenu(
    private val settings: GravesSettings,
    private val views: (Player) -> List<GraveView>,
    private val tracking: (Player) -> UUID?,
    private val trackingEnabled: (Player) -> Boolean,
    private val toggleTracking: (Player) -> Unit,
    private val track: (Player, UUID) -> Unit,
    private val price: (Player) -> Double,
    private val teleport: (Player, GraveView, Double) -> Unit,
    private val showDialog: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit = { player, screen, reopen ->
        ArcMenus.openDialog(player, screen, reopen = reopen)
    },
    private val closeDialog: (Player) -> Unit = ArcMenus::closeDialog,
) {
    private val gson = GsonComponentSerializer.gson()
    private val plainText = PlainTextComponentSerializer.plainText()

    /** Opens inside the current dialog flow; command entry points start a new flow before calling this. */
    fun open(player: Player) = openList(player, 0)

    private fun openList(player: Player, requestedPage: Int, notice: String? = null) {
        val active = activeViews(player)
        val entries = active.orEmpty()
        val pages = pageCount(entries.size)
        val page = requestedPage.coerceIn(0, pages - 1)
        val pageEntries = entries.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val body = buildList {
            add(
                PaperDialogBody(
                    when {
                        active == null -> settings.text("list-unavailable")
                        entries.isEmpty() -> settings.text("list-empty")
                        else -> settings.text(
                            "list-body",
                            "count" to entries.size.toString(),
                            "page" to (page + 1).toString(),
                            "pages" to pages.toString(),
                        )
                    },
                    width = 420,
                ),
            )
            notice?.let { add(PaperDialogBody(settings.text(it), width = 420)) }
        }
        val buttons = buildList {
            pageEntries.forEachIndexed { index, grave ->
                add(
                    button(
                        "grave_$index",
                        settings.text(
                            "entry-label",
                            "server" to grave.server,
                            "world" to grave.world,
                            "x" to grave.x.toString(),
                            "y" to grave.y.toString(),
                            "z" to grave.z.toString(),
                        ),
                        settings.text("entry-tooltip", "expiry" to plain(expiryText(grave))),
                    ) {
                        openDetail(player, grave.id, page)
                    }.copy(width = 300),
                )
            }
            if (page > 0) add(button("previous_page", settings.text("previous-label"), settings.text("previous-tooltip")) {
                openList(player, page - 1)
            })
            if (page < pages - 1) add(button("next_page", settings.text("next-label"), settings.text("next-tooltip")) {
                openList(player, page + 1)
            })
            if (entries.isNotEmpty()) add(trackingButton(player, "tracking_toggle") {
                if (activeViews(player).isNullOrEmpty()) {
                    openList(player, page, "list-stale")
                } else {
                    toggleTracking(player)
                    openList(player, page)
                }
            })
        }

        showDialog(
            player,
            PaperDialogScreen(
                id = "graves.list",
                title = settings.text("list-title"),
                body = body,
                buttons = buttons,
                columns = 2,
            ),
            { openList(player, page) },
        )
    }

    private fun openDetail(player: Player, graveId: UUID, listPage: Int, requestedItemPage: Int = 0) {
        val grave = activeGrave(player, graveId)
        if (grave == null) {
            openList(player, listPage, "list-stale")
            return
        }
        showDetail(player, grave, listPage, requestedItemPage)
    }

    private fun showDetail(player: Player, grave: GraveView, listPage: Int, requestedItemPage: Int, notice: String? = null) {
        val itemPages = pageCount(grave.items.size)
        val itemPage = requestedItemPage.coerceIn(0, itemPages - 1)
        val itemStart = itemPage * PAGE_SIZE
        val pageItems = grave.items.drop(itemStart).take(PAGE_SIZE)
        val rows = listOf(
            settings.text("row-server") to Component.text(grave.server, NamedTextColor.WHITE),
            settings.text("row-world") to Component.text(grave.world, NamedTextColor.WHITE),
            settings.text("row-coordinates") to Component.text("${grave.x}, ${grave.y}, ${grave.z}", NamedTextColor.WHITE),
            settings.text("row-expiry") to expiryText(grave),
            settings.text("row-experience") to Component.text(grave.experience.toString(), NamedTextColor.WHITE),
        )
        val body = buildList {
            add(PaperDialogBody(settings.text("detail-body"), width = 420))
            notice?.let { add(PaperDialogBody(settings.text(it), width = 420)) }
            add(
                DialogTables.filledBody(
                    rows = rows.map { (label, value) -> listOf(label, value) },
                    headers = listOf(settings.text("row-label-heading"), settings.text("row-value-heading")),
                    width = 320,
                    columnWeights = listOf(1, 2),
                ),
            )
            add(PaperDialogBody(settings.text("detail-items-title"), width = 320))
            if (grave.items.isEmpty()) {
                add(PaperDialogBody(settings.text("items-empty"), width = 320))
            } else {
                add(
                    PaperDialogBody(
                        settings.text("items-page", "page" to (itemPage + 1).toString(), "pages" to itemPages.toString()),
                        width = 320,
                    ),
                )
                add(
                    DialogTables.body(
                        rows = pageItems.mapIndexed { index, item ->
                            Component.text((itemStart + index + 1).toString(), NamedTextColor.WHITE) to itemLine(item)
                        },
                        headers = settings.text("row-index") to settings.text("row-item"),
                        frame = DialogTables.Frame.COMMON,
                        width = 320,
                    ),
                )
            }
            if (trackedId(player) == grave.id) add(PaperDialogBody(settings.text("detail-tracked"), width = 320))
        }
        val buttons = buildList {
            if (itemPage > 0) add(button("items_previous", settings.text("previous-label"), settings.text("previous-tooltip")) {
                openDetail(player, grave.id, listPage, itemPage - 1)
            })
            if (itemPage < itemPages - 1) add(button("items_next", settings.text("next-label"), settings.text("next-tooltip")) {
                openDetail(player, grave.id, listPage, itemPage + 1)
            })
            if (trackedId(player) != grave.id) add(button("track_grave", settings.text("track-label"), settings.text("track-tooltip")) {
                if (activeGrave(player, grave.id) == null) {
                    openList(player, listPage, "list-stale")
                } else {
                    track(player, grave.id)
                    openDetail(player, grave.id, listPage, itemPage)
                }
            })
            add(trackingButton(player, "tracking_toggle") {
                if (activeGrave(player, grave.id) == null) {
                    openList(player, listPage, "list-stale")
                } else {
                    toggleTracking(player)
                    openDetail(player, grave.id, listPage, itemPage)
                }
            })
            if (notice != "teleport-unavailable") {
                add(button("teleport_grave", settings.text("teleport-label"), settings.text("teleport-tooltip")) {
                    openConfirmation(player, grave.id, listPage, itemPage)
                })
            }
        }
        showDialog(
            player,
            PaperDialogScreen(
                id = "graves.detail",
                title = settings.text("detail-title"),
                body = body,
                buttons = buttons,
                columns = 2,
            ),
            { openDetail(player, grave.id, listPage, itemPage) },
        )
    }

    private fun openConfirmation(player: Player, graveId: UUID, listPage: Int, itemPage: Int) {
        val grave = activeGrave(player, graveId)
        if (grave == null) {
            openList(player, listPage, "list-stale")
            return
        }
        val quotedPrice = runCatching { price(player) }.getOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        if (quotedPrice == null) {
            showDetail(player, grave, listPage, itemPage, "teleport-unavailable")
            return
        }
        showConfirmation(player, grave, quotedPrice, listPage, itemPage)
    }

    private fun showConfirmation(player: Player, grave: GraveView, quotedPrice: Double, listPage: Int, itemPage: Int) {
        val priceComponent = if (quotedPrice == 0.0) {
            settings.text("free-price")
        } else {
            settings.text("price-value", "price" to BigDecimal.valueOf(quotedPrice).stripTrailingZeros().toPlainString())
        }
        val body = listOf(
            PaperDialogBody(settings.text("confirm-body"), width = 420),
            DialogTables.body(
                rows = listOf(
                    settings.text("row-server") to Component.text(grave.server, NamedTextColor.WHITE),
                    settings.text("row-world") to Component.text(grave.world, NamedTextColor.WHITE),
                    settings.text("row-coordinates") to Component.text("${grave.x}, ${grave.y}, ${grave.z}", NamedTextColor.WHITE),
                    settings.text("row-price") to priceComponent,
                ),
                headers = settings.text("row-label-heading") to settings.text("row-value-heading"),
                frame = DialogTables.Frame.LEGENDARY,
                width = 320,
            ),
        )
        showDialog(
            player,
            PaperDialogScreen(
                id = "graves.teleport-confirmation",
                title = settings.text("confirm-title"),
                body = body,
                buttons = listOf(button("confirm_teleport", settings.text("confirm-label"), settings.text("confirm-tooltip")) {
                    if (activeGrave(player, grave.id) == null) {
                        openList(player, listPage, "list-stale")
                    } else {
                        closeDialog(player)
                        teleport(player, grave, quotedPrice)
                    }
                }),
                columns = 2,
            ),
            { openConfirmationForQuote(player, grave.id, quotedPrice, listPage, itemPage) },
        )
    }

    private fun openConfirmationForQuote(player: Player, graveId: UUID, quotedPrice: Double, listPage: Int, itemPage: Int) {
        val grave = activeGrave(player, graveId)
        if (grave == null) {
            openList(player, listPage, "list-stale")
        } else {
            showConfirmation(player, grave, quotedPrice, listPage, itemPage)
        }
    }

    private fun trackingButton(player: Player, id: String, action: () -> Unit): PaperDialogButton {
        val enabled = runCatching { trackingEnabled(player) }.getOrDefault(false)
        val label = if (enabled) "tracking-enabled-label" else "tracking-disabled-label"
        val tooltip = if (enabled) "tracking-enabled-tooltip" else "tracking-disabled-tooltip"
        return button(id, settings.text(label), settings.text(tooltip), action)
    }

    private fun activeViews(player: Player): List<GraveView>? = try {
        val now = System.currentTimeMillis()
        views(player).filter { it.active(now) }
    } catch (_: Exception) {
        null
    }

    private fun activeGrave(player: Player, id: UUID): GraveView? = activeViews(player)?.firstOrNull { it.id == id }

    private fun trackedId(player: Player): UUID? = runCatching { tracking(player) }.getOrNull()

    private fun expiryText(grave: GraveView): Component {
        if (grave.expiresAt < 0L) return settings.text("expiry-eternal")
        val remainingMillis = (grave.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val secondsRemaining = remainingMillis / 1_000L + if (remainingMillis % 1_000L == 0L) 0L else 1L
        if (secondsRemaining < 60L) return settings.text("expiry-time", "time" to plain(settings.text("expiry-under-minute")))
        val days = secondsRemaining / 86_400L
        val hours = (secondsRemaining % 86_400L) / 3_600L
        val minutes = (secondsRemaining % 3_600L) / 60L
        val duration = when {
            days > 0L -> "$days д. $hours ч."
            hours > 0L -> "$hours ч. $minutes мин."
            else -> "$minutes мин."
        }
        return settings.text("expiry-time", "time" to duration)
    }

    private fun itemLine(item: GraveItem): Component =
        Component.text("${item.amount} × ", NamedTextColor.WHITE).append(safeItemName(item))

    private fun safeItemName(item: GraveItem): Component = runCatching {
        withoutActions(gson.deserialize(item.nameJson))
    }.getOrElse { settings.text("item-name-unavailable") }

    private fun withoutActions(component: Component): Component {
        var sanitized = component
            .clickEvent(null)
            .hoverEvent(null)
            .insertion(null)
            .children(component.children().map(::withoutActions))

        if (component is TranslatableComponent) {
            val safeArguments = component.arguments().map { argument ->
                when (val value = argument.value()) {
                    is Component -> TranslationArgument.component(withoutActions(value))
                    is ComponentLike -> TranslationArgument.component(withoutActions(value.asComponent()))
                    else -> argument
                }
            }
            sanitized = (sanitized as TranslatableComponent).arguments(safeArguments)
        }

        when (component) {
            is SelectorComponent -> component.separator()?.let { separator ->
                sanitized = (sanitized as SelectorComponent).separator(withoutActions(separator))
            }
            is BlockNBTComponent -> component.separator()?.let { separator ->
                sanitized = (sanitized as BlockNBTComponent).separator(withoutActions(separator))
            }
            is EntityNBTComponent -> component.separator()?.let { separator ->
                sanitized = (sanitized as EntityNBTComponent).separator(withoutActions(separator))
            }
            is StorageNBTComponent -> component.separator()?.let { separator ->
                sanitized = (sanitized as StorageNBTComponent).separator(withoutActions(separator))
            }
        }
        return sanitized
    }

    private fun button(
        id: String,
        label: Component,
        tooltip: Component,
        action: () -> Unit,
    ) = PaperDialogButton(
        PaperDialogActionId.of(id),
        label,
        tooltip,
        onClick = { action() },
    )

    private fun plain(component: Component): String = plainText.serialize(component)

    private fun pageCount(size: Int): Int = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)

    private companion object {
        const val PAGE_SIZE = 6
    }
}
