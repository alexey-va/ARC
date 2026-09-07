package ru.arc.dialogdemo

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.Config
import ru.arc.text.TextAlignment
import java.nio.file.Files
import java.nio.file.Path

class DialogDesignGalleryTest : FreeSpec({
    "every table and divider preserves exact line widths using the shipped pack and exports the real components" {
        val directory = Files.createTempDirectory("dialog-design-gallery")
        try {
            val destination = directory.resolve("modules/dialog-demo.yml")
            Files.createDirectories(destination.parent)
            javaClass.getResourceAsStream("/modules/dialog-demo.yml")!!.use { Files.copy(it, destination) }
            val config = Config(directory, "modules/dialog-demo.yml")
            val mm = MiniMessage.miniMessage()
            fun text(key: String): Component {
                val value = config.string(key, "MISSING")
                require(value != "MISSING") { "Missing gallery copy $key" }
                return mm.deserialize(value)
            }
            val fonts = javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json")!!.bufferedReader()
                .use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
            fun lineWidths(component: Component): List<Int> {
                val lines = mutableListOf(0)
                fun visit(node: Component, inherited: Style) {
                    val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
                    val font = style.font()?.asString() ?: "minecraft:default"
                    val bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                    require(node is TextComponent)
                    node.content().codePoints().forEach { point ->
                        if (point == 10) lines += 0
                        else {
                            val width = if (point in 0xF0F01..0xF0F0A) {
                                bold shouldBe false
                                1 shl (point - 0xF0F01)
                            } else if (point == 0xF0F11) {
                                bold shouldBe false
                                -1
                            } else if (point in 0xE540..0xE59E && (point and 0xF) <= 14) {
                                bold shouldBe false
                                val style = (point - 0xE540) / 0x10
                                when (point and 0xF) {
                                    4 -> listOf(2, 2, 2, 4, 4, 6)[style]
                                    5 -> listOf(6, 6, 6, 6, 6, 7)[style]
                                    6 -> listOf(10, 10, 10, 8, 8, 8)[style]
                                    10 -> if (style >= 4) 8 else 10
                                    else -> 10
                                }
                            } else requireNotNull(fonts.getAsJsonObject(font)
                                .getAsJsonObject(if (bold) "bold" else "normal").get("U+%04X".format(point))) {
                                "Unmeasured U+%04X".format(point)
                            }.asInt
                            lines[lines.lastIndex] += width
                        }
                    }
                    node.children().forEach { visit(it, style) }
                }
                visit(component, Style.empty())
                return lines
            }
            val export = linkedMapOf<String, Map<String, Map<String, String>>>()
            for ((family, count) in listOf("tables" to DialogDesignGallery.TABLE_COUNT, "dividers" to DialogDesignGallery.DIVIDER_COUNT)) {
                export[family] = (1..count).associate { number ->
                    val component = if (family == "tables") DialogDesignGallery.table(number, ::text)
                        else DialogDesignGallery.divider(number, ::text)
                    val widths = lineWidths(component)
                    require(widths.isNotEmpty())
                    widths.forEach { it shouldBe DialogDesignGallery.WIDTH }
                    if (family == "tables") require(widths.size in 5..9) { "Table $number is too tall: ${widths.size}" }
                    number.toString() to mapOf(
                        "title" to mm.serialize(text("gallery.$family.$number.title")),
                        "note" to mm.serialize(text("gallery.$family.$number.note")),
                        "content" to mm.serialize(component))
                } + mapOf("ui" to buildMap {
                    put("title", mm.serialize(text("title.$family")))
                    put("intro", mm.serialize(text("gallery.$family.intro")))
                    put("page", mm.serialize(text("gallery.page")))
                    put("page-active", mm.serialize(text("gallery.page-button").color(net.kyori.adventure.text.format.TextColor.color(0x9BD48D))))
                    put("page-idle", mm.serialize(text("gallery.page-button").color(net.kyori.adventure.text.format.TextColor.color(0x92BED8))))
                    put("back", mm.serialize(text("back")))
                    put("back-tip", mm.serialize(text("back-tip")))
                    for (page in 1..if (family == "tables") 6 else 3) {
                        put("page-$page", mm.serialize(text("gallery.$family.page-$page")))
                    }
                })
            }
            val output = Path.of("build/reports/dialog-designs/content.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
        } finally { directory.toFile().deleteRecursively() }
    }
    "numeric cells align to their fixed right edge without relying on character count" {
        val mm = MiniMessage.miniMessage()
        for (number in listOf("6", "87", "312", "1 248")) {
            val result = DialogDesignGallery.cell(Component.text(number), 100, TextAlignment.RIGHT)
            val serialized = mm.serialize(result)
            require(serialized.contains(number))
            // A right-aligned cell's final visible child is the unchanged value; padding precedes it.
            (result.children().last() as TextComponent).content() shouldBe number
        }
    }
})
