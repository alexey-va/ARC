@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Existing Core headless presenter; real dispatch and shared history.

package ru.arc.dialogdemo

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Player
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.subcommands.DialogDemoSubCommand
import ru.arc.config.Config
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogSessionRegistration
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path

class DialogLibraryGalleryTest : FreeSpec() {
    private fun withCopy(test: ((String) -> Component) -> Unit) {
        val directory = Files.createTempDirectory("dialog-library-gallery")
        try {
            val target = directory.resolve("modules/dialog-demo.yml")
            Files.createDirectories(target.parent)
            javaClass.getResourceAsStream("/modules/dialog-demo.yml")!!.use { Files.copy(it, target) }
            val config = Config(directory, "modules/dialog-demo.yml")
            test { key ->
                val value = config.string(key, "MISSING")
                withClue(key) { value shouldNotBe "MISSING" }
                MiniMessage.miniMessage().deserialize(value)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    init {
        "dialog preview aliases the existing demo command and retains configured aliases" {
            mockkObject(CommandConfig, DialogDemoModule)
            try {
                every { CommandConfig.getAliases("dialogdemo") } returns listOf("dialogs")
                DialogDemoSubCommand.aliases shouldBe listOf("dialogs", "dialog")
                val player = mockk<Player>()
                every { DialogDemoModule.open(player, any(), any()) } returns Unit
                (DialogDemoSubCommand.execute(player, arrayOf("preview"))) shouldBe true
                verify(exactly = 1) { DialogDemoModule.open(player, "preview", "") }
                ("design" in DialogDemoModule.pages) shouldBe true
                ("preview" in DialogDemoSubCommand.tabComplete(player, arrayOf("pre"))) shouldBe true
            } finally { unmockkObject(CommandConfig, DialogDemoModule) }
        }

        "root exposes all families with filled frame first and every specimen exports actual components" { withCopy { copy ->
            val gallery = DialogLibraryGallery(mockk(), copy)
            val root = gallery.screen(DialogLibraryGallery.Page(), true) { _, _ -> }
            root.buttons.first().id.value shouldBe "filled"
            root.buttons.map { it.id.value } shouldBe DialogLibraryGallery.Section.entries.map { it.key }
            (root.buttons.all { !it.closeDialogBeforeAction }) shouldBe true
            val screens = mutableMapOf("root" to root)
            var actionSamples = 0
            for (section in DialogLibraryGallery.Section.entries) {
                repeat(section.pageCount) { index ->
                    val page = DialogLibraryGallery.Page(section, index)
                    val screen = gallery.screen(page, false) { _, _ -> }
                    withClue("Pagination must replace the same history visit") { screen.id shouldBe "dialog_design.${section.key}" }
                    (screen.buttons.all { !it.closeDialogBeforeAction }) shouldBe true
                    screen.buttons.any { it.id.value == "previous" } shouldBe (index > 0)
                    screen.buttons.any { it.id.value == "next" } shouldBe (index + 1 < section.pageCount)
                    if (section == DialogLibraryGallery.Section.ACTIONS) actionSamples += screen.buttons.count { it.id.value.startsWith("sample_") }
                    screens["${section.key}-$index"] = screen
                }
            }
            actionSamples shouldBe 21
            screens.getValue("ornaments-0").buttons.size shouldBe 4
            val progressPoints = spans(screens.getValue("text-5").body[3].text).flatMap { it.text.codePoints().toArray().toList() }
            for (point in 0xF9138..0xF9147) withClue("Missing progress glyph U+${point.toString(16)}") { (point in progressPoints) shouldBe true }
            for (screen in screens.values) {
                for (component in listOf(screen.title) + screen.body.map { it.text } + screen.buttons.flatMap { listOf(it.label, it.tooltip) }) {
                    for (span in spans(component)) {
                        if (span.text.any { it.isLetterOrDigit() }) span.style.font()?.asString() shouldBe "minecraft:default"
                    }
                }
            }
            val serializer = GsonComponentSerializer.gson()
            fun component(value: Component) = JsonParser.parseString(serializer.serialize(value))
            val export = screens.mapValues { (_, screen) -> mapOf(
                "id" to screen.id, "title" to component(screen.title), "columns" to screen.columns,
                "body" to screen.body.map { mapOf("text" to component(it.text), "width" to it.width) },
                "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to component(it.label), "tooltip" to component(it.tooltip), "width" to it.width) },
                "exit" to screen.exitButton?.let { mapOf("text" to component(it.label), "tooltip" to component(it.tooltip), "width" to it.width) },
            ) }
            val target = Path.of("build/reports/dialog-library/screens.json")
            Files.createDirectories(target.parent)
            Files.writeString(target, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
        } }

        "filled gallery uses compact one column and independently wrapped wide columns without client overflow" { withCopy { copy ->
            val gallery = DialogLibraryGallery(mockk(), copy)
            for (sample in 0..3) {
                val body = gallery.screen(DialogLibraryGallery.Page(DialogLibraryGallery.Section.FILLED, sample), false) { _, _ -> }.body[3]
                val rows = mutableListOf(mutableListOf<Span>())
                for (span in spans(body.text)) {
                    span.text.split('\n').forEachIndexed { index, text ->
                        if (index > 0) rows += mutableListOf<Span>()
                        if (text.isNotEmpty()) rows.last() += span.copy(text = text)
                    }
                }
                body.width shouldBe listOf(300, 508, 508, 508)[sample]
                rows.map(::advance) shouldBe List(rows.size) { body.width - 10 }
                val clientRows = rows.flatMap { clientLines(it, body.width - 8) }
                withClue("The client checks each prefix before reading the following negative spacer") {
                    clientRows.size shouldBe rows.size
                    clientRows.map { row -> row.sumOf { it.advance } } shouldBe List(rows.size) { body.width - 10 }
                }
                val background = rows.first().flatMap { it.text.codePoints().toArray().toList() }
                    .filter { it in 0xFA300..0xFAF1B }
                background.map { (it - 0xFA300) / 16 }.toSet() shouldBe rows.indices.toSet()
                background.count { (it and 15) in listOf(3, 7, 11) } shouldBe rows.size * listOf(0, 1, 2, 0)[sample]
                rows.first().forEach {
                    it.style.font()?.asString() shouldBe "arc:dialog_ui_preview"
                    it.style.shadowColor() shouldBe ShadowColor.none()
                }
                for (row in rows.drop(1)) {
                    row.none { span -> span.text.codePoints().anyMatch { it in 0xFA300..0xFAF1B } } shouldBe true
                    row.filter { it.text.any(Char::isLetter) || it.text.codePoints().anyMatch { point -> point == 0x1F4B0 } }.forEach {
                        it.style.font()?.asString() shouldBe "minecraft:default"
                        withClue("Native words and coin retain the client's default shadow") { it.style.shadowColor().shouldBeNull() }
                        if (it.text.codePoints().anyMatch { point -> point == 0x1F4B0 }) {
                            withClue("The resource-pack coin must retain its original palette") { it.style.color() shouldBe NamedTextColor.WHITE }
                        }
                    }
                }
                if (sample in listOf(1, 3)) (rows.size > 6) shouldBe true
                if (sample in listOf(0, 3)) spans(body.text).any { it.text.codePoints().anyMatch { point -> point == 0x1F4B0 } } shouldBe true
            }
        } }

        "modular gallery exposes every skin layout size and icon through shared bounded components" { withCopy { copy ->
            val gallery = DialogLibraryGallery(mockk(), copy)
            val seenFonts = mutableSetOf<String>()
            val seenIcons = mutableSetOf<Int>()
            for (section in listOf(DialogLibraryGallery.Section.PANELS, DialogLibraryGallery.Section.LAYOUTS,
                DialogLibraryGallery.Section.SIZES, DialogLibraryGallery.Section.ICONS)) {
                repeat(section.pageCount) { index ->
                    val screen = gallery.screen(DialogLibraryGallery.Page(section, index), false) { _, _ -> }
                    val panels = screen.body.filter { body -> spans(body.text).any { it.style.font()?.asString()?.startsWith("arc:dialog_panel_") == true } }
                    panels.size shouldBe 1
                    panels.forEach { body ->
                        val rows = mutableListOf(mutableListOf<Span>())
                        spans(body.text).forEach { span -> span.text.split('\n').forEachIndexed { row, text ->
                            if (row > 0) rows += mutableListOf<Span>()
                            if (text.isNotEmpty()) rows.last() += span.copy(text = text)
                        } }
                        rows.forEach { row ->
                            advance(row) shouldBe body.width - 10
                            withClue("${section.key}-$index must stay inside the actual client wrap budget") {
                                clientLines(row, body.width - 8).size shouldBe 1
                            }
                        }
                        for (span in spans(body.text)) {
                            val font = span.style.font()?.asString()
                            if (font?.startsWith("arc:dialog_panel_") == true) seenFonts += font
                            span.text.codePoints().forEach { point ->
                                if (point in 0x10A000..0x10A009) {
                                    seenIcons += point
                                    span.style.font() shouldBe Key.key("arc:dialog_field_icons")
                                    span.style.color() shouldBe NamedTextColor.WHITE
                                    span.style.shadowColor().shouldBeNull()
                                }
                            }
                        }
                    }
                }
            }
            seenFonts shouldBe setOf("arc:dialog_panel_navy", "arc:dialog_panel_plum", "arc:dialog_panel_evergreen",
                "arc:dialog_panel_cocoa", "arc:dialog_panel_ornate")
            seenIcons shouldBe (0x10A000..0x10A009).toSet()
            val progress = gallery.screen(DialogLibraryGallery.Page(DialogLibraryGallery.Section.ELEMENTS, 2), false) { _, _ -> }
            val unknown = spans(progress.body[7].text).joinToString("") { it.text }
            unknown.contains("Нет данных") shouldBe true
            unknown.codePoints().anyMatch { it in 0xF9138..0xF9147 } shouldBe false
            val ornate = gallery.screen(DialogLibraryGallery.Page(DialogLibraryGallery.Section.PANELS, 4), false) { _, _ -> }
            spans(ornate.body[3].text).joinToString("") { it.text }.count { it == '◆' } shouldBe 2
        } }

        "legacy and modular element samples share list spacing step joins and tight progress geometry" { withCopy { copy ->
            val gallery = DialogLibraryGallery(mockk(), copy)
            fun body(section: DialogLibraryGallery.Section, index: Int) =
                gallery.screen(DialogLibraryGallery.Page(section, index), false) { _, _ -> }.body[3]
            fun rows(component: Component): List<List<Span>> {
                val rows = mutableListOf(mutableListOf<Span>())
                spans(component).forEach { span -> span.text.split('\n').forEachIndexed { index, text ->
                    if (index > 0) rows += mutableListOf<Span>()
                    if (text.isNotEmpty()) rows.last() += span.copy(text = text)
                } }
                return rows
            }
            fun positions(row: List<Span>): List<Triple<Int, Int, Int>> {
                var cursor = 0
                return row.flatMap { span -> span.text.codePoints().toArray().map { point ->
                    val width = advance(point, span.style)
                    Triple(point, cursor, width).also { cursor += width }
                } }.filterNot { (point, _, _) -> point in 0xF9F00..0xF9F19 || point in 0xF0F01..0xF0F0A }
            }
            for ((section, indices) in listOf(
                DialogLibraryGallery.Section.TEXT to Triple(3, 2, 5),
                DialogLibraryGallery.Section.ELEMENTS to Triple(0, 1, 2),
            )) {
                val (listIndex, stepIndex, progressIndex) = indices
                val list = body(section, listIndex)
                val listRows = rows(list.text)
                listRows.count { positions(it).isEmpty() } shouldBe 2
                listRows.forEach { advance(it) shouldBe list.width - 8 }
                val steps = body(section, stepIndex)
                rows(steps.text).forEach { row ->
                    advance(row) shouldBe steps.width - 8
                    positions(row).filter { it.first in 0xF9108..0xF910B }.forEach { it.second shouldBe 0 }
                    positions(row).isEmpty() shouldBe false
                }
                val progress = body(section, progressIndex)
                val progressRows = rows(progress.text)
                progressRows.forEachIndexed { index, row ->
                    val glyphs = positions(row)
                    if (glyphs.any { it.first in 0xF9138..0xF9147 }) {
                        val starts = glyphs.filter { it.first in 0xF9138..0xF9147 && it.first % 2 == 0 }
                        starts.zipWithNext().forEach { (left, right) -> right.second - left.second shouldBe 18 }
                        val caption = positions(progressRows[index - 1])
                        caption.first().second shouldBe starts.first().second
                        caption.last().second + caption.last().third shouldBe glyphs.last().second + glyphs.last().third
                        caption.none { it.first == '·'.code } shouldBe true
                    }
                    clientLines(row, progress.width - 8).size shouldBe 1
                }
            }
        } }

        "pagination and repeated sample clicks return to the real root once without closing between pages" { withCopy { copy ->
            MockBukkitTestRuntime.open().use { paper ->
                val player: Player = spyk(paper.addPlayer("GalleryViewer"))
                every { player.isOnline } returns true
                var closes = 0
                every { player invokeNoArgs "closeDialog" } answers { closes++; Unit }
                val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
                lateinit var shown: PaperDialogScreen
                lateinit var registration: PaperDialogSessionRegistration
                val previousHook = HookRegistry.luckPermsHook
                try {
                    HookRegistry.luckPermsHook = null
                    PaperDialogRuntime(paper.createSimplePlugin("GalleryHistory")) { _, screen, registered ->
                        shown = screen
                        registration = registered
                    }.use { runtime ->
                        val gallery = DialogLibraryGallery(runtime, copy)
                        fun click(id: String) {
                            val key = registration.key(PaperDialogActionId.of(id))
                            runtime.onCustomClick(mockk {
                                every { commonConnection } returns connection
                                every { identifier } returns Key.key(key)
                                every { dialogResponseView } returns null
                            })
                        }
                        gallery.open(player)
                        repeat(3) {
                            click("actions")
                            click("sample_21")
                            click("next")
                            click("next")
                            click("sample_back")
                            click("exit")
                            shown.id shouldBe "dialog_design.root"
                        }
                        click("filled")
                        click("next")
                        shown.id shouldBe "dialog_design.filled"
                        click("exit")
                        shown.id shouldBe "dialog_design.root"
                        click("panels")
                        repeat(4) { click("next") }
                        shown.id shouldBe "dialog_design.panels"
                        click("exit")
                        shown.id shouldBe "dialog_design.root"
                        closes shouldBe 0
                        click("exit")
                        closes shouldBe 1
                        HookRegistry.luckPermsHook = mockk {
                            every { getCachedMeta(player.uniqueId, "arc-menu-escape") } returns "close"
                        }
                        gallery.open(player)
                        click("filled")
                        click("exit")
                        withClue("The Close preference must end the entire flow from a child") { closes shouldBe 2 }
                        withClue("Close must not reopen the root") { shown.id shouldBe "dialog_design.filled" }
                    }
                } finally { HookRegistry.luckPermsHook = previousHook }
            }
        } }
    }

    private data class Span(val text: String, val style: Style)
    private fun spans(component: Component): List<Span> = buildList {
        fun visit(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            require(node is TextComponent)
            if (node.content().isNotEmpty()) add(Span(node.content(), style))
            node.children().forEach { visit(it, style) }
        }
        visit(component, Style.empty())
    }

    private val fonts by lazy {
        javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json")!!.bufferedReader()
            .use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
    }
    private fun advance(point: Int, style: Style): Int = when (point) {
        // Registered library cell advances; the native text snapshot excludes this artwork font.
        in 0xF9F00..0xF9F09 -> 1 shl (point - 0xF9F00)
        in 0xF9F10..0xF9F19 -> -(1 shl (point - 0xF9F10))
        in 0xF9108..0xF910A -> 13
        0xF910B -> 6
        0xF910C, 0xF910D -> 10
        in 0xF9138..0xF9147 -> if (point % 2 == 0) 10 else 9
        in 0xFA300..0xFAF1B -> 17
        in 0x100000..0x109834 -> 17
        in 0xFA200..0xFA209 -> -(1 shl (point - 0xFA200))
        in 0xFA220..0xFA229 -> 1 shl (point - 0xFA220)
        else -> fonts.getAsJsonObject(style.font()?.asString() ?: "minecraft:default")
            .getAsJsonObject(if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal")
            .get("U+%04X".format(point)).asInt
    }

    private fun advance(row: List<Span>): Int = row.sumOf { span ->
        span.text.codePoints().toArray().sumOf { advance(it, span.style) }
    }

    private data class Glyph(val point: Int, val advance: Int)

    /** LineBreakFinder.accept / splitLines from the official 1.21.11 and 26.3 clients, per authored row. */
    private fun clientLines(row: List<Span>, maxWidth: Int): List<List<Glyph>> {
        val glyphs = row.flatMap { span ->
            span.text.codePoints().toArray().map { Glyph(it, advance(it, span.style)) }
        }
        return buildList {
            var start = 0
            while (start < glyphs.size) {
                var end = start
                var width = 0
                var hadNonZeroWidth = false
                var lastSpace = -1
                while (end < glyphs.size) {
                    val glyph = glyphs[end]
                    if (glyph.point == ' '.code) lastSpace = end
                    width += glyph.advance
                    if (hadNonZeroWidth && width > maxWidth.coerceAtLeast(1)) break
                    hadNonZeroWidth = hadNonZeroWidth || glyph.advance != 0
                    end++
                }
                if (end < glyphs.size && lastSpace >= start) end = lastSpace
                add(glyphs.subList(start, end))
                start = if (end < glyphs.size && glyphs[end].point == ' '.code) end + 1 else end
            }
        }
    }
}
