package ru.arc.landsui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.actionpanel.ActionPanelAction
import ru.arc.paper.actionpanel.ActionPanelButton
import ru.arc.paper.actionpanel.ActionPanelController
import ru.arc.paper.actionpanel.ActionPanelFollowSettings
import ru.arc.paper.actionpanel.ActionPanelFrame
import ru.arc.paper.actionpanel.ActionPanelInputProfile
import ru.arc.paper.actionpanel.ActionPanelLabel
import ru.arc.paper.actionpanel.ActionPanelLayout
import ru.arc.paper.actionpanel.ActionPanelLayouts
import ru.arc.paper.actionpanel.ActionPanelOptions
import ru.arc.paper.actionpanel.ActionPanelPlacement
import ru.arc.paper.actionpanel.ActionPanelTextStyle
import ru.arc.paper.actionpanel.PaperActionPanels
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

/** Lands owns eligibility and gestures; Core owns the six-action viewer-private world panel. */
internal class ClaimLandMenu(
    plugin: Plugin,
    private val title: (String) -> Component,
    private val labels: Map<LandsUiPanelAction, Component>,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "claim-land-menu"),
) : AutoCloseable {
    private data class View(val worldId: UUID, val landId: String, val heading: Component)

    private val views = mutableMapOf<UUID, View>()
    private var closed = false

    init {
        require(labels.keys == LandsUiPanelAction.entries.toSet()) {
            "Claim land menu needs a localized label for each Lands action"
        }
    }

    private val panels = PaperActionPanels(plugin, displays, object : ActionPanelController<String, LandsUiPanelAction>() {
        override fun frame(player: Player): ActionPanelFrame<String, LandsUiPanelAction>? {
            val view = views[player.uniqueId]?.takeIf { it.worldId == player.world.uid } ?: return null
            return ActionPanelFrame(
                view.landId,
                ActionPanelPlacement.FollowView(view.worldId, FOLLOW),
                claimLandMenuLayout(view.heading, labels),
                true,
                ActionPanelOptions(ActionPanelInputProfile.EXTERNAL_RAY, 4.0, true, true),
                null,
            )
        }

        // ClaimBlockTool rechecks the native held item, current land and trust before opening an action.
        // It also owns main/off-hand and arm-swing deduplication. This profile creates no native targets.
        override fun activate(player: Player, context: String, action: LandsUiPanelAction) = Unit
    })

    fun show(player: Player, landId: String, landName: String) {
        check(!closed) { "Claim land menu is closed" }
        if (views[player.uniqueId]?.worldId?.let { it != player.world.uid } == true) hide(player.uniqueId)
        views[player.uniqueId] = View(player.world.uid, landId, title(landName))
        panels.refresh(player)
    }

    fun hide(playerId: UUID) {
        views.remove(playerId)
        panels.clear(playerId)
    }

    fun contains(playerId: UUID): Boolean = panels.contains(playerId)

    fun isLookingAt(player: Player): Boolean =
        views[player.uniqueId]?.worldId == player.world.uid && panels.isLookingAt(player.uniqueId)

    /** Resolve the current camera ray without taking over the claim tool's native gesture routing. */
    fun target(player: Player): LandsUiPanelAction? = panels.target(player)?.action

    fun landId(playerId: UUID): String? = views[playerId]?.landId?.takeIf { contains(playerId) }

    override fun close() {
        if (closed) return
        closed = true
        views.clear()
        panels.close()
        displays.close()
    }

    private companion object {
        val FOLLOW = ActionPanelFollowSettings(2.4, 0.0, 0.0, 1.25, 0.03)
    }
}

/** Retains the existing Lands card footprint, line widths and two-tick transform interpolation. */
internal fun claimLandMenuLayout(
    heading: Component,
    labels: Map<LandsUiPanelAction, Component>,
): ActionPanelLayout<LandsUiPanelAction> {
    val grid = ActionPanelLayouts.twoColumn(
        LandsUiPanelAction.entries.map { ActionPanelAction(it, labels.getValue(it)) }, heading,
    )
    fun style(source: ActionPanelTextStyle) = ActionPanelTextStyle(
        source.scale, source.lineWidth, source.displayWidth, source.displayHeight, 0.025, -1, 2,
    )
    return ActionPanelLayout(
        grid.buttons.map { ActionPanelButton(it.action, it.rect, it.text, style(it.style), it.visible, it.enabled) },
        grid.labels.map { ActionPanelLabel(it.key, it.rect, it.text, style(it.style), true) },
    )
}
