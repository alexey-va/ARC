package ru.arc.gui

import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogScreen

/** Native Escape runs the footer action; ordinary navigation buttons do not. */
object MenuEscapeBehavior {
    const val META_KEY = "arc-menu-escape"
    fun goesBack(player: Player): Boolean = HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, META_KEY) == "back"

    fun apply(screen: PaperDialogScreen, back: Boolean): PaperDialogScreen {
        val exit = screen.exitButton ?: return screen
        return if (back) screen else screen.copy(buttons = screen.buttons + exit, exitButton = null)
    }
}
