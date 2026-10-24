package ru.arc.helpcenter

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Native HelpCenter text compositions; utility buttons are styled at the shared ARC dialog boundary. */
internal class HelpCenterPresentation(settings: HelpCenterSettings) {
    private val font: Key? = settings.decorationFont.takeIf(String::isNotEmpty)?.let { Key.key(it) }
    private val decoration = font?.let {
        DialogDecoration(it, 0xF9F00, 0xF9F10)
    }
    private val prose = TextColor.color(0xe8dfd2)
    private val corners = DialogDecoration.Corners(glyph(0xF9100, 13), glyph(0xF9101, 13), glyph(0xF9102, 13), glyph(0xF9103, 13))
    private val divider = DialogDecoration.Divider(glyph(0xF9104, 13), glyph(0xF9106, 13), glyph(0xF9105, 13))

    /** Explicit opaque summary; the existing font opt-out keeps portable native facts. */
    fun filledFacts(rows: List<List<Pair<Component, Component>>>, width: Int): PaperDialogBody = filledFacts(rows, width, null)

    fun filledFacts(rows: List<List<Pair<Component, Component>>>, width: Int, skin: DialogTables.PanelSkin?): PaperDialogBody {
        if (decoration == null || rows.isEmpty()) return facts(rows, width)
        val cells = if (rows.all { it.size == 1 }) rows.map { row -> listOf(row.single().first, row.single().second) }
            else rows.map { row -> row.map(::field) }
        val weights = List(cells.first().size) { 1 }
        return if (skin == null) DialogTables.filledBody(cells, null, width, weights)
        else DialogTables.filledBody(cells, null, width, weights, skin)
    }

    private fun field(pair: Pair<Component, Component>) = Component.empty().color(prose)
        .append(pair.first).append(Component.text(": ")).append(pair.second)

    fun fieldLabel(icon: DialogDecoration.FieldIcon, label: Component): Component =
        if (decoration == null) label else DialogDecoration.fieldLabel(icon, label)

    /** Content-fitted facts retain close label/value pairs; the font opt-out has no private glyphs. */
    fun compactFacts(facts: List<Pair<Component, Component>>, width: Int, skin: DialogTables.PanelSkin): PaperDialogBody =
        if (decoration == null) PaperDialogBody(lines(facts.map(::field)), width)
        else DialogTables.factsBody(facts.map { (label, value) -> label.append(Component.text(":")) to value },
            DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(skin, width, 1, true))

    fun groupedFacts(groups: List<DialogTables.FactsGroup>, width: Int, skin: DialogTables.PanelSkin): PaperDialogBody =
        if (decoration == null) PaperDialogBody(lines(groups.flatMap { listOf(it.title) + it.facts.map(::field) }), width)
        else DialogTables.groupedFactsBody(groups.map { group -> group.copy(facts = group.facts.map { (label, value) ->
            label.append(Component.text(":")) to value
        }) }, DialogTables.PanelSpec(skin, width, 2, true))

    /** Labels share a left edge and values a right edge within each measured column. */
    fun facts(rows: List<List<Pair<Component, Component>>>, width: Int, corners: Boolean = false): PaperDialogBody {
        val contentWidth = width - 40 // Native padding plus the existing 16px open-corner inset.
        val gutter = 24
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
        else PaperDialogBody(inset(rendered), width)
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

    private fun inset(rows: List<Component>) = lines(rows.map {
        Component.empty().append(DialogTextLayout.spacing.padding(16)).append(it)
            .append(DialogTextLayout.spacing.padding(16))
    })

    /** Long explanations keep a common reading edge instead of centering every wrapped line. */
    fun paragraph(text: Component, width: Int): PaperDialogBody {
        if (decoration == null) return PaperDialogBody(text, width)
        val rows = alignedLines(text, width - 40, TextAlignment.LEFT) ?: return PaperDialogBody(text, width)
        return PaperDialogBody(inset(rows), width)
    }

    fun callout(text: Component, width: Int): PaperDialogBody =
        decoration?.openCorners(text, width, corners) ?: PaperDialogBody(text, width)

    /** Marks only the diagnostic facts supplied by the planner; no inferred player progress. */
    fun checklist(entries: List<Pair<Boolean, Component>>, width: Int): Component {
        fun fallback() = lines(entries.flatMapIndexed { index, (positive, text) ->
            val item = Component.text(if (positive) "✓ " else "• ").color(prose).append(text)
            if (index == 0) listOf(item) else listOf(Component.empty(), item)
        })
        val painter = decoration ?: return fallback()
        return painter.listBody(entries.map { (positive, text) ->
            DialogDecoration.ListEntry(if (positive) DialogDecoration.Marker.COMPLETE else DialogDecoration.Marker.PENDING, text)
        }, width).text
    }

    fun separator(width: Int): PaperDialogBody? = decoration?.let { PaperDialogBody(it.divider(width, divider), width) }

    /** A compact subject heading between two rules, without adding another framed panel. */
    fun section(title: Component, width: Int, style: DialogDecoration.HeadingStyle = DialogDecoration.HeadingStyle.PLAIN): PaperDialogBody =
        decoration?.headingBody(title, width, style) ?: PaperDialogBody(title, width)

    private fun glyph(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
}
