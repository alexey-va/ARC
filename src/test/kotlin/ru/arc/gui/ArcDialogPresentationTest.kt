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

    "post-Escape moved Back preserves its resolved grid width and original action" {
        val original = button("edit", 230)
        val close = button("close", 200).copy(label = Component.text("Закрыть меню"), closeDialogBeforeAction = true)
        val themed = presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(original), exitButton = close),
            footerIsBack = false, movedBackId = original.id)
        themed.buttons.single().width shouldBe 230
        (themed.buttons.single().onClick === original.onClick) shouldBe true
        PlainTextComponentSerializer.plainText().serialize(themed.buttons.single().label)
            .codePoints().anyMatch { it == 0xF9050 } shouldBe true
        themed.exitButton!!.width shouldBe 200
        themed.exitButton!!.closeDialogBeforeAction shouldBe true
        PlainTextComponentSerializer.plainText().serialize(themed.exitButton!!.label)
            .codePoints().anyMatch { it == 0xF9004 } shouldBe true
    }

    "missing Back footer is materialized without colliding with domain actions" {
        val source = screen.copy(buttons = listOf(button("arc_history_exit_0", 166)), exitButton = null)
        (presentation("").screen(source, true) === source) shouldBe true
        val themed = presentation("arc:dialog_ui").screen(source, true)
        (themed.buttons.single() === source.buttons.single()) shouldBe true
        themed.exitButton!!.id.value shouldBe "arc_history_exit_1"
        themed.exitButton!!.width shouldBe 200
        themed.exitButton!!.closeDialogBeforeAction shouldBe false
        PlainTextComponentSerializer.plainText().serialize(themed.exitButton!!.label).contains("Назад") shouldBe true
    }

    "native default exit translates before styling and Back cannot be relabeled as Close" {
        val source = screen.copy(exitButton = button("exit", 200).copy(label = Component.translatable("gui.back"),
            closeDialogBeforeAction = true))
        val back = presentation("arc:dialog_ui").screen(source, true).exitButton!!
        back.closeDialogBeforeAction shouldBe false
        PlainTextComponentSerializer.plainText().serialize(back.label).contains("Назад") shouldBe true
        back.tooltip shouldBe Component.empty()
        val close = presentation("arc:dialog_ui").screen(source, false).exitButton!!
        close.closeDialogBeforeAction shouldBe true
        PlainTextComponentSerializer.plainText().serialize(close.label).contains("Закрыть меню") shouldBe true
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

    "functional glyphs replace only the known static ornamental arrows" {
        val examples = listOf("next" to ("Следующая →" to "Следующая"),
            "previous" to ("← Предыдущая" to "Предыдущая"),
            "create_home" to ("＋ Новый дом ›" to "Новый дом"), "search" to ("✧ Поиск ›" to "Поиск"),
            "settings" to ("☷ Настройки ›" to "Настройки"),
            "previous" to ("‹ Предыдущая страница" to "Предыдущая страница"),
            "next" to ("Следующая страница ›" to "Следующая страница"))
        for ((id, labels) in examples) {
            val original = button(id, 230).copy(label = Component.text(labels.first))
            val decorated = presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(original)), true).buttons.single()
            val points = PlainTextComponentSerializer.plainText().serialize(decorated.label).codePoints().toArray()
            val words = buildString { points.filter { it !in 0xF9000..0xF9FFF && it !in 0xF0F01..0xF0F0A }.forEach { appendCodePoint(it) } }
            words shouldBe labels.second
            points.count { it in 0xF9060..0xF9073 } shouldBe 1
        }
        val home = button("home_0", 166).copy(label = Component.text("⌖ Утёс ›"))
        presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(home)), true).buttons.single().label shouldBe home.label
    }

    "existing save add and native cancel controls keep their own utility families" {
        for ((id, base) in listOf("save" to 0xF9014, "create" to 0xF9008, "loading_cancel" to 0xF9004,
            "back" to 0xF9050, "root" to 0xF9050, "back_command" to 0xF9050, "previous" to 0xF900C)) {
            val source = button(id, 200).copy(label = if (id == "loading_cancel") Component.translatable("gui.cancel")
                else Component.text("Действие"))
            val themed = presentation("arc:dialog_ui").button(source)
            val text = PlainTextComponentSerializer.plainText().serialize(themed.label)
            text.codePoints().anyMatch { it == base } shouldBe true
            (themed.onClick === source.onClick) shouldBe true
            if (id == "loading_cancel") text.contains("Отмена") shouldBe true
        }
    }

})
