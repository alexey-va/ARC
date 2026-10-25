package ru.arc.gui

import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogButton

/** Native Escape runs the footer action; ordinary navigation buttons do not. */
object MenuEscapeBehavior {
    const val META_KEY = "arc-menu-escape"
    fun goesBack(player: Player): Boolean = goesBack(HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, META_KEY))
    internal fun goesBack(meta: String?): Boolean = meta != "close"

    /** A declared returning footer stays at the bottom; native Escape shares that footer action. */
    fun footerGoesBack(screen: PaperDialogScreen, preference: Boolean): Boolean =
        screen.exitButton?.let { !it.closeDialogBeforeAction } ?: preference

    fun apply(screen: PaperDialogScreen, back: Boolean, closeButton: PaperDialogButton? = null): PaperDialogScreen {
        val footer = screen.exitButton ?: closeButton.takeUnless { back }
        return if (footer == null) screen else screen.copy(exitButton = footer.copy(width = 200))
    }
}
