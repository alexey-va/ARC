package ru.arc.helpcenter

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class HelpCenterPresentationTest : FreeSpec({
    fun presentation(font: String) = HelpCenterPresentation(HelpCenterSettings(
        true, 12, 8, 3, emptyMap(), emptyMap(), emptyMap(), emptyMap(), font,
    ))
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

    "Escape close prepares Back at its future grid width before the generic runtime moves it" {
        val theme = presentation("arc:dialog_ui")
        val close = theme.screen(screen, false)
        close.exitButton!!.width shouldBe 230
        close.exitButton!!.label shouldBe theme.screen(screen.copy(exitButton = button("back", 230)), true).exitButton!!.label
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
        for ((id, label) in listOf("settings" to "☷ Настройки ›", "rank" to "Ранги ›",
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
            "create_home" to ("＋ Новый дом ›" to "Новый дом"), "search" to ("✧ Поиск ›" to "Поиск"))
        for ((id, labels) in examples) {
            val original = button(id, 166).copy(label = Component.text(labels.first))
            val decorated = presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(original)), true).buttons.single()
            val points = PlainTextComponentSerializer.plainText().serialize(decorated.label).codePoints().toArray()
            val words = buildString { points.filter { it !in 0xF9000..0xF9FFF && it !in 0xF0F01..0xF0F0A }.forEach { appendCodePoint(it) } }
            words shouldBe labels.second
            points.count { it in 0xF9060..0xF9073 } shouldBe 1
        }
        val home = button("home_0", 166).copy(label = Component.text("⌖ Утёс ›"))
        presentation("arc:dialog_ui").screen(screen.copy(buttons = listOf(home)), true).buttons.single().label shouldBe home.label
    }
})
