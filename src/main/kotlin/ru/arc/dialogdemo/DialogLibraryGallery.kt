package ru.arc.dialogdemo

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTextLayout
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
        FILLED("filled", 2),
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
                    body("filled.${page.index}.title", FILLED_BODY_WIDTH)
                    bodies += filledFrame((0..3).map { text("filled.${page.index}.row-$it") })
                    body("filled.note", FILLED_BODY_WIDTH)
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

    private fun composition(index: Int): PaperDialogBody = when (index) {
        0 -> decoration.openCorners(text("text.0.sample"), 320, DialogDecoration.Corners(
            piece(0xF9100, 13), piece(0xF9101, 13), piece(0xF9102, 13), piece(0xF9103, 13)))
        1 -> PaperDialogBody(lines(listOf(
            nativeCell(text("text.1.sample-title"), 312, TextAlignment.CENTER),
            decoration.divider(320, DialogDecoration.Divider(piece(0xF9104, 13), piece(0xF9106, 13), piece(0xF9105, 13))),
            nativeCell(text("text.1.sample"), 312),
        )), 320)
        2 -> PaperDialogBody(lines((0..2).flatMap { step ->
            val node = Row().at(0, glyph(0xF9108 + step), 13)
                .at(20, nativeCell(text("text.2.step-$step"), 280), 280).finish(300)
            val detail = Row().apply { if (step < 2) at(0, glyph(0xF910B), 6) }
                .at(20, nativeCell(text("text.2.detail-$step"), 280), 280).finish(300)
            if (step == 2) listOf(node, detail)
            else listOf(node, detail, Row().at(0, glyph(0xF910B), 6).finish(300))
        }), 308)
        3 -> PaperDialogBody(lines((0..2).flatMap { index -> listOf(
            Row().at(0, glyph(if (index < 2) 0xF910C else 0xF910D), 10)
                .at(20, nativeCell(text("text.3.check-$index"), 280), 280).finish(300),
            Row().at(20, nativeCell(text("text.3.detail-$index"), 280), 280).finish(300),
        ) }), 308)
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

    private fun progress(): PaperDialogBody = PaperDialogBody(lines((0..3).flatMap { palette ->
        val base = 0xF9138 + palette * 4
        val labels = Row().at(36, nativeCell(text("text.5.palette-$palette"), 150), 150)
            .at(186, nativeCell(text("text.5.value"), 66, TextAlignment.RIGHT), 66).finish(288)
        val bar = Row().apply {
            for (segment in 0 until 12) {
                val point = base + if (segment < 7) 0 else 2
                at(36 + segment * 18, glyph(point), 10)
                at(45 + segment * 18, glyph(point + 1), 9)
            }
        }.finish(288)
        if (palette == 3) listOf(labels, bar) else listOf(labels, bar, positive.padding(288))
    }), 296)

    companion object {
        val NATIVE_FONT: Key = Key.key("minecraft:default")
        val LIBRARY_FONT: Key = Key.key("arc:dialog_ui")
        val PREVIEW_FONT: Key = Key.key("arc:dialog_ui_preview")
        val PROSE = net.kyori.adventure.text.format.TextColor.color(0xE8DFD2)
        const val FILLED_WIDTH = 498
        const val FILLED_BODY_WIDTH = 506
        val ORNAMENTS = listOf("02", "11", "12", "16")
        private val decoration = DialogDecoration(LIBRARY_FONT, 0xF9F00, 0xF9F10)
        private val positive = PixelSpacing(LIBRARY_FONT, 0xF9F00)
        private val negative = PixelSpacing(LIBRARY_FONT, 0xF9F10)
        private val previewPositive = PixelSpacing(PREVIEW_FONT, 0xFA220)
        private val previewNegative = PixelSpacing(PREVIEW_FONT, 0xFA200)
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

        /** v02 is exactly 6 lines / 54px. The whole opaque panel precedes every native foreground/shadow pixel. */
        internal fun filledFrame(content: List<Component>): PaperDialogBody {
            require(content.size == 4) { "The installed filled-frame bitmap supports exactly four content rows" }
            val background = Row(previewPositive, previewNegative).apply {
                at(0, glyph(0xFA110, PREVIEW_FONT), 17)
                for (x in 16..464 step 16) at(x, glyph(0xFA111, PREVIEW_FONT), 17)
                at(466, glyph(0xFA111, PREVIEW_FONT), 17)
                at(482, glyph(0xFA112, PREVIEW_FONT), 17)
            }.finish(FILLED_WIDTH).shadowColor(ShadowColor.none())
            val foreground = content.map {
                Component.empty().append(previewPositive.padding(16))
                    .append(nativeCell(it, 466)).append(previewPositive.padding(16))
            }
            return PaperDialogBody(lines(listOf(background) + foreground + previewPositive.padding(FILLED_WIDTH)), FILLED_BODY_WIDTH)
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
