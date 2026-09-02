package ru.arc.scheduled.guis

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

object ScheduledCommandsGuiFactory {
    fun openList(player: Player) {
        val settings = ScheduledCommandsManager.settings()
        val entries = settings.entries().map { entryListItem(player, it) }
        ArcMenus.open(
            player,
            ArcMenuSchema.SCHEDULED_LIST,
            TextUtil.mm(settings.guiTitle, true),
            elements = mapOf(
                "refresh" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.SCHEDULED_LIST, "refresh")) {
                    openList(it)
                },
            ),
            regions = mapOf(ArcMenuSchema.SCHEDULED_ENTRIES to entries),
        )
    }

    fun openEditor(
        player: Player,
        entryId: String,
    ) {
        val entry = ScheduledCommandsManager.settings().entry(entryId) ?: return
        EditScheduledCommandGui.open(player, entry)
    }

    private fun entryListItem(
        player: Player,
        entry: ScheduledCommandEntry,
    ) = ArcMenus.entry(
        ArcMenus.item(
            if (entry.enabled) "scheduled-entry-enabled" else "scheduled-entry-disabled",
            PaperMenuItemRenderContext(
                values = mapOf(
                    "id" to Component.text(entry.id),
                    "schedule" to Component.text(entry.schedule.describe()),
                    "command" to Component.text(entry.command),
                    "servers" to Component.text(entry.serversLabel()),
                    "action" to TextUtil.mm("<yellow>Нажмите для редактирования", true),
                ),
            ),
        ),
    ) { openEditor(player, entry.id) }
}
