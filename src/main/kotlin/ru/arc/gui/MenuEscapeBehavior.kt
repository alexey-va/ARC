package ru.arc.gui

import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogButton

/** Native Escape runs the footer action; ordinary navigation buttons do not. */
object MenuEscapeBehavior {
    const val META_KEY = "arc-menu-escape"
    fun goesBack(player: Player): Boolean = HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, META_KEY) == "back"

    fun apply(screen: PaperDialogScreen, back: Boolean, closeButton: PaperDialogButton? = null): PaperDialogScreen {
        if (back) return screen
        val exit = screen.exitButton
        val regularExit = exit?.let { if (closeButton == null) it else it.copy(width = screen.buttons.first().width) }
        return screen.copy(buttons = screen.buttons + listOfNotNull(regularExit), exitButton = closeButton)
    }
}
