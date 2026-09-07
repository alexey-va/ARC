package ru.arc.gui

import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class MenuEscapeBehaviorTest {
    @Test
    fun `close preference keeps declared exit width when action grid is empty`() {
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

        prepared.buttons.single().width shouldBe exit.width
        prepared.exitButton shouldBe close

        val notice = MenuEscapeBehavior.apply(
            PaperDialogScreen(Component.text("Loading"), buttons = emptyList()),
            back = false,
            closeButton = close,
        )
        notice.buttons shouldBe emptyList()
        notice.exitButton shouldBe close
    }
}
