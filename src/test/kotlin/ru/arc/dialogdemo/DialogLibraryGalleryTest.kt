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
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Player
import io.kotest.assertions.throwables.shouldThrow
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

        "filled frame draws one complete background before four native rows and preserves foreground shadows" { withCopy { copy ->
            for (sample in 0..1) {
                val content = (0..3).map { copy("design.filled.$sample.row-$it") }
                val body = DialogLibraryGallery.filledFrame(content)
                body.width shouldBe 506
                val rows = mutableListOf(mutableListOf<Span>())
                for (span in spans(body.text)) {
                    span.text.split('\n').forEachIndexed { index, text ->
                        if (index > 0) rows += mutableListOf<Span>()
                        if (text.isNotEmpty()) rows.last() += span.copy(text = text)
                    }
                }
                rows.size shouldBe 6
                rows.map(::advance) shouldBe List(6) { 498 }
                val background = rows.first().flatMap { it.text.codePoints().toArray().toList() }
                background.count { it == 0xFA110 } shouldBe 1
                background.count { it == 0xFA111 } shouldBe 30
                background.count { it == 0xFA112 } shouldBe 1
                rows.first().forEach { it.style.shadowColor() shouldBe ShadowColor.none() }
                for (row in rows.drop(1)) (row.none { span -> span.text.codePoints().anyMatch { it in 0xFA110..0xFA112 } }) shouldBe true
                for (row in rows.slice(1..4)) {
                    val text = row.filter { it.text.any(Char::isLetter) }
                    (text.isNotEmpty()) shouldBe true
                    text.forEach {
                        it.style.font()?.asString() shouldBe "minecraft:default"
                        withClue("Native foreground must retain the client's default shadow") { it.style.shadowColor().shouldBeNull() }
                    }
                }
            }
            shouldThrow<IllegalArgumentException> { DialogLibraryGallery.filledFrame(listOf(Component.text("short"))) }
            shouldThrow<IllegalArgumentException> { DialogLibraryGallery.filledFrame(List(4) { Component.text("word ".repeat(160)) }) }
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
    private fun advance(row: List<Span>): Int = row.sumOf { span ->
        span.text.codePoints().toArray().sumOf { point ->
            when (point) {
                in 0xFA110..0xFA112 -> 17
                in 0xFA200..0xFA209 -> -(1 shl (point - 0xFA200))
                in 0xFA220..0xFA229 -> 1 shl (point - 0xFA220)
                else -> fonts.getAsJsonObject(span.style.font()?.asString() ?: "minecraft:default")
                    .getAsJsonObject(if (span.style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal")
                    .get("U+%04X".format(point)).asInt
            }
        }
    }
}
