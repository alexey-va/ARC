package ru.arc.dialogdemo

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.gui.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Fixed, synthetic design specimens. Every cell uses the shared pack measurement engine. */
object DialogDesignGallery {
    const val WIDTH = 392
    const val TABLE_COUNT = 12
    const val DIVIDER_COUNT = 18
    private val ink = TextColor.color(0xE6EDF3)
    private val muted = TextColor.color(0x9AA8B7)
    private val line = TextColor.color(0x728698)
    private val gold = TextColor.color(0xE8C383)
    private val blue = TextColor.color(0x83C9E8)
    private val mint = TextColor.color(0x95D5B2)
    private val violet = TextColor.color(0xC2A9E8)

    internal fun cell(text: Component, width: Int, alignment: TextAlignment = TextAlignment.LEFT): Component {
        val result = DialogTextLayout.layout(text, alignment, width + 8)
        require(result is TextLayoutResult.Aligned && result.lineCount == 1) {
            "Gallery cell does not fit its ${width}px column: $result"
        }
        return result.component
    }

    private fun join(parts: List<Component>): Component = Component.empty().children(parts)
    private fun lines(parts: List<Component>): Component = join(parts.flatMapIndexed { i, part ->
        if (i == 0) listOf(part) else listOf(Component.newline(), part)
    })
    private fun gap(width: Int) = DialogTextLayout.spacing.padding(width)
    private fun glyph(value: String, color: TextColor = line) = Component.text(value, color)
    private fun centered(value: Component) = cell(value, WIDTH, TextAlignment.CENTER)
    private fun rule(width: Int, character: Char = '─', color: TextColor = line): Component {
        val advance = DialogTextLayout.glyphWidth(character)
        require(advance > 0)
        return join(listOf(glyph(character.toString().repeat(width / advance), color), gap(width % advance)))
    }
    private fun dotted(width: Int, character: Char = '·', step: Int = 6, color: TextColor = line): Component {
        val advance = DialogTextLayout.glyphWidth(character)
        require(step >= advance)
        val count = width / step
        return join(List(count) { join(listOf(glyph(character.toString(), color), gap(step - advance))) } + gap(width % step))
    }
    private fun row(values: List<Component>, widths: List<Int>, separator: Component = gap(12),
        alignments: List<TextAlignment> = listOf(TextAlignment.LEFT, TextAlignment.RIGHT, TextAlignment.LEFT)): Component {
        require(values.size == widths.size && values.size == alignments.size)
        return join(values.flatMapIndexed { i, value ->
            val part = cell(value, widths[i], alignments[i])
            if (i == 0) listOf(part) else listOf(separator, part)
        })
    }

