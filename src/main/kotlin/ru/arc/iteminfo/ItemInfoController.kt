package ru.arc.iteminfo

import org.bukkit.entity.Player
import ru.arc.chestpreview.ChestPreviewIcons
import ru.arc.chestpreview.ChestPreviewProvider
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.inspection.PaperArcInspectionService

internal class ItemInfoController(
    private val preferences: (Player) -> ItemInfoPreferences,
    private val inspection: PaperArcInspectionService,
    private val suppressed: (Player) -> Boolean = { false },
    private val chestPreview: ChestPreviewProvider? = null,
    private val chestIcons: ChestPreviewIcons? = null,
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
                val frame = chestPreview.selectedFrame(player) { inspection.update(player, selected) }
                chestIcons?.update(player, frame.takeIf { selected.mode == InspectionViewMode.HOLOGRAM }, selected.scale)
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
