package ru.arc.autobuild.gui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import ru.arc.KotestTestBase
import ru.arc.autobuild.BuildConfig

class BuildingGuiPresentationTest : KotestTestBase({
    val plain = PlainTextComponentSerializer.plainText()

    describe("active construction GUI presentation") {
        it("uses a dedicated active construction title") {
            BuildConfig.BuildingGui.title shouldBe "<dark_gray>Строительство"
        }

        it("keeps stable progress guidance while the percentage changes") {
            val item = ItemStackMock(Material.PAPER)

            BuildingGuiPresentation.progress(BuildConfig.config(), 37).applyTo(item)
            item.plainDisplay(plain) shouldBe "Ход строительства"
            item.plainLore(plain) shouldContainExactly listOf(
                "Завершено: 37%",
                "",
                "Панель обновляется автоматически.",
            )

            BuildingGuiPresentation.progress(BuildConfig.config(), 84).applyTo(item)
            item.plainLore(plain) shouldContainExactly listOf(
                "Завершено: 84%",
                "",
                "Панель обновляется автоматически.",
            )
            item.assertNotItalic()
        }

        it("restores the complete cancel action after confirmation expires") {
            val item = ItemStackMock(Material.RED_STAINED_GLASS_PANE)
            val normal = BuildingGuiPresentation.item(BuildConfig.config(), "building-gui.cancel")
            val confirmation = BuildingGuiPresentation.item(BuildConfig.config(), "building-gui.cancel-confirm")

            normal.applyTo(item)
            val originalName = item.plainDisplay(plain)
            val originalLore = item.plainLore(plain)

            confirmation.applyTo(item)
            item.plainDisplay(plain) shouldBe "Подтвердить отмену"
            item.plainLore(plain) shouldContainExactly listOf(
                "Строительство остановится сразу.",
                "",
                "Уже поставленные блоки останутся на месте.",
                "Использованная книга не вернётся.",
                "",
                "Нажмите ещё раз, чтобы отменить.",
            )
            item.assertNotItalic()

            normal.applyTo(item)
            item.plainDisplay(plain) shouldBe originalName
            item.plainLore(plain) shouldContainExactly originalLore
            item.assertNotItalic()
        }
    }
})

private fun ItemStackMock.plainDisplay(serializer: PlainTextComponentSerializer): String =
    serializer.serialize(checkNotNull(itemMeta.displayName()))

private fun ItemStackMock.plainLore(serializer: PlainTextComponentSerializer): List<String> =
    itemMeta.lore().orEmpty().map(serializer::serialize)

private fun ItemStackMock.assertNotItalic() {
    checkNotNull(itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    itemMeta.lore().orEmpty().forEach { line ->
        line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    }
}
