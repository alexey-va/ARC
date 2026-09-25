package ru.arc.itemlore

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ItemLorePreviewTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    fun coloredRuns(component: Component): List<Pair<String, TextColor?>> {
        val runs = mutableListOf<Pair<String, TextColor?>>()
        fun visit(node: Component, parent: Style) {
            val style = node.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            val content = (node as? TextComponent)?.content().orEmpty()
            if (content.isNotEmpty()) {
                val color = style.color()
                val previous = runs.lastOrNull()
                if (previous != null && previous.second == color) runs[runs.lastIndex] = (previous.first + content) to color
                else runs += content to color
            }
            node.children().forEach { visit(it, style) }
        }
        visit(component, Style.empty())
        return runs
    }

    val labels = mapOf(
        "preview-before" to "Было",
        "preview-after" to "Станет",
        "preview-blank-row" to "(пустая строка)",
        "preview-missing-row" to "—",
    )
    val text: (String) -> Component = { key -> Component.text(labels.getValue(key)) }

    "new lore renders in the framed table under the requested headers" {
        val table = ItemLorePreview.table(
            before = emptyList(),
            after = listOf(Component.text("Добавлено", NamedTextColor.WHITE)),
            text = text,
        )
        val rendered = plain.serialize(table.text)

        table.width shouldBe 320
        rendered shouldContain "Было"
        rendered shouldContain "Станет"
        rendered shouldContain "—"
        rendered shouldContain "Добавлено"
        rendered shouldContain "\uE570"
    }

    "keeps every before and after row, including empty interior rows and long text" {
        val long = "Редкое зачарование усиливает оружие при каждом точном ударе."
        val table = ItemLorePreview.table(
            before = listOf(
                Component.text("Старое название", NamedTextColor.RED),
                Component.empty(),
                Component.text(long),
            ),
            after = listOf(
                Component.text("Новое название", NamedTextColor.GREEN),
                Component.text("Вторая строка"),
                Component.text("Третья строка"),
                Component.text("Четвёртая строка"),
            ),
            text = text,
        )
        val rendered = plain.serialize(table.text)

        rendered shouldContain "Старое название"
        rendered shouldContain "Новое название"
        rendered shouldContain "(пустая строка)"
        rendered shouldContain "Вторая строка"
        long.split(' ').forEach { rendered shouldContain it }
        rendered shouldContain "Четвёртая строка"
        rendered shouldContain "\uE570"
        coloredRuns(table.text).any { (content, color) ->
            "Старое название" in content && color == NamedTextColor.RED
        } shouldBe true
        coloredRuns(table.text).any { (content, color) ->
            "Новое название" in content && color == NamedTextColor.GREEN
        } shouldBe true
    }
})
