package ru.arc.gui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class ArcDialogPresentationTest : FreeSpec({
    fun presentation(font: String) = ArcDialogPresentation(font)
    fun button(id: String, width: Int) = PaperDialogButton(
        PaperDialogActionId.of(id), Component.text("Назад"), Component.text("Подсказка"), width,
        onClick = { },
    )
    val screen = PaperDialogScreen(title = Component.text("Настройки"),
        buttons = listOf(button("search", 230)), exitButton = button("back", 150), id = "help.settings")

    "empty font is a complete opt-out and decoration preserves domain handlers" {
        presentation("").screen(screen, true) shouldBe screen
        val decorated = presentation("arc:dialog_ui").screen(screen, true)
        decorated.buttons.single().id shouldBe screen.buttons.single().id
        (decorated.buttons.single().onClick === screen.buttons.single().onClick) shouldBe true
        decorated.buttons.single().tooltip shouldBe screen.buttons.single().tooltip
        decorated.buttons.single().closeDialogBeforeAction shouldBe screen.buttons.single().closeDialogBeforeAction
        decorated.exitButton!!.width shouldBe 150
    }

    "configured Back retains its frame while Close stays native and missing footers stay Core-owned" {
        val theme = presentation("arc:dialog_ui")
        val back = theme.screen(screen, true).exitButton!!
        PlainTextComponentSerializer.plainText().serialize(back.label).codePoints().anyMatch { it == 0xF9050 } shouldBe true
        (back.onClick === screen.exitButton!!.onClick) shouldBe true
        val close = button("close", 200).copy(label = Component.text("Close"), closeDialogBeforeAction = true)
        theme.screen(screen.copy(exitButton = close), false).exitButton shouldBe close
        theme.screen(screen.copy(exitButton = null), true).exitButton shouldBe null
    }

    "every explicit pager family stays native and last on short pages in either font mode" {
        val pairs = listOf("previous" to "next", "previous_page" to "next_page", "items_previous" to "items_next",
            "previous_regions" to "next_regions", "previous_levels" to "next_levels", "network_previous" to "network_next")
        for (font in listOf("", "arc:dialog_ui")) for ((previousId, nextId) in pairs) {
            val previous = button(previousId, 230).copy(label = Component.text("‹ Previous page"))
            val next = button(nextId, 230).copy(label = Component.text("Следующая страница ›"))
            val item = button("item_0", 230).copy(label = Component.text("Дом игрока ›"))
            val source = screen.copy(buttons = listOf(previous, item, next), columns = 2)
            val ordered = presentation(font).screen(source, true)
            ordered.buttons shouldBe listOf(item, previous, next)
            ordered.columns shouldBe 2
            presentation(font).screen(source.copy(buttons = listOf(previous, item)), true).buttons shouldBe listOf(item, previous)
        }
    }

    "only pack primitives use the configured font and supplementary IDs are whole scalars" {
        val label = presentation("example:dialog").screen(screen, true).buttons.single().label
        var custom = 0
        var native = 0
        fun visit(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            (node as TextComponent).content().codePoints().forEach { point ->
                if (point in 0xF9000..0xF9FFF) {
                    style.font() shouldBe Key.key("example:dialog")
                    custom++
                } else if (point in 0x400..0x4ff) {
                    (style.font() ?: Key.key("minecraft:default")) shouldBe Key.key("minecraft:default")
                    native++
                }
            }
            node.children().forEach { visit(it, style) }
        }
        visit(label, Style.empty())
        (custom > 0) shouldBe true
        native shouldBe 5
    }

    "ordinary categories and dynamic names retain their original vanilla button" {
        val theme = presentation("arc:dialog_ui")
        for ((id, label) in listOf("rank" to "Ранги ›",
            "root_technology" to "Технологии ›", "settings_interface" to "Интерфейс ›",
            "command_builder" to "⚒ Инструменты строителя ›", "home_0" to "⌖ Утёс ›")) {
            val original = button(id, 150).copy(label = Component.text(label))
            val presented = theme.screen(screen.copy(buttons = listOf(original)), true).buttons.single()
            (presented === original) shouldBe true
        }
    }

    "technical controls replace their known ornaments without touching catalog captions" {
        for ((id, caption) in listOf("search" to "✧ Поиск ›", "settings" to "☷ Настройки ›")) {
            val original = button(id, 230).copy(label = Component.text(caption))
            val decorated = presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(original)), true).buttons.single()
            val points = PlainTextComponentSerializer.plainText().serialize(decorated.label).codePoints().toArray()
            points.count { it in 0xF9060..0xF9073 } shouldBe 1
            points.none { it == '✧'.code || it == '☷'.code || it == '›'.code } shouldBe true
            (decorated.onClick === original.onClick) shouldBe true
        }
    }

    "important commits retain distinct accents while repeated utility controls stay native" {
        for ((id, base) in listOf("save" to 0xF9014, "confirm" to 0xF9000, "delete" to 0xF9044)) {
            val source = button(id, 200).copy(label = Component.text("Действие"))
            val themed = presentation("arc:dialog_ui").button(source)
            PlainTextComponentSerializer.plainText().serialize(themed.label).codePoints().anyMatch { it == base } shouldBe true
            (themed.onClick === source.onClick) shouldBe true
        }
        for (id in listOf("create", "invite", "create_home", "loading_cancel", "previous", "next", "layout_reset")) {
            val source = button(id, 200)
            presentation("arc:dialog_ui").button(source) shouldBe source
        }
    }
})
