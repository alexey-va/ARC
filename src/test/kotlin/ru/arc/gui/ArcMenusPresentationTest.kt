package ru.arc.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime

class ArcMenusPresentationTest : FreeSpec({
    fun button(id: String, width: Int = 230, close: Boolean = false, action: () -> Unit = {}) = PaperDialogButton(
        PaperDialogActionId.of(id), Component.text("Кнопка $id"), Component.text("Подсказка $id"), width,
        closeDialogBeforeAction = close, onClick = { action() },
    )
    fun decorated(button: PaperDialogButton) = PlainTextComponentSerializer.plainText().serialize(button.label)
        .codePoints().anyMatch { it in 0xF9000..0xF9FFF }
    fun icons(button: PaperDialogButton) = PlainTextComponentSerializer.plainText().serialize(button.label)
        .codePoints().toArray().filter { it in 0xF9060..0xF907F }
    fun close() = button("close_menu", 200, close = true)
    val content = PaperDialogBody(Component.text("Фразы игрока не меняются"), 468)

    beforeEach { ArcMenus.configureDialogDecoration("") }
    afterEach { ArcMenus.configureDialogDecoration("") }

    "empty font preserves native screens and leaves automatic Back to the runtime" {
        val choice = button("phrase_0", 299)
        val previous = button("previous", 299)
        val screen = PaperDialogScreen(Component.text("Каталог"), body = listOf(content),
            buttons = listOf(choice, previous), columns = 2, id = "messages.catalog.join")

        ArcMenus.prepareDialog(screen, true, close()) shouldBe screen
        val closing = ArcMenus.prepareDialog(screen, false, close())
        closing.buttons shouldBe screen.buttons
        closing.exitButton!!.id.value shouldBe "close_menu"
        (closing.buttons + closing.exitButton!!).any(::decorated) shouldBe false
    }

    "ARC child without a declared exit gets a decorated unique Back while content and callbacks survive" {
        ArcMenus.configureDialogDecoration("arc:dialog_ui")
        var selected = 0
        val choice = button("phrase_0", 299) { selected++ }
        val previous = button("previous", 299)
        val next = button("next", 299)
        val screen = PaperDialogScreen(Component.text("Каталог"), body = listOf(content), buttons = listOf(
            choice, button("arc_history_exit_0"), button("arc_history_exit_1"), previous, next,
        ), columns = 2, id = "messages.catalog.join")

        val prepared = ArcMenus.prepareDialog(screen, true, close())
        prepared.title shouldBe screen.title
        prepared.body shouldBe screen.body
        prepared.columns shouldBe 2
        prepared.id shouldBe screen.id
        prepared.buttons.first() shouldBe choice
        prepared.buttons.map { it.id } shouldBe screen.buttons.map { it.id }
        prepared.buttons.zip(screen.buttons).forEach { (actual, original) ->
            (actual.onClick === original.onClick) shouldBe true
            actual.tooltip shouldBe original.tooltip
            actual.width shouldBe original.width
        }
        prepared.buttons.first().onClick.handle(mockk<PaperDialogClickContext>())
        selected shouldBe 1
        val footer = prepared.exitButton!!
        footer.id.value shouldBe "arc_history_exit_2"
        footer.width shouldBe 200
        footer.closeDialogBeforeAction shouldBe false
        decorated(footer) shouldBe true
        val paging = prepared.buttons.associateBy { it.id.value }
        icons(paging.getValue("previous")).isNotEmpty() shouldBe true
        icons(paging.getValue("next")).isNotEmpty() shouldBe true
        (icons(paging.getValue("previous")) != icons(paging.getValue("next"))) shouldBe true
    }

    "Escape close frames the moved Back at its adopted grid width and keeps both actions" {
        ArcMenus.configureDialogDecoration("arc:dialog_ui")
        val called = mutableListOf<String>()
        val choice = button("phrase_0", 299)
        val back = button("edit", 200) { called += "back" }
        val close = button("close_menu", 200, close = true) { called += "close" }
        val screen = PaperDialogScreen(Component.text("Предпросмотр"), body = listOf(content),
            buttons = listOf(choice), exitButton = back, id = "messages.preview.join")

        val prepared = ArcMenus.prepareDialog(screen, false, close)
        prepared.buttons.first() shouldBe choice
        val moved = prepared.buttons.last()
        moved.id shouldBe back.id
        moved.width shouldBe 299
        moved.tooltip shouldBe back.tooltip
        moved.closeDialogBeforeAction shouldBe false
        (moved.onClick === back.onClick) shouldBe true
        decorated(moved) shouldBe true
        val footer = prepared.exitButton!!
        footer.id shouldBe close.id
        footer.width shouldBe 200
        footer.closeDialogBeforeAction shouldBe true
        (footer.onClick === close.onClick) shouldBe true
        decorated(footer) shouldBe true
        (icons(moved) != icons(footer)) shouldBe true
        val context = mockk<PaperDialogClickContext>()
        moved.onClick.handle(context)
        footer.onClick.handle(context)
        called shouldBe listOf("back", "close")
    }

    "explicit Back footer stays below the grid and preserves its callback even with close preference" {
        for (font in listOf("", "arc:dialog_ui")) {
            ArcMenus.configureDialogDecoration(font)
            var returned = 0
            val back = button("root", 200) { returned++ }
            val original = PaperDialogScreen(Component.text("Профиль"),
                buttons = listOf(button("now_homes"), button("now_lands")), exitButton = back,
                columns = 2, id = "help.now")
            val prepared = ArcMenus.prepareDialog(original, false, close(), preferBackFooter = true)
            prepared.buttons shouldBe original.buttons
            val footer = requireNotNull(prepared.exitButton)
            footer.id shouldBe back.id
            footer.width shouldBe 200
            footer.closeDialogBeforeAction shouldBe false
            (footer.onClick === back.onClick) shouldBe true
            footer.onClick.handle(mockk<PaperDialogClickContext>())
            returned shouldBe 1
            val ordinary = ArcMenus.prepareDialog(original, false, close())
            ordinary.buttons.last().id shouldBe back.id
            ordinary.exitButton!!.id.value shouldBe "close_menu"
        }
    }

    "explicit footer dispatches native history mode while the default still closes on Escape" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("ProfileFooter")
            val modes = mutableListOf<Boolean>()
            val screens = mutableListOf<PaperDialogScreen>()
            mockkObject(MenuEscapeBehavior)
            mockkConstructor(PaperDialogRuntime::class)
            try {
                every { MenuEscapeBehavior.goesBack(player) } returns false
                every { anyConstructed<PaperDialogRuntime>().open(any(), any(), any(), any(), any()) } answers {
                    screens += secondArg<PaperDialogScreen>()
                    modes += arg<Boolean>(4)
                }
                ArcMenus.initializeDialogRuntime(paper.createSimplePlugin("ProfileFooter"))
                val original = PaperDialogScreen(Component.text("Профиль"),
                    buttons = listOf(button("now_homes"), button("now_lands")),
                    exitButton = button("root", 200), columns = 2, id = "help.now")
                ArcMenus.openDialog(player, original, close(), null, {}, preferBackFooter = true)
                modes shouldBe listOf(false)
                screens.single().buttons shouldBe original.buttons
                screens.single().exitButton!!.id.value shouldBe "root"
                screens.single().exitButton!!.closeDialogBeforeAction shouldBe false
                ArcMenus.openDialog(player, original, close(), null, {})
                modes shouldBe listOf(false, true)
                screens.last().buttons.last().id.value shouldBe "root"
                screens.last().exitButton!!.id.value shouldBe "close_menu"
            } finally {
                ArcMenus.close()
                unmockkConstructor(PaperDialogRuntime::class)
                unmockkObject(MenuEscapeBehavior)
            }
        }
    }

    "Escape Back normalizes a declared closing footer before decoration without losing its callback" {
        ArcMenus.configureDialogDecoration("arc:dialog_ui")
        val footer = button("close", 200, close = true)
        val screen = PaperDialogScreen(Component.text("Окно"), buttons = emptyList(), exitButton = footer,
            id = "messages.notice")

        val prepared = ArcMenus.prepareDialog(screen, true, close())
        prepared.buttons shouldBe emptyList()
        prepared.exitButton!!.id shouldBe footer.id
        prepared.exitButton!!.width shouldBe 200
        prepared.exitButton!!.closeDialogBeforeAction shouldBe false
        (prepared.exitButton!!.onClick === footer.onClick) shouldBe true
        decorated(prepared.exitButton!!) shouldBe true
    }
})
