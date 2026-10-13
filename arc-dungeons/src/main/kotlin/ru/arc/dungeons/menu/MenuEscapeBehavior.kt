package ru.arc.dungeons.menu

import org.bukkit.entity.Player
import ru.arc.dungeons.api.ArcDungeonNavigation
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Native Escape runs the footer action; ordinary navigation buttons do not. */
object MenuEscapeBehavior {
    const val META_KEY = "arc-menu-escape"

    /** Missing navigation service keeps the existing default: Escape follows dialog history. */
    fun goesBack(player: Player): Boolean =
        player.server.servicesManager.load(ArcDungeonNavigation::class.java)
            ?.escapeGoesBack(player.uniqueId)
            ?: true

    fun apply(
        screen: PaperDialogScreen,
        back: Boolean,
        closeButton: PaperDialogButton? = null,
    ): PaperDialogScreen {
        if (back) return screen
        val exit = screen.exitButton
        val regularExit = exit?.let {
            if (closeButton == null) it
            else it.copy(width = screen.buttons.firstOrNull()?.width ?: it.width)
        }
        return screen.copy(buttons = screen.buttons + listOfNotNull(regularExit), exitButton = closeButton)
    }
}
