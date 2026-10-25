package ru.arc.gui

import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class MenuEscapeBehaviorTest {
    @Test
    fun `declared Back stays in a 200 unit footer with no invented grid action`() {
        val exit = PaperDialogButton(
            id = PaperDialogActionId.of("back"),
            label = Component.text("Back"),
            width = 200,
            onClick = {},
        )
        val close = PaperDialogButton(
            id = PaperDialogActionId.of("close"),
            label = Component.text("Close"),
            width = 200,
            onClick = {},
        )

        val prepared = MenuEscapeBehavior.apply(
            PaperDialogScreen(Component.text("Loading"), buttons = emptyList(), exitButton = exit),
            back = false,
            closeButton = close,
        )

        prepared.buttons shouldBe emptyList()
        prepared.exitButton shouldBe exit
        MenuEscapeBehavior.footerGoesBack(prepared, false) shouldBe true

        val notice = MenuEscapeBehavior.apply(
            PaperDialogScreen(Component.text("Loading"), buttons = emptyList()),
            back = false,
            closeButton = close,
        )
        notice.buttons shouldBe emptyList()
        notice.exitButton shouldBe close
    }
}
