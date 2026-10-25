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

    /** Returning footers remain below the grid; native Escape runs that same action. */
    fun footerGoesBack(screen: PaperDialogScreen, preference: Boolean): Boolean =
        screen.exitButton?.let { !it.closeDialogBeforeAction } ?: preference

    fun apply(
        screen: PaperDialogScreen,
        back: Boolean,
        closeButton: PaperDialogButton? = null,
    ): PaperDialogScreen {
        val footer = screen.exitButton ?: closeButton.takeUnless { back }
        return if (footer == null) screen else screen.copy(exitButton = footer.copy(width = 200))
    }
}
