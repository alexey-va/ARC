package ru.arc.iteminfo

import org.bukkit.entity.Player
import ru.arc.paper.inspection.PaperArcInspectionService

internal class ItemInfoController(
    private val preferences: (Player) -> ItemInfoPreferences,
    private val inspection: PaperArcInspectionService,
    private val suppressed: (Player) -> Boolean = { false },
) {
    fun update(player: Player) {
        if (suppressed(player)) inspection.clear(player)
        else inspection.update(player, preferences(player).toInspectionViewPreferences())
    }

    fun follow(player: Player) = inspection.follow(player)

    fun reset(player: Player) = inspection.clear(player)
}
