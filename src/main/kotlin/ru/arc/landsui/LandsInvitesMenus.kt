package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.Logging.warn
import java.util.UUID

/** The gateway revalidates the exact invitation before answering it. */
internal class LandsInvitesMenus(
    private val settings: LandsUiSettings,
    private val gateway: LandsUiGateway,
    private val openRoot: (Player) -> Unit,
) {
    private val tasks = LifecycleTaskScope()
    private val visits = mutableMapOf<UUID, Long>()
    private val pending = mutableSetOf<UUID>()
    private var sequence = 0L
    private var active = true

    fun open(player: Player) = openList(player)

    fun close() {
        active = false
        visits.clear()
        pending.clear()
        tasks.close()
    }

    private fun openList(player: Player, requestedPage: Int = 0, feedback: Component? = null) {
        if (!active) return
        val permitted = player.hasPermission("lands.command.invites.menu")
        val invitations = if (permitted) gateway.invitations(player) else emptyList()
        val pages = ((invitations.size + settings.maxListedPlayers - 1) / settings.maxListedPlayers).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val waiting = player.uniqueId in pending
        val body = mutableListOf(facts(listOf(
            text("invites-count-label") to text("invites-value", "value" to invitations.size.toString()),
            text("invites-page-label") to text("invites-page-value", "page" to (page + 1).toString(), "pages" to pages.toString()),
        )))
        feedback?.let { body += spacer(); body += panel(it) }
        when {
            !permitted -> { body += spacer(); body += panel(text("invites-denied")) }
            waiting -> { body += spacer(); body += panel(text("invites-pending")) }
            invitations.isEmpty() -> { body += spacer(); body += panel(text("invites-empty")) }
        }
        show(player, PaperDialogScreen(
            id = "lands.invites", title = text("invites-title"), body = body,
            buttons = buildList {
                invitations.drop(page * settings.maxListedPlayers).take(settings.maxListedPlayers).forEachIndexed { index, invitation ->
                    add(PaperDialogButton(
                        PaperDialogActionId.of("invite_$index"),
                        text("invites-entry", "land" to invitation.landName, "intent" to intent(invitation)),
                        text("invites-entry-tooltip", "sender" to sender(invitation)), width = 230,
                        onClick = { details(player, invitation, page) },
                    ))
                }
                if (permitted) add(button("refresh", "invites-refresh") { openList(player, page) })
                if (page > 0) add(button("previous", "previous-page") { openList(player, page - 1) })
                if (page + 1 < pages) add(button("next", "next-page") { openList(player, page + 1) })
            },
            exitButton = back { openRoot(player) }, columns = 2,
        ), reopen = { openList(player, page) })
    }

    private fun details(player: Player, invitation: LandsUiInvitation, page: Int) {
        if (!active) return
        if (!gateway.invitations(player).contains(invitation)) {
            openList(player, page, text("invites-stale"))
            return
        }
        val waiting = player.uniqueId in pending
        show(player, detailScreen(player, invitation, page, waiting), reopen = { details(player, invitation, page) })
    }

    private fun detailScreen(player: Player, invitation: LandsUiInvitation, page: Int, waiting: Boolean): PaperDialogScreen {
        val body = mutableListOf(invitationFacts(invitation), spacer(), panel(text(
            "invites-${invitation.intent.name.lowercase(java.util.Locale.ROOT)}-body", "land" to invitation.landName,
        )))
        if (waiting) { body += spacer(); body += panel(text("invites-pending")) }
        return PaperDialogScreen(
            id = "lands.invites.detail", title = text("invites-detail-title"), body = body,
            buttons = if (waiting) emptyList() else buildList {
                if (player.hasPermission("lands.command.invites.accept")) when (invitation.intent) {
                    LandsUiInviteIntent.TRUST -> add(button("accept", "invites-accept") { respond(player, invitation, page, true) })
                    LandsUiInviteIntent.OWNER -> add(button("review_owner", "invites-review-owner") { confirm(player, invitation, page) })
                    LandsUiInviteIntent.MERGE -> add(button("review_merge", "invites-review-merge") { confirm(player, invitation, page) })
                }
                if (player.hasPermission("lands.command.invites.decline")) {
                    add(button("decline", "invites-decline") { respond(player, invitation, page, false) })
                }
            },
            exitButton = back { openList(player, page) }, columns = 2,
        )
    }

    private fun confirm(player: Player, invitation: LandsUiInvitation, page: Int, enteredName: String = "", feedback: Component? = null) {
        if (!active) return
        val merge = invitation.intent == LandsUiInviteIntent.MERGE
        val body = mutableListOf(invitationFacts(invitation), spacer(),
            panel(text(if (merge) "invites-merge-confirm-body" else "invites-owner-confirm-body", "land" to invitation.landName)))
        feedback?.let { body += spacer(); body += panel(it) }
        show(player, PaperDialogScreen(
            id = "lands.invites.confirm", title = text(if (merge) "invites-merge-title" else "invites-owner-title"), body = body,
            inputs = if (merge) listOf(PaperDialogTextInput(LAND_NAME, text("invites-merge-name-input"), initial = enteredName, maxLength = 64)) else emptyList(),
            buttons = listOf(PaperDialogButton(
                PaperDialogActionId.of("confirm"), text(if (merge) "invites-merge-confirm" else "invites-owner-confirm"), width = 230,
                onClick = { context ->
                    val submitted = context.text(LAND_NAME).orEmpty()
                    if (merge && !submitted.trim().equals(invitation.landName, ignoreCase = true)) {
                        confirm(player, invitation, page, submitted, text("invites-merge-name-mismatch"))
                    } else respond(player, invitation, page, true)
                },
            )),
            exitButton = back { details(player, invitation, page) }, columns = 1,
        ))
    }

    private fun respond(player: Player, invitation: LandsUiInvitation, page: Int, accept: Boolean) {
        if (!active || !pending.add(player.uniqueId)) return
        val playerId = player.uniqueId
        val visit = show(player, detailScreen(player, invitation, page, waiting = true), reopen = { details(player, invitation, page) })
        val response = try {
            gateway.respondToInvitation(player, invitation, accept)
        } catch (failure: Exception) {
            pending.remove(playerId)
            warn("Lands invitation response failed player={} land={} accept={}", playerId, invitation.landId, accept, failure)
            openList(player, page, text("invites-failed"))
            return
        }
        response.whenComplete { _, failure ->
            if (failure != null) warn("Lands invitation response failed asynchronously player={} land={} accept={}",
                playerId, invitation.landId, accept, failure)
        }.whenCompleteSync(tasks) { result, failure ->
            pending.remove(playerId)
            if (!active || visits[playerId] != visit || !player.isOnline) return@whenCompleteSync
            if (failure == null && result == LandsUiChangeResult.DISPATCHED) {
                player.sendMessage(text("invites-response-sent"))
                ArcMenus.closeDialog(player)
                return@whenCompleteSync
            }
            val key = if (failure != null) "invites-failed" else when (result) {
                LandsUiChangeResult.APPLIED -> if (accept) "invites-response-sent" else "invites-declined"
                LandsUiChangeResult.DISPATCHED -> "invites-response-sent"
                LandsUiChangeResult.DENIED -> "invites-response-denied"
                LandsUiChangeResult.NOT_FOUND, LandsUiChangeResult.STALE -> "invites-stale"
                else -> "invites-failed"
            }
            openList(player, page, text(key))
        }
    }

    private fun invitationFacts(invitation: LandsUiInvitation) = facts(listOf(
        text("invites-land-label") to text("invites-value", "value" to invitation.landName),
        text("invites-sender-label") to text("invites-value", "value" to sender(invitation)),
        text("invites-intent-label") to text("invites-value", "value" to intent(invitation)),
    ))

    private fun sender(invitation: LandsUiInvitation) = gateway.playerName(invitation.senderId) ?: settings.text("invites-unknown-sender")
    private fun intent(invitation: LandsUiInvitation) = settings.text("invites-intent-${invitation.intent.name.lowercase(java.util.Locale.ROOT)}")
    private fun facts(rows: List<Pair<Component, Component>>) = DialogTables.filledBody(rows.map { listOf(it.first, it.second) }, null, 320, listOf(1, 2))
    private fun panel(component: Component) = DialogTables.filledBody(listOf(listOf(component)), null, 320, listOf(1))
    private fun spacer() = PaperDialogBody(Component.text(" "), width = 320)
    private fun button(id: String, key: String, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), text(key), width = 230, onClick = { action() },
    )
    private fun back(action: () -> Unit) = button("back", "back-label", action).copy(width = 200)

    private fun show(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null): Long {
        val id = player.uniqueId
        val visit = ++sequence
        visits[id] = visit
        ArcMenus.openDialog(player, screen,
            closeButton = button("close", "close-label") {}.copy(width = 200, closeDialogBeforeAction = true),
            reopen = reopen, onDismiss = { visits.remove(id, visit) },
        )
        return visit
    }

    private fun text(key: String, vararg values: Pair<String, String>): Component = MiniMessage.miniMessage()
        .deserialize(settings.text(key), *values.map { (name, value) -> Placeholder.component(name, Component.text(value)) }.toTypedArray())
        .decoration(TextDecoration.ITALIC, false)

    private companion object {
        val LAND_NAME = PaperDialogInputId.of("land_name")
    }
}
