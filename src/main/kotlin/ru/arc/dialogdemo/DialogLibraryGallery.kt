package ru.arc.dialogdemo

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.text.PixelSpacing
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Installed design-library specimens. Navigation is native and belongs to Core's shared history. */
internal class DialogLibraryGallery(
    private val runtime: PaperDialogRuntime,
    private val copy: (String) -> Component,
) {
    enum class Section(val key: String, val pageCount: Int) {
        FILLED("filled", 4),
        PANELS("panels", 5), LAYOUTS("layouts", 4), SIZES("sizes", 4),
        ELEMENTS("elements", 5), ICONS("icons", 2),
        TEXT("text", 6), ACTIONS("actions", 3), ORNAMENTS("ornaments", 1),
        TABLES("tables", 6), DIVIDERS("dividers", 3),
    }

    data class Page(val section: Section? = null, val index: Int = 0) {
        init { require(index in 0 until (section?.pageCount ?: 1)) }
        val id: String get() = "dialog_design.${section?.key ?: "root"}"
    }

    fun open(player: Player, page: Page = Page(), entry: Boolean = true, sample: String? = null) {
        if (entry) runtime.beginFlow(player)
        val closeOnEscape = !MenuEscapeBehavior.goesBack(player)
        val screen = screen(page, entry || closeOnEscape, sample) { next, selected ->
            open(player, next, entry = false, sample = selected)
        }
        runtime.open(player, screen, null, {}, closeOnEscape)
    }

    internal fun screen(
        page: Page,
        exitCloses: Boolean,
        sample: String? = null,
        navigate: (Page, String?) -> Unit,
    ): PaperDialogScreen {
        val section = page.section
        val bodies = mutableListOf<PaperDialogBody>()
        val buttons = mutableListOf<PaperDialogButton>()
        fun body(key: String, width: Int = 400) { bodies += PaperDialogBody(text(key), width) }
        fun button(id: String, label: Component, tooltip: Component, click: () -> Unit) = PaperDialogButton(
            PaperDialogActionId.of(id), label, tooltip, width = 200, onClick = { click() },
        )
        if (section == null) {
            body("intro")
            Section.entries.forEach { destination ->
                buttons += button(destination.key, text("sections.${destination.key}.label"),
                    text("sections.${destination.key}.tip")) { navigate(Page(destination), null) }
            }
        } else {
            body("sections.${section.key}.intro")
            bodies += PaperDialogBody(text("page").replaceText {
                it.matchLiteral("%page%").replacement("${page.index + 1} / ${section.pageCount}")
            })
            when (section) {
                Section.TEXT -> {
                    body("text.${page.index}.title")
                    bodies += composition(page.index)
                    body("text.${page.index}.note")
                }
                Section.ACTIONS -> (page.index * 8 until minOf(page.index * 8 + 8, 21)).forEach { index ->
                    val key = if (index == 20) "back" else (index + 21).toString()
                    val label = text("actions.$key")
                    buttons += button("sample_$key", actionLabel(index, label), text("sample-tip")) {
                        navigate(page, "actions.$key")
                    }
                }
                Section.ORNAMENTS -> ORNAMENTS.forEachIndexed { index, key ->
                    buttons += button("sample_$key", decoration.buttonLabel(text("ornaments.$key"), 200,
                        frame(0xF9080 + index * 4)), text("sample-tip")) {
                        navigate(page, "ornaments.$key")
                    }
                }
                Section.TABLES -> {
                    val number = page.index + 13
                    bodies += PaperDialogBody(copy("gallery.tables.$number.title").font(NATIVE_FONT))
                    bodies += PaperDialogBody(DialogDesignGallery.table(number, ::tableText), 400)
                    bodies += PaperDialogBody(copy("gallery.tables.$number.note").color(PROSE).font(NATIVE_FONT))
                }
                Section.DIVIDERS -> (page.index * 6 + 1..page.index * 6 + 6).forEach { number ->
                    bodies += PaperDialogBody(copy("gallery.dividers.$number.title").font(NATIVE_FONT))
                    bodies += PaperDialogBody(DialogDesignGallery.divider(number, ::tableText), 400)
                }
                Section.FILLED -> {
                    body("filled.${page.index}.title", FILLED_BODY_WIDTHS[page.index])
                    bodies += filledPanel(page.index)
                    body("filled.note", 508)
                }
                Section.PANELS -> {
                    body("panels.${page.index}.title")
                    if (page.index == 4) bodies += decoration.headingBody(text("samples.heading"), 360,
                        DialogDecoration.HeadingStyle.DIAMOND)
                    bodies += DialogTables.factsBody(sampleFacts(), DialogTables.FactsLayout.ROWS,
                        DialogTables.PanelSpec(DialogTables.PanelSkin.entries[page.index], 468, 1, true))
                    body("panels.${page.index}.note")
                }
                Section.LAYOUTS -> {
                    body("layouts.${page.index}.title")
                    bodies += compactLayout(page.index)
                    body("layouts.${page.index}.note")
                }
                Section.SIZES -> {
                    body("sizes.${page.index}.title")
                    bodies += sizedPanel(page.index)
                    body("sizes.${page.index}.note")
                }
                Section.ELEMENTS -> {
                    body("elements.${page.index}.title")
                    bodies += sharedElements(page.index)
                    if (page.index != 1) body("elements.${page.index}.note")
                }
                Section.ICONS -> {
                    body("icons.${page.index}.title")
                    val icons = DialogDecoration.FieldIcon.entries.drop(page.index * 5).take(5)
                    val facts = icons.map { icon ->
                        val key = icon.name.lowercase()
                        DialogDecoration.fieldLabel(icon, text("icons.$key.label")) to text("icons.$key.value")
                    }
                    bodies += DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
                        DialogTables.PanelSpec(DialogTables.PanelSkin.ORNATE, 400, 1, true))
                    body("icons.note")
                }
            }
            if (sample != null) bodies += PaperDialogBody(text("sample-selected").append(text(sample)))
            if (page.index > 0) buttons += button("previous", text("previous"), text("previous-tip")) {
                navigate(page.copy(index = page.index - 1), null)
            }
            if (page.index + 1 < section.pageCount) buttons += button("next", text("next"), text("next-tip")) {
                navigate(page.copy(index = page.index + 1), null)
            }
        }
        return PaperDialogScreen(
            id = page.id,
            title = text(if (section == null) "title" else "sections.${section.key}.title"),
            body = bodies,
            buttons = buttons,
            columns = 2,
            exitButton = button("exit", text(if (exitCloses) "close" else "back"),
                text(if (exitCloses) "close-tip" else "back-tip")) {},
        )
    }

    private fun text(key: String) = copy("design.$key").font(NATIVE_FONT)
        .decoration(TextDecoration.ITALIC, false)
    private fun tableText(key: String) = copy(key).font(NATIVE_FONT)
        .decoration(TextDecoration.ITALIC, false)

    private fun filledPanel(index: Int): PaperDialogBody {
        val weights = when (index) { 1 -> listOf(2, 3); 2 -> listOf(3, 2, 2); else -> listOf(1) }
        val count = listOf(4, 5, 3, 6)[index]
        val rows = List(count) { row -> List(weights.size) { column -> text("filled.$index.row-$row.col-$column") } }
        val headers = if (index in 1..2) List(weights.size) { text("filled.$index.column-$it") } else null
        return DialogTables.filledBody(rows, headers, FILLED_BODY_WIDTHS[index], weights)
    }

    private fun sampleFacts(count: Int = 4) = List(count) { index ->
        text("samples.fact-$index.label") to text("samples.fact-$index.value")
    }

    private fun compactLayout(index: Int): PaperDialogBody {
        val facts = sampleFacts()
        if (index == 3) return DialogTables.groupedFactsBody(listOf(
            DialogTables.FactsGroup(text("samples.wallet"), facts.take(2)),
            DialogTables.FactsGroup(text("samples.territory"), facts.drop(2)),
        ), DialogTables.PanelSpec(DialogTables.PanelSkin.COCOA, 468, 2, true))
        return DialogTables.factsBody(facts, DialogTables.FactsLayout.entries[index],
            DialogTables.PanelSpec(DialogTables.PanelSkin.entries[index], 468, if (index == 0) 1 else 2, true))
    }

    private fun sizedPanel(index: Int): PaperDialogBody {
        val facts = if (index == 3) sampleFacts(2) +
            (text("samples.long-label") to text("samples.long-value")) else sampleFacts(6)
        return DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
            DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, listOf(220, 360, 500, 180)[index],
                listOf(1, 2, 3, 1)[index], index != 2))
    }

    private fun sharedElements(index: Int): List<PaperDialogBody> = when (index) {
        0 -> listOf(decoration.listBody(DialogDecoration.Marker.entries.mapIndexed { item, marker ->
            DialogDecoration.ListEntry(marker, text("elements.0.item-$item"))
        }, 260))
        1 -> listOf(decoration.stepsBody(DialogDecoration.StepState.entries.mapIndexed { item, state ->
            DialogDecoration.Step(state, text("elements.1.item-$item"))
        }, 260))
        2 -> DialogDecoration.ProgressColor.entries.mapIndexed { color, palette ->
            decoration.progressBody(text("elements.2.label-$color"), 3.0, 10.0, text("elements.2.value"), 280, 10, palette)
        } + decoration.progressBody(text("elements.2.unknown-label"), null, null, text("elements.2.unknown-value"),
            280, 10, DialogDecoration.ProgressColor.MINT)
        3 -> DialogDecoration.StateTone.entries.mapIndexed { item, tone ->
            PaperDialogBody(DialogDecoration.stateBadge(text("elements.3.item-$item"), tone), 280)
        }
        else -> listOf(
            decoration.headingBody(text("samples.heading"), 400),
            decoration.headingBody(text("samples.heading"), 400, DialogDecoration.HeadingStyle.DIAMOND),
            decoration.headingBody(text("elements.4.long-title"), 180, DialogDecoration.HeadingStyle.DIAMOND),
        )
    }

    private fun composition(index: Int): PaperDialogBody = when (index) {
        0 -> decoration.openCorners(text("text.0.sample"), 320, DialogDecoration.Corners(
            piece(0xF9100, 13), piece(0xF9101, 13), piece(0xF9102, 13), piece(0xF9103, 13)))
        1 -> PaperDialogBody(lines(listOf(
            nativeCell(text("text.1.sample-title"), 312, TextAlignment.CENTER),
            decoration.divider(320, DialogDecoration.Divider(piece(0xF9104, 13), piece(0xF9106, 13), piece(0xF9105, 13))),
            nativeCell(text("text.1.sample"), 312),
        )), 320)
        2 -> decoration.stepsBody(DialogDecoration.StepState.entries.mapIndexed { step, state ->
            DialogDecoration.Step(state, text("text.2.step-$step").append(Component.newline())
                .append(text("text.2.detail-$step")))
        }, 308)
        3 -> decoration.listBody((0..2).map { index ->
            DialogDecoration.ListEntry(if (index < 2) DialogDecoration.Marker.COMPLETE else DialogDecoration.Marker.PENDING,
                text("text.3.check-$index").append(Component.newline()).append(text("text.3.detail-$index")))
        }, 308)
        4 -> beforeAfter()
        else -> progress()
    }

    private fun beforeAfter(): PaperDialogBody = PaperDialogBody(lines((0..6).map { y ->
        Row().apply {
            for (x in listOf(0, 162)) {
                when (y) {
                    0, 6 -> {
                        val base = if (y == 0) 0xF9120 else 0xF9122
                        at(x, glyph(base), 10).at(x + 117, glyph(base + 1), 9)
                        for (tile in 1..12) at(x + 9 * tile, glyph(if (y == 0) 0xF9124 else 0xF9125), 10)
                    }
                    else -> at(x, glyph(0xF9126), 3).at(x + 117, glyph(0xF9127), 9)
                }
            }
            if (y == 3) {
                at(130, glyph(0xF912A), 10).at(139, glyph(0xF912A), 10).at(148, glyph(0xF9129), 10)
            }
            if (y in listOf(1, 3, 4)) {
                at(9, nativeCell(text("text.4.before-$y"), 108, TextAlignment.CENTER), 108)
                at(171, nativeCell(text("text.4.after-$y"), 108, TextAlignment.CENTER), 108)
            }
        }.finish(288)
    }), 296)

    private fun progress(): PaperDialogBody = PaperDialogBody(lines(DialogDecoration.ProgressColor.entries.flatMapIndexed { index, palette ->
        val body = decoration.progressBody(text("text.5.palette-$index"), 7.0, 12.0, text("text.5.value"),
            296, 12, palette).text
        if (index == 3) listOf(body) else listOf(body, positive.padding(288))
    }), 296)

    companion object {
        val NATIVE_FONT: Key = Key.key("minecraft:default")
        val LIBRARY_FONT: Key = Key.key("arc:dialog_ui")
        val PREVIEW_FONT: Key = Key.key("arc:dialog_ui_preview")
        val PROSE = net.kyori.adventure.text.format.TextColor.color(0xE8DFD2)
        val FILLED_BODY_WIDTHS = listOf(300, 508, 508, 508)
        val ORNAMENTS = listOf("02", "11", "12", "16")
        private val decoration = DialogDecoration(LIBRARY_FONT, 0xF9F00, 0xF9F10)
        private val positive = PixelSpacing(LIBRARY_FONT, 0xF9F00)
        private val negative = PixelSpacing(LIBRARY_FONT, 0xF9F10)
        private val undecorated = TextDecoration.values().associateWith { TextDecoration.State.FALSE }

        private fun piece(point: Int, advance: Int) = DialogDecoration.Glyph(point, advance)
        private fun frame(base: Int) = DialogDecoration.ButtonFrame(piece(base, 16), piece(base + 1, 17), piece(base + 3, 15))
        private fun glyph(point: Int, font: Key = LIBRARY_FONT) = Component.text(String(Character.toChars(point)))
            .font(font).color(NamedTextColor.WHITE).decorations(undecorated)
        private fun lines(rows: List<Component>) = Component.empty().font(NATIVE_FONT).decorations(undecorated)
            .children(rows.flatMapIndexed { index, row -> if (index == 0) listOf(row) else listOf(Component.newline(), row) })

        internal fun nativeCell(text: Component, width: Int, alignment: TextAlignment = TextAlignment.LEFT): Component {
            val result = DialogTextLayout.layout(text.font(NATIVE_FONT), alignment, width + 8)
            require(result is TextLayoutResult.Aligned && result.lineCount == 1) { "Gallery sample exceeds its ${width}px cell: $result" }
            return result.component
        }

        internal fun actionLabel(index: Int, label: Component): Component {
            require(index in 0..20)
            val iconIndex = if (index == 20) 3 else index
            val iconAdvance = if (iconIndex in listOf(8, 12, 16, 17, 19)) 10 else 11
            return decoration.buttonLabel(label.font(NATIVE_FONT), 200,
                frame(if (index == 20) 0xF9050 else 0xF9000 + index * 4), piece(0xF9060 + iconIndex, iconAdvance), index == 4)
        }

        /** Static specimen placement only; shared PixelSpacing and DialogTextLayout own spacing and text measurement. */
        private class Row(private val positive: PixelSpacing = Companion.positive, private val negative: PixelSpacing = Companion.negative) {
            private val output = Component.text()
            private var cursor = 0
            fun at(x: Int, value: Component, advance: Int): Row {
                move(x - cursor)
                output.append(value)
                cursor = x + advance
                return this
            }
            private fun move(amount: Int) {
                output.append((if (amount >= 0) positive.padding(amount) else negative.padding(-amount)).color(NamedTextColor.WHITE))
                cursor += amount
            }
            fun finish(width: Int): Component { move(width - cursor); return output.build() }
        }
    }
}
