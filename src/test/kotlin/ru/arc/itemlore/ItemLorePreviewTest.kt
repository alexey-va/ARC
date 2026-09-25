package ru.arc.itemlore

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.configuration.file.YamlConfiguration
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

    val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/item-lore.yml"))
    val config = resource.reader(Charsets.UTF_8).use(YamlConfiguration::loadConfiguration)
    val miniMessage = MiniMessage.miniMessage()
    val text: (String) -> Component = { key ->
        miniMessage.deserialize(requireNotNull(config.getString("text.$key")), Placeholder.unparsed("price", "5 монет"))
    }

    "preview is one outer frame with only the resulting lore and no table joints" {
        val frame = ItemLorePreview.framed(listOf(Component.text("Добавлено", NamedTextColor.WHITE)), text)
        val rendered = plain.serialize(frame.text)

        frame.width shouldBe 320
        rendered shouldContain "Добавлено"
        rendered shouldContain "\uE570"
        rendered shouldContain "\uE573"
        rendered shouldContain "\uE57B"
        rendered shouldContain "\uE57E"
        listOf("Было", "Станет", "\uE572", "\uE575", "\uE579", "\uE57D").forEach {
            rendered shouldNotContain it
        }
    }

    "preserves colored lore, an actual blank line and long wrapped text" {
        val long = "Редкое зачарование усиливает оружие при каждом точном ударе."
        val frame = ItemLorePreview.framed(listOf(
            Component.text("Зелёная строка", NamedTextColor.GREEN),
            Component.empty(),
            Component.text("Красная строка", NamedTextColor.RED),
            Component.text(long),
        ), text)
        val rendered = plain.serialize(frame.text)
        val rows = rendered.lines()
        val first = rows.indexOfFirst { "Зелёная строка" in it }
        val second = rows.indexOfFirst { "Красная строка" in it }
        second - first shouldBe 2
        rendered shouldNotContain "(пустая строка)"
        long.split(' ').forEach { rendered shouldContain it }
        coloredRuns(frame.text).any { (content, color) ->
            "Зелёная строка" in content && color == NamedTextColor.GREEN
        } shouldBe true
        coloredRuns(frame.text).any { (content, color) ->
            "Красная строка" in content && color == NamedTextColor.RED
        } shouldBe true
    }

    "clearing lore shows its explanation inside the same single frame" {
        val rendered = plain.serialize(ItemLorePreview.framed(emptyList(), text).text)
        rendered shouldContain "описание будет очищено"
        rendered shouldContain "\uE570"
        rendered shouldNotContain "\uE575"
    }

    "editing help lists every legacy color and effect with readable examples" {
        val help = ItemLorePreview.editorHelp(text("editor-body"), text)
        help.size shouldBe 3
        help.forEach { it.width shouldBe 320 }
        val intro = plain.serialize(help[0].text)
        val codes = plain.serialize(help[1].text)
        (intro.lines().size <= 6) shouldBe true
        (codes.lines().size <= 16) shouldBe true
        intro shouldContain "5 монет"
        intro shouldNotContain "\uE575"
        ("0123456789abcdefklmnor".map { "&$it" } + listOf("&#RRGGBB", "&&")).forEach { codes shouldContain it }
        val example = plain.serialize(help[2].text)
        example shouldContain "&a&lМеч&r"
        example shouldContain "Новый цвет снимает эффекты"
        example shouldContain "&#FFAA00Текст"
        codes shouldNotContain "\uE579"
        coloredRuns(help[1].text).any { (content, color) ->
            "Аа" in content && color == NamedTextColor.GREEN
        } shouldBe true
    }
})
