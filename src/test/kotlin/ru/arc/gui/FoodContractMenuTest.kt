package ru.arc.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.testing.MockBukkitTestRuntime

class FoodContractMenuTest : StringSpec({
    "food catalog keeps all 21 offers separate from seven deposit slots and preserves priced nonitalic tooltips" {
        MockBukkitTestRuntime.open().use {
            val catalog = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            val layout = catalog.catalog.require(ArcMenuSchema.FOOD_CONTRACTS)
            layout.rows shouldBe 6
            layout.region(ArcMenuSchema.FOOD_ORDERS).size shouldBe 21
            layout.region(ArcMenuSchema.FOOD_DEPOSIT).map { it.index } shouldBe (46..52).toList()
            layout.backgroundTemplate shouldBe MenuTemplateId.of("background")
            val item = PaperMenuItemFactory().create(catalog.template(MenuTemplateId.of("food-contract-order")),
                PaperMenuItemRenderContext(values = mapOf(
                    "name" to Component.text("Любая сырая рыба"), "price" to Component.text("1.20"),
                    "remaining" to Component.text("64"), "state" to Component.text("Выбран для сдачи"),
                    "accepted" to Component.text("Треска, лосось, тропическая рыба и иглобрюх"),
                ))).withType(Material.COD)
            item.type shouldBe Material.COD
            item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            item.itemMeta.lore()!!.forEach { row -> row.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
            val text = item.itemMeta.lore()!!.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
            text.contains("1.20 💰") shouldBe true
            text.contains("64 шт.") shouldBe true
        }
    }
})
