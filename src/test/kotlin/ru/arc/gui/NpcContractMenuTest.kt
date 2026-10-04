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

class NpcContractMenuTest : StringSpec({
    "NPC desks match adaptive chest geometry and display base price and growth without italics" {
        MockBukkitTestRuntime.open().use {
            val catalog = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            for (rows in 3..6) {
                val layout = catalog.catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(rows))
                layout.rows shouldBe rows
                val depositStart = (rows - 2) * 9
                layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).map { it.index } shouldBe (depositStart until depositStart + 9).toList()
                layout.region(ArcMenuSchema.CONTRACT_DESK_ORDERS).size shouldBe depositStart
                layout.slot("sell").index shouldBe (rows - 1) * 9 + 4
                layout.backgroundTemplate shouldBe MenuTemplateId.of("background")
                layout.pagination shouldBe null
            }
            val item = PaperMenuItemFactory().create(catalog.template(MenuTemplateId.of("contract-desk-order")),
                PaperMenuItemRenderContext(values = mapOf(
                    "name" to Component.text("Любая сырая рыба"), "price" to Component.text("1.20"),
                    "remaining" to Component.text("64"), "state" to Component.text("Положите товар в пустой ряд"),
                    "base" to Component.text("1.20"), "growth" to Component.text("+25%"), "rank" to Component.text("+0%"),
                    "accepted" to Component.text("Треска, лосось, тропическая рыба и иглобрюх"),
                ))).withType(Material.COD)
            item.type shouldBe Material.COD
            item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            item.itemMeta.lore()!!.forEach { row -> row.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
            val text = item.itemMeta.lore()!!.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
            text.contains("1.20 💰") shouldBe true
            text.contains("64 шт.") shouldBe true
            text.contains("+25% от базовой") shouldBe true
        }
    }
})
