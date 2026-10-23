package ru.arc.helpcenter

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.DialogTextLayout

class HelpCenterPresentationTest : FreeSpec({
    fun presentation(font: String) = HelpCenterPresentation(HelpCenterSettings(
        true, 12, 8, 3, emptyMap(), emptyMap(), emptyMap(), emptyMap(), font,
    ))
    "single field rows retain label and value columns in an opaque detail card" {
        val rows = listOf(
            listOf(Component.text("Заряд") to Component.text("12 / 100")),
            listOf(Component.text("Состояние") to Component.text("Включён")),
        )
        val body = presentation("arc:dialog_ui").filledFacts(rows, 320)
        val visible = PlainTextComponentSerializer.plainText().serialize(body.text)
        listOf("Заряд", "12 / 100", "Состояние", "Включён").forEach { visible.contains(it) shouldBe true }
        visible.contains("Заряд:") shouldBe false
        visible.codePoints().anyMatch { it in 0xFA300..0xFAF1B && (it - 0xFA300) % 16 == 3 } shouldBe true
        presentation("").filledFacts(rows, 320) shouldBe presentation("").facts(rows, 320)
    }

    "filled summary preserves all facts and native coin styling while respecting the font opt-out" {
        val values = listOf("Long_Player_1234", "999", "Исследователь", "123 456 789.99", "3 / 12", "4 (128 чанков)", "survival", "Ванильный мир")
        val labels = listOf("Игрок", "В сети", "Ранг", "Баланс", "Дома", "Приваты", "Сейчас", "Мир")
        val rows = labels.zip(values).mapIndexed { index, (label, value) ->
            Component.text(label) to Component.text(value).let {
                if (index == 3) it.append(Component.text(" 💰", NamedTextColor.WHITE)) else it
            }
        }.chunked(2)
        val body = presentation("arc:dialog_ui").filledFacts(rows, 508)
        body.width shouldBe 508
        val visible = PlainTextComponentSerializer.plainText().serialize(body.text)
        (labels + values).forEach { visible.contains(it) shouldBe true }
        visible.codePoints().anyMatch { it in 0xFA300..0xFAF1B } shouldBe true
        var coins = 0
        fun inspect(node: Component, parent: Style) {
            val style = node.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            (node as TextComponent).content().codePoints().forEach {
                if (it == 0x1F4B0) {
                    coins++
                    style.color() shouldBe NamedTextColor.WHITE
                    style.font() shouldBe Key.key("minecraft:default")
                    style.shadowColor() shouldBe null
                }
            }
            node.children().forEach { inspect(it, style) }
        }
        inspect(body.text, Style.empty())
        coins shouldBe 1
        presentation("").filledFacts(rows, 508) shouldBe presentation("").facts(rows, 508)
    }

    "fact columns have exact pixel starts for long values and retain whole white currency glyphs" {
        val money = Component.text("123 456 789.99 ").append(Component.text("💰", NamedTextColor.WHITE))
        val body = presentation("arc:dialog_ui").facts(listOf(
            listOf(Component.text("Игрок") to Component.text("Long_Player_1234").decorate(TextDecoration.BOLD),
                Component.text("Баланс") to money),
            listOf(Component.text("Ранг") to Component.text("Исследователь"),
                Component.text("В сети") to Component.text("999")),
        ), 506)
        val fonts = requireNotNull(DialogTextLayout::class.java.getResourceAsStream("/fonts/dialog-font-metrics.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
        val lines = mutableListOf(mutableListOf<Pair<Int, Int>>())
        val widths = mutableListOf<Int>()
        var x = 0
        var coins = 0
        fun measure(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            val font = (style.font() ?: Key.key("minecraft:default")).asString()
            val weight = if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal"
            (node as TextComponent).content().codePoints().forEach { point ->
                if (point == '\n'.code) {
                    widths += x; x = 0; lines += mutableListOf<Pair<Int, Int>>()
                } else {
                    lines.last() += point to x
                    x += fonts.getAsJsonObject(font).getAsJsonObject(weight)
                        .get("U+" + point.toString(16).uppercase().padStart(4, '0')).asInt
                    if (point == 0x1F4B0) {
                        style.color() shouldBe NamedTextColor.WHITE
                        coins++
                    }
                }
            }
            node.children().forEach { measure(it, style) }
        }
        measure(body.text, Style.empty()); widths += x
        fun position(row: Int, text: String, end: Boolean = false): Int {
            val value = buildString { lines[row].forEach { appendCodePoint(it.first) } }
            val index = value.indexOf(text)
            (index >= 0) shouldBe true
            val offset = value.codePointCount(0, index) + if (end) text.codePointCount(0, text.length) else 0
            return lines[row][offset].second
        }
        widths shouldBe listOf(498, 498)
        position(0, "Игрок") shouldBe 16
        position(1, "Ранг") shouldBe 16
        position(0, "Баланс") shouldBe 261
        position(1, "В сети") shouldBe 261
        position(0, "Long_Player_1234", end = true) shouldBe 237
        position(1, "Исследователь", end = true) shouldBe 237
        position(0, "123 456 789.99 💰", end = true) shouldBe 482
        position(1, "999", end = true) shouldBe 482
        val visible = PlainTextComponentSerializer.plainText().serialize(body.text)
        visible.contains("Long_Player_1234") shouldBe true
        visible.contains("123 456 789.99") shouldBe true
        visible.contains('·') shouldBe false
        coins shouldBe 1
    }
})
