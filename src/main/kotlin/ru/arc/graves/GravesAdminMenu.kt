package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID

/** Administrator discovery has no claim or travel actions. Retrieval remains permission-checked by its service. */
internal class GravesAdminMenu(
    private val settings: GravesSettings,
    private val worldName: (String) -> Component,
    private val requestPage: (Player, Int, String?) -> Unit,
    private val openContents: (Player, UUID) -> Unit,
    private val showDialog: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit) -> Unit = { player, screen, reopen, onDismiss ->
        ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = onDismiss)
    },
    private val closeDialog: (Player) -> Unit = ArcMenus::closeDialog,
) {
    fun loading(player: Player, onDismiss: () -> Unit = {}) {
        if (!allowed(player)) return
        showDialog(player, PaperDialogScreen(id = "graves.admin", title = settings.text("admin-title"),
            body = listOf(PaperDialogBody(settings.text("admin-loading"), width = 420)),
            buttons = emptyList(), exitButton = graveCloseButton()), null, onDismiss)
    }

    /** Null means unavailable; an empty authoritative result means no current graves. */
    fun open(player: Player, records: List<NativeGraveRecord>?, page: Int = 0, serverFilter: String? = null) {
        if (!allowed(player)) return
        val now = System.currentTimeMillis()
        val active = records?.filter { it.phase == NativeGravePhase.ACTIVE && (it.expiresAt < 0 || it.expiresAt > now) &&
            (serverFilter == null || it.server == serverFilter) }?.sortedByDescending { it.createdAt }
        val pages = maxOf(1, ((active?.size ?: 0) + PAGE_SIZE - 1) / PAGE_SIZE)
        val currentPage = page.coerceIn(0, pages - 1)
        val body = settings.text(when {
            active == null -> "list-unavailable"
            active.isEmpty() -> "admin-empty"
            else -> "admin-body"
        }, "count" to (active?.size ?: 0).toString(), "page" to (currentPage + 1).toString(), "pages" to pages.toString())
        val buttons = buildList {
            active.orEmpty().drop(currentPage * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, record ->
                add(PaperDialogButton(PaperDialogActionId.of("admin_grave_$index"), settings.text("admin-entry-label",
                    "owner" to record.ownerName, "world" to plain(worldName(record.world))),
                    tooltip = settings.text("admin-entry-tooltip", "x" to record.x.toString(), "y" to record.y.toString(),
                        "z" to record.z.toString(), "expiry" to plain(graveExpiryText(settings, record.expiresAt))),
                    width = 300, onClick = {
                        if (allowed(player)) { closeDialog(player); openContents(player, record.id) }
                    }))
            }
            if (currentPage > 0) add(pageButton(player, "previous_page", "previous", currentPage - 1, serverFilter))
            if (currentPage < pages - 1) add(pageButton(player, "next_page", "next", currentPage + 1, serverFilter))
        }
        showDialog(player, PaperDialogScreen(id = "graves.admin", title = settings.text("admin-title"),
            body = listOf(PaperDialogBody(body, width = 420)), buttons = buttons, columns = 2,
            exitButton = graveCloseButton()), { if (allowed(player)) requestPage(player, currentPage, serverFilter) }, {})
    }

    private fun pageButton(player: Player, id: String, label: String, page: Int, server: String?) = PaperDialogButton(
        PaperDialogActionId.of(id), settings.text("$label-label"), settings.text("$label-tooltip"),
        onClick = { if (allowed(player)) requestPage(player, page, server) },
    )

    private fun allowed(player: Player): Boolean {
        if (!player.isOnline) return false
        if (player.hasPermission(GRAVES_ADMIN_PERMISSION)) return true
        closeDialog(player)
        player.sendMessage(settings.text("admin-denied"))
        return false
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    private companion object { const val PAGE_SIZE = 6 }
}
