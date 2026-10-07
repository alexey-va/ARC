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
    "NPC desks match the approved six-row chest geometry and display base price and growth without italics" {
        MockBukkitTestRuntime.open().use {
            val catalog = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            val layout = catalog.catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(6))
            layout.rows shouldBe 6
            layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).map { it.index } shouldBe
                (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
            layout.region(ArcMenuSchema.CONTRACT_DESK_ORDERS).map { it.index } shouldBe
                (0 until 5).flatMap { row -> (0..3).map { row * 9 + it } } + listOf(46, 47)
            layout.slot("sell").index shouldBe 4
            layout.slot("previous").index shouldBe 45
            layout.slot("next").index shouldBe 48
            layout.backgroundTemplate shouldBe MenuTemplateId.of("background")
            layout.pagination shouldBe null
            val factory = PaperMenuItemFactory()
            for ((template, model) in listOf("contract-desk-previous" to 11009, "contract-desk-next" to 11008)) {
                val arrow = factory.create(catalog.template(MenuTemplateId.of(template)), PaperMenuItemRenderContext())
                arrow.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                arrow.itemMeta.customModelData shouldBe model
            }
            val filler = factory.create(catalog.template(MenuTemplateId.of("contract-desk-empty")), PaperMenuItemRenderContext())
            filler.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
            filler.itemMeta.hasCustomModelData() shouldBe false
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
    "Board NPC advert shows author and market premium in the shared paginated region" {
        MockBukkitTestRuntime.open().use {
            val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            configuration.catalog.require(ArcMenuSchema.BOARD).region(ArcMenuSchema.BOARD_ENTRIES).size shouldBe 45
            val item = PaperMenuItemFactory().create(configuration.template(MenuTemplateId.of("board-contract")),
                PaperMenuItemRenderContext(values = mapOf(
                    "name" to Component.text("Любая сырая рыба"), "author" to Component.text("Матео"),
                    "status" to Component.text("открыт"), "item" to Component.text("arc:any_raw_fish"),
                    "accepted" to Component.text("0"), "reserved" to Component.text("0"),
                    "target" to Component.text("100"), "progress" to Component.text("0"),
                    "remaining" to Component.text("100"), "payout" to Component.text("1.50"),
                    "base" to Component.text("1.20"), "growth" to Component.text("+25%"),
                    "budget" to Component.text("100.00"), "ends" to Component.text("12 октября"),
                    "action" to Component.text("Сдача у NPC: Матео · спавн"),
                ))).withType(Material.COD)
            item.type shouldBe Material.COD
            item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            val text = item.itemMeta.lore()!!.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
            text.contains("Объявитель: Матео") shouldBe true
            text.contains("1.50 💰") shouldBe true
            text.contains("Базовая цена: 1.20 💰") shouldBe true
            text.contains("Надбавка к базовой цене: +25%") shouldBe true
            text.contains("arc:any_raw_fish") shouldBe false
        }
    }

})
