package ru.arc.autobuild.gui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.KotestTestBase
import ru.arc.autobuild.BuildBookSettings
import ru.arc.config.ConfigManager

class BuildBookEditorPresentationTest : KotestTestBase({
    val plain = PlainTextComponentSerializer.plainText()

    describe("build-book editor surface") {
        it("uses Escape instead of a close-only save control") {
            val config = ConfigManager.ofModule(dataPath, "auto-build.yml")

            BuildBookSettings.validate()
            config.stringOrNull("build-book.editor.close.name") shouldBe null
            config.stringListOrNull("build-book.editor.close.lore") shouldBe null

            config.componentList("build-book.editor.overview.lore") {
                tag("name", Component.text("Дом"))
                tag("rotation", Component.text(90))
                tag("offset_x", Component.text(2))
                tag("offset_y", Component.text(1))
                tag("offset_z", Component.text(-3))
            }.map(plain::serialize) shouldContainExactly listOf(
                "Настройте положение будущей постройки.",
                "",
                "Поворот: 90°",
                "Смещение: 2, 1, -3",
                "",
                "Изменения сохраняются сразу; открытое превью обновляется вместе с книгой.",
                "• Esc — закрыть настройку •",
            )
        }

        it("explicitly disables italics on every final name and lore root") {
            val item = BuildBookEditorPresentation.item(
                material = Material.BOOK,
                name = Component.text("Настройка книги"),
                lore = listOf(Component.text("Положение"), Component.empty(), Component.text("Esc — вернуться")),
            )
            val meta = checkNotNull(item.itemMeta)

            checkNotNull(meta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            meta.lore().orEmpty().forEach { line ->
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
        }
    }
})