    fun table(number: Int, text: (String) -> Component): Component {
        require(number in 1..TABLE_COUNT)
        val widths = listOf(180, 81, 81)
        val accent = when (number) { 3, 6, 9 -> blue; 4, 7 -> mint; 8 -> violet; else -> gold }
        val header = listOf("project", "steps", "status").map { text("gallery.data.$it").color(accent).decorate(TextDecoration.BOLD) }
        val data = (1..4).map { index -> listOf("project", "steps", "status").map { key ->
            text("gallery.data.row-$index.$key").color(if (key == "status" && index == 2) mint else ink)
        } }
        val ordinary = (listOf(header) + data).map { row(it, widths) }
        val output = when (number) {
            1 -> ordinary
            2 -> listOf(ordinary.first(), rule(366)) + ordinary.drop(1) + rule(366)
            3 -> (listOf(header) + data).map { row(it, widths, cell(glyph("│", blue), 12, TextAlignment.CENTER)) }
            4 -> listOf(ordinary.first(), dotted(366)) + ordinary.drop(1).flatMapIndexed { index, value ->
                if (index == 3) listOf(value) else listOf(value, dotted(366))
            }
            5, 6 -> {
                val double = number == 6
                val edgeWidth = if (double) 8 else 6
                val vertical = if (double) "║" else "│"
                fun edge(left: String, middle: String, right: String) = join(listOf(
                    glyph(left, accent), rule(180, if (double) '═' else '─', accent), glyph(middle, accent),
                    rule(81, if (double) '═' else '─', accent), glyph(middle, accent),
                    rule(81, if (double) '═' else '─', accent), glyph(right, accent)))
                fun framed(values: List<Component>) = join(listOf(cell(glyph(vertical, accent), 9),
                    row(values, widths, cell(glyph(vertical, accent), 9)), cell(glyph(vertical, accent), edgeWidth)))
                listOf(edge(if (double) "╔" else "┌", if (double) "╦" else "┬", if (double) "╗" else "┐"),
                    framed(header), edge(if (double) "╠" else "├", if (double) "╬" else "┼", if (double) "╣" else "┤")) +
                    data.map(::framed) + edge(if (double) "╚" else "└", if (double) "╩" else "┴", if (double) "╝" else "┘")
            }
            7 -> listOf(rule(366, '─', accent), ordinary.first()) + ordinary.drop(1) + rule(366, '─', accent)
            8 -> listOf(ordinary.first()) + data.flatMapIndexed { i, values ->
                val marked = join(listOf(cell(glyph("│", accent), 12), row(values, listOf(168, 81, 81))))
                if (i == 3) listOf(marked) else listOf(marked, gap(366))
            }
            9 -> listOf(ordinary.first(), rule(366, '═', accent)) + data.mapIndexed { i, values ->
                row(values.mapIndexed { column, value -> value.color(if (column == 1) blue else if (i % 2 == 0) ink else muted) }, widths)
            }
            10 -> {
                val headings = listOf("feature", "base", "expanded", "team").map { text("gallery.compare.$it").color(blue).decorate(TextDecoration.BOLD) }
                val rows = (1..4).map { i -> listOf("feature", "base", "expanded", "team").map { text("gallery.compare.row-$i.$it").color(ink) } }
                val sizes = listOf(144, 66, 66, 66)
                val align = listOf(TextAlignment.LEFT, TextAlignment.CENTER, TextAlignment.CENTER, TextAlignment.CENTER)
                listOf(row(headings, sizes, gap(8), align), rule(366)) + rows.map { row(it, sizes, gap(8), align) }
            }
            11 -> {
                val align = listOf(TextAlignment.RIGHT, TextAlignment.LEFT, TextAlignment.RIGHT)
                val sizes = listOf(24, 216, 102)
                val head = listOf("place", "builder", "count").map { text("gallery.ranking.$it").color(gold).decorate(TextDecoration.BOLD) }
                listOf(row(head, sizes, gap(12), align), rule(366)) + (1..4).map { i ->
                    row(listOf(glyph(i.toString(), if (i == 1) gold else muted), text("gallery.ranking.row-$i.name").color(ink),
                        text("gallery.ranking.row-$i.count").color(if (i == 1) gold else ink)), sizes, gap(12), align)
                }
            }
            else -> listOf(cell(text("gallery.details.title").color(violet).decorate(TextDecoration.BOLD), 366), rule(366)) +
                (1..5).map { i -> row(listOf(text("gallery.details.row-$i.label").color(muted), text("gallery.details.row-$i.value").color(ink)),
                    listOf(180, 174), gap(12), listOf(TextAlignment.LEFT, TextAlignment.RIGHT)) }
        }
        return lines(output.map(::centered))
    }

    fun divider(number: Int, text: (String) -> Component): Component {
        require(number in 1..DIVIDER_COUNT)
        val label = text("gallery.dividers.sample").color(gold)
        val content = when (number) {
            1 -> rule(378)
            2 -> rule(378, '═')
            3 -> rule(378, '_')
            4 -> dotted(378)
            5 -> dotted(378, '•', 9)
            6 -> dotted(378, '─', 18)
            7 -> rule(126, '─', blue)
            8 -> join(listOf(rule(90, '─', gold), gap(12), glyph("◆", gold), gap(12), rule(90, '─', gold)))
            9 -> join(listOf(rule(144), gap(12), glyph("◇", blue), gap(12), rule(144)))
            10 -> join(listOf(rule(108, '─', blue), cell(label.color(blue), 162, TextAlignment.CENTER), rule(108, '─', blue)))
            11 -> join(listOf(dotted(108), cell(label, 162, TextAlignment.CENTER), dotted(108)))
            12 -> join(listOf(glyph("[", violet), cell(label.color(violet), 180, TextAlignment.CENTER), glyph("]", violet)))
            13 -> join(listOf(rule(126, '─', blue), rule(126, '─', mint), rule(126, '─', gold)))
            14 -> join(listOf(rule(54, '═', gold), rule(324, '─')))
            15 -> join((0..6).map { step -> rule(54, '─', TextColor.lerp(step / 6f, blue, violet)) })
            16 -> join(listOf(glyph("┌", blue), rule(351, '─', blue), glyph("┐", blue)))
            17 -> join(listOf(glyph("└", violet), rule(351, '─', violet), glyph("┘", violet)))
            else -> lines(listOf(rule(378, '─', muted), rule(378, '─', blue)).map(::centered))
        }
        return if (number == 18) content else centered(content)
    }
}
