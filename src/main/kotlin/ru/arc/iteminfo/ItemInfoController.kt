package ru.arc.iteminfo

import org.bukkit.entity.Player
import ru.arc.chestpreview.ChestPreviewIcons
import ru.arc.chestpreview.ChestPreviewProvider
import ru.arc.chestpreview.ChestPreviewSettings
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.inspection.PaperArcInspectionService

internal class ItemInfoController(
    private val preferences: (Player) -> ItemInfoPreferences,
    private val inspection: PaperArcInspectionService,
    private val suppressed: (Player) -> Boolean = { false },
    private val chestPreview: ChestPreviewProvider? = null,
    private val chestIcons: ChestPreviewIcons? = null,
    private val chestSettings: (Player) -> ChestPreviewSettings = { ChestPreviewSettings() },
) {
    fun update(player: Player) {
        if (suppressed(player)) {
            reset(player)
            return
        }
        val selected = preferences(player).toInspectionViewPreferences()
        try {
            if (chestPreview == null) inspection.update(player, selected)
            else {
                val options = chestSettings(player)
                // Resolve through the same priority arbitration even when ordinary item info
                // is off. Clear that temporary view before the packet owner's next snapshot.
                val arbitration = if (options.enabled && player.hasPermission("arc.chest-preview") && selected.mode == InspectionViewMode.OFF)
                    selected.copy(mode = InspectionViewMode.HOLOGRAM) else selected
                val frame = chestPreview.selectedFrame(player) { inspection.update(player, arbitration) }
                if (selected.mode == InspectionViewMode.OFF) inspection.clear(player)
                chestIcons?.update(player, frame.takeIf { options.enabled }, options.scale, options)
            }
        } catch (failure: Exception) {
            chestIcons?.clear(player)
            throw failure
        }
    }

    fun follow(player: Player) = inspection.follow(player)

    fun reset(player: Player) {
        chestIcons?.clear(player)
        inspection.clear(player)
    }
}
