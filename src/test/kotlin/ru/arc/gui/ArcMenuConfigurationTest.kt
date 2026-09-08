package ru.arc.gui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.menu.MenuTemplateId
import ru.arc.config.Config
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path

class ArcMenuConfigurationTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "bundled menu catalog validates every declared semantic screen" {
        val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)

        configuration.catalog.layouts.keys shouldContainExactly ArcMenuSchema.contracts.keys
        configuration.catalog.require(ArcMenuSchema.INVESTIGATION_HUB).slot("start").index shouldBe 13
        configuration.catalog.require(ArcMenuSchema.INVESTIGATION_CASE).region("witnesses")
            .map { it.index } shouldContainExactly listOf(18, 20, 22, 24, 26)
        ArcMenuSchema.PERSONAL_LOOT.forEach { (rows, menu) ->
            configuration.catalog.require(menu).apply {
                this.rows shouldBe rows
                region(ArcMenuSchema.PERSONAL_LOOT_ITEMS).size shouldBe rows * 9
            }
        }
        ArcMenuSchema.STORE.forEach { (rows, menu) ->
            configuration.catalog.require(menu).apply {
                this.rows shouldBe rows
                slot("back").index shouldBe (rows - 1) * 9
                region(ArcMenuSchema.STORE_ITEMS).size shouldBe (rows - 1) * 9
            }
        }
    }

    "investigation menu backgrounds render the canonical filler model" {
        val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)
        val factory = PaperMenuItemFactory()

        listOf(
            ArcMenuSchema.INVESTIGATION_HUB,
            ArcMenuSchema.INVESTIGATION_CASE,
            ArcMenuSchema.INVESTIGATION_VERDICT,
            ArcMenuSchema.INVESTIGATION_TESTIMONY,
        ).forEach { menu ->
            val templateId = requireNotNull(configuration.catalog.require(menu).backgroundTemplate)
            val item = factory.create(configuration.template(templateId), Component.empty(), emptyList())

            item.itemMeta.customModelData shouldBe 11000
        }
    }

    "contract book keeps tabs separate and renders readable amount and disabled preset states" {
        val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)
        val layout = configuration.catalog.require(ArcMenuSchema.CONTRACTS_LIST)
        layout.rows shouldBe 6
        layout.region("orders").size shouldBe 21
        val slots = layout.elements.keys.map { layout.slot(it).index }
        slots.distinct().size shouldBe slots.size
        val factory = PaperMenuItemFactory()
        val values = ArcMenuSchema.textContracts.getValue("contracts-order").values.associateWith { Component.text("64") }
        val card = factory.create(configuration.template(MenuTemplateId.of("contracts-order")), PaperMenuItemRenderContext(values = values))
        card.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        card.itemMeta.lore()!!.forEach { it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
        val plain = PlainTextComponentSerializer.plainText()
        card.itemMeta.lore()!!.any { plain.serialize(it).contains("примерно") } shouldBe true
        val preset = factory.create(configuration.template(MenuTemplateId.of("contracts-quantity-preset")), PaperMenuItemRenderContext(
            values = mapOf("label" to Component.text("Максимум"), "quantity" to Component.text(0)),
        ))
        preset.itemMeta.lore()!!.any { plain.serialize(it).contains("ЛКМ") } shouldBe false
    }

    "unknown item tags reject a candidate before it can replace the active catalog" {
        val root = Files.createTempDirectory("arc-menu-invalid")
        val target = root.resolve("guis/menus.yml")
        Files.createDirectories(target.parent)
        val original = requireNotNull(javaClass.classLoader.getResource("guis/menus.yml")).readText()
        Files.writeString(target, original.replace("<action>", "<undeclared>"))

        shouldThrow<IllegalArgumentException> {
            PaperMenuConfigurationParser.require(
                Config(root, "guis/menus.yml"),
                "menus.layouts",
                "menus.templates",
                ArcMenuSchema.contracts,
                textContracts = ArcMenuSchema.textContracts,
            )
        }
    }

    "configured movement changes a semantic button without recompilation" {
        val root = Files.createTempDirectory("arc-menu-move")
        val target = root.resolve("guis/menus.yml")
        Files.createDirectories(target.parent)
        val original = requireNotNull(javaClass.classLoader.getResource("guis/menus.yml")).readText()
        Files.writeString(target, original.replace("start: { slot: 13", "start: { slot: 15"))

        val moved = ArcMenuConfiguration.load(root)

        moved.catalog.require(MenuId.of("investigation-hub"))
            .slot(MenuElementId.of("start")).index shouldBe 15
    }
})
