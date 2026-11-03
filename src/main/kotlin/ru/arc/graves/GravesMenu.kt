package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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

/** Command navigation and remote travel. Physical tombs open [GravesContentsMenu] directly. */
internal class GravesMenu(
    private val settings: GravesSettings,
    private val views: (Player) -> List<GraveView>?,
    private val price: (Player) -> Double,
    private val teleport: (Player, GraveView, Double) -> Unit,
    private val worldName: (String) -> Component,
    private val openContents: (Player, UUID) -> Unit,
    private val showDialog: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit = { player, screen, reopen ->
        ArcMenus.openDialog(player, screen, reopen = reopen)
    },
    private val closeDialog: (Player) -> Unit = ArcMenus::closeDialog,
) {
    fun open(player: Player) = openList(player, 0)

    fun openDetail(player: Player, graveId: UUID) = openDetail(player, graveId, 0)

    private fun openList(player: Player, requestedPage: Int, notice: String? = null) {
        val active = activeViews(player)
        val entries = active.orEmpty()
        val pages = pageCount(entries.size)
        val page = requestedPage.coerceIn(0, pages - 1)
        val body = buildList {
            add(PaperDialogBody(when {
                active == null -> settings.text("list-unavailable")
                entries.isEmpty() -> settings.text("list-empty")
                else -> settings.text("list-body", "count" to entries.size.toString(),
                    "page" to (page + 1).toString(), "pages" to pages.toString())
            }, width = 420))
            if (active != null) notice?.let { add(PaperDialogBody(settings.text(it), width = 420)) }
        }
        val buttons = buildList {
            entries.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, grave ->
                add(button("grave_$index", settings.text("entry-location-label",
                    "world" to plain(worldName(grave.world)), "x" to grave.x.toString(),
                    "y" to grave.y.toString(), "z" to grave.z.toString()),
                    settings.text("entry-tooltip", "expiry" to plain(graveExpiryText(settings, grave.expiresAt)))) {
                    openDetail(player, grave.id, page)
                }.copy(width = 300))
            }
            if (page > 0) add(button("previous_page", settings.text("previous-label"), settings.text("previous-tooltip")) {
                openList(player, page - 1)
            })
            if (page < pages - 1) add(button("next_page", settings.text("next-label"), settings.text("next-tooltip")) {
                openList(player, page + 1)
            })
        }
        showDialog(player, PaperDialogScreen(id = "graves.list", title = settings.text("list-title"),
            body = body, buttons = buttons, columns = 2, exitButton = graveCloseButton()), { openList(player, page) })
    }

    private fun openDetail(player: Player, graveId: UUID, listPage: Int, notice: String? = null) {
        val grave = activeGrave(player, graveId)
        if (grave == null) { openList(player, listPage, "list-stale"); return }
        val body = buildList {
            add(PaperDialogBody(settings.text("detail-body"), width = 420))
            notice?.let { add(PaperDialogBody(settings.text(it), width = 420)) }
            add(DialogTables.filledBody(
                rows = listOf(
                    listOf(settings.text("row-world"), worldName(grave.world)),
                    listOf(settings.text("row-coordinates"), Component.text("${grave.x}, ${grave.y}, ${grave.z}", NamedTextColor.WHITE)),
                    listOf(settings.text("row-expiry"), graveExpiryText(settings, grave.expiresAt)),
                    listOf(settings.text("row-experience"), Component.text(grave.experience.toString(), NamedTextColor.WHITE)),
                ), headers = listOf(settings.text("row-label-heading"), settings.text("row-value-heading")),
                width = 320, columnWeights = listOf(1, 2),
            ))
        }
        val buttons = buildList {
            add(button("contents", settings.text("contents-label"), settings.text("contents-tooltip")) {
                if (activeGrave(player, grave.id) == null) openList(player, listPage, "list-stale")
                else { closeDialog(player); openContents(player, grave.id) }
            })
            if (notice != "teleport-unavailable") add(button("teleport_grave", settings.text("teleport-label"), settings.text("teleport-tooltip")) {
                openConfirmation(player, grave.id, listPage)
            })
            add(button("back_to_list", settings.text("back-label"), settings.text("back-tooltip")) { openList(player, listPage) })
        }
        showDialog(player, PaperDialogScreen(id = "graves.detail", title = settings.text("detail-title"),
            body = body, buttons = buttons, columns = 2, exitButton = graveCloseButton()),
            { openDetail(player, grave.id, listPage) })
    }

    private fun openConfirmation(player: Player, graveId: UUID, listPage: Int, quotedPrice: Double? = null) {
        val grave = activeGrave(player, graveId)
        if (grave == null) { openList(player, listPage, "list-stale"); return }
        val quote = quotedPrice ?: runCatching { price(player) }.getOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        if (quote == null) { openDetail(player, graveId, listPage, "teleport-unavailable"); return }
        val priceComponent = if (quote == 0.0) settings.text("free-price") else settings.text("price-value",
            "price" to BigDecimal.valueOf(quote).stripTrailingZeros().toPlainString())
        showDialog(player, PaperDialogScreen(
            id = "graves.teleport-confirmation", title = settings.text("confirm-title"),
            body = listOf(PaperDialogBody(settings.text("confirm-body"), width = 420), DialogTables.body(
                rows = listOf(
                    settings.text("row-world") to worldName(grave.world),
                    settings.text("row-coordinates") to Component.text("${grave.x}, ${grave.y}, ${grave.z}", NamedTextColor.WHITE),
                    settings.text("row-price") to priceComponent,
                ), headers = settings.text("row-label-heading") to settings.text("row-value-heading"),
                frame = DialogTables.Frame.LEGENDARY, width = 320,
            )),
            buttons = listOf(
                button("confirm_teleport", settings.text("confirm-label"), settings.text("confirm-tooltip")) {
                    val current = activeGrave(player, grave.id)
                    if (current == null) openList(player, listPage, "list-stale")
                    else { closeDialog(player); teleport(player, current, quote) }
                },
                button("back_to_detail", settings.text("back-label"), settings.text("back-tooltip")) {
                    openDetail(player, grave.id, listPage)
                },
            ), columns = 2, exitButton = graveCloseButton(),
        ), { openConfirmation(player, grave.id, listPage, quote) })
    }

    private fun activeViews(player: Player): List<GraveView>? = runCatching {
        val now = System.currentTimeMillis()
        views(player)?.filter { it.ownerId == player.uniqueId && it.active(now) }
    }.getOrNull()

    private fun activeGrave(player: Player, id: UUID): GraveView? = activeViews(player)?.firstOrNull { it.id == id }

    private fun button(id: String, label: Component, tooltip: Component, action: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, onClick = { action() })

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    private fun pageCount(size: Int): Int = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)

    private companion object { const val PAGE_SIZE = 6 }
}

/** Literal Russian caption: the observed native client displays the old translation key verbatim. */
internal fun graveCloseButton() = PaperDialogButton(
    PaperDialogActionId.of("close_graves"), Component.text("Закрыть", NamedTextColor.WHITE),
    tooltip = Component.text("Закрыть", NamedTextColor.WHITE), width = 200,
    closeDialogBeforeAction = true, onClick = {},
)

internal fun graveExpiryText(settings: GravesSettings, expiresAt: Long, now: Long = System.currentTimeMillis()): Component {
    if (expiresAt < 0L) return settings.text("expiry-eternal")
    val remaining = (expiresAt - now).coerceAtLeast(0L)
    val seconds = remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    if (seconds < 60L) return settings.text("expiry-time", "time" to
        PlainTextComponentSerializer.plainText().serialize(settings.text("expiry-under-minute")))
    val days = seconds / 86_400L
    val hours = seconds % 86_400L / 3_600L
    val minutes = seconds % 3_600L / 60L
    val duration = when {
        days > 0L -> "$days д. $hours ч."
        hours > 0L -> "$hours ч. $minutes мин."
        else -> "$minutes мин."
    }
    return settings.text("expiry-time", "time" to duration)
}
