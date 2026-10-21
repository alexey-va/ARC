package ru.arc.helpcenter

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Measured HelpCenter summaries; utility buttons are styled at the shared ARC dialog boundary. */
internal class HelpCenterPresentation(settings: HelpCenterSettings) {
    private val decoration = settings.decorationFont.takeIf(String::isNotEmpty)?.let {
        DialogDecoration(Key.key(it), 0xF9F00, 0xF9F10)
    }
    private val prose = TextColor.color(0xe8dfd2)
    private val corners = DialogDecoration.Corners(glyph(0xF9100, 13), glyph(0xF9101, 13), glyph(0xF9102, 13), glyph(0xF9103, 13))
    private val divider = DialogDecoration.Divider(glyph(0xF9104, 13), glyph(0xF9106, 13), glyph(0xF9105, 13))

    /** Labels share a left edge and values a right edge within each measured column. */
    fun facts(rows: List<List<Pair<Component, Component>>>, width: Int, corners: Boolean = false): PaperDialogBody {
        val contentWidth = width - 40 // Native padding plus the existing 16px open-corner inset.
        val gutter = 24
        fun field(pair: Pair<Component, Component>) = Component.empty().color(prose)
            .append(pair.first).append(Component.text(": ")).append(pair.second)
        fun fallback() = PaperDialogBody(lines(rows.flatten().map(::field)), width)
        val rendered = rows.flatMap { row ->
            if (row.isEmpty()) return@flatMap emptyList()
            val available = contentWidth - gutter * (row.size - 1)
            val widths = row.indices.map { available / row.size + if (it == row.lastIndex) available % row.size else 0 }
            val cells = row.mapIndexed { index, pair ->
                val labelWidth = minOf(88, widths[index] * 2 / 5)
                val valueWidth = widths[index] - labelWidth - 8
                val label = Component.empty().color(prose).append(pair.first).append(Component.text(":"))
                val value = Component.empty().color(prose).append(pair.second)
                val labels = alignedLines(label, labelWidth, TextAlignment.LEFT) ?: return fallback()
                val values = alignedLines(value, valueWidth, TextAlignment.RIGHT) ?: return fallback()
                List(maxOf(labels.size, values.size)) { line ->
                    Component.empty().append(labels.getOrElse(line) { DialogTextLayout.spacing.padding(labelWidth) })
                        .append(DialogTextLayout.spacing.padding(8))
                        .append(values.getOrElse(line) { DialogTextLayout.spacing.padding(valueWidth) })
                }
            }
            List(cells.maxOf { it.size }) { line ->
                Component.empty().children(cells.flatMapIndexed { index, cell ->
                    (if (index == 0) emptyList() else listOf(DialogTextLayout.spacing.padding(gutter))) +
                        cell.getOrElse(line) { DialogTextLayout.spacing.padding(widths[index]) }
                })
            }
        }
        val content = lines(rendered)
        return if (corners && decoration != null) decoration.openCorners(content, width, this.corners)
        else PaperDialogBody(lines(rendered.map {
            Component.empty().append(DialogTextLayout.spacing.padding(16)).append(it)
                .append(DialogTextLayout.spacing.padding(16))
        }), width)
    }

    private fun alignedLines(text: Component, width: Int, alignment: TextAlignment): List<Component>? {
        if (width < 1) return null
        val result = DialogTextLayout.layout(text, alignment, width + 8) as? TextLayoutResult.Aligned ?: return null
        val rows = mutableListOf(Component.text())
        fun split(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            (node as TextComponent).content().split('\n').forEachIndexed { index, part ->
                if (index > 0) rows += Component.text()
                if (part.isNotEmpty()) rows.last().append(Component.text(part).style(style))
            }
            node.children().forEach { split(it, style) }
        }
        split(result.component, Style.empty())
        return rows.map { it.build() }
    }

    private fun lines(rows: List<Component>) = Component.empty().children(rows.flatMapIndexed { index, row ->
        if (index == 0) listOf(row) else listOf(Component.newline(), row)
    })

    fun separator(width: Int): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, divider), width) }

    private fun glyph(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
}
