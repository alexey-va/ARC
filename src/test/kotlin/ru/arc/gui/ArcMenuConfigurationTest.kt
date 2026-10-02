package ru.arc.gui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.menu.MenuTemplateId
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.config.Config
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
        configuration.catalog.require(ArcMenuSchema.LOST_LOOT).apply {
            rows shouldBe 6
            region(ArcMenuSchema.LOST_LOOT_ITEMS).size shouldBe 45
            slot("info").index shouldBe 49
        }
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

    "bundled mount schemas keep content and controls on their production rows" {
        val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)

        configuration.catalog.require(ArcMenuSchema.MOUNT_LIST).apply {
            rows shouldBe 6
            backgroundTemplate shouldBe null
            region(ArcMenuSchema.MOUNT_ENTRIES).map { it.index } shouldContainExactly (0..44).toList()
            slot("previous").index shouldBe 45
            slot("filter").index shouldBe 49
            slot("next").index shouldBe 53
            elements.keys shouldContainExactly setOf("previous", "filter", "next").map(MenuElementId::of).toSet()
        }
        configuration.catalog.require(ArcMenuSchema.MOUNT_PROGRESSION).apply {
            rows shouldBe 3
            backgroundTemplate shouldBe null
            slot("info").index shouldBe 4
            slot("back").index shouldBe 18
            elements.keys shouldContainExactly setOf("info", "back").map(MenuElementId::of).toSet()
            region(ArcMenuSchema.MOUNT_LEVELS).map { it.index } shouldContainExactly listOf(11, 13, 15)
        }
        configuration.catalog.require(ArcMenuSchema.MOUNT_TUNING).apply {
            rows shouldBe 6
            backgroundTemplate shouldBe null
            slot("info").index shouldBe 4
            slot("skins").index shouldBe 2
            slot("glow").index shouldBe 6
            region(ArcMenuSchema.MOUNT_SPEEDS).map { it.index } shouldContainExactly (11..15).toList()
            region(ArcMenuSchema.MOUNT_STEPS).map { it.index } shouldContainExactly (20..24).toList()
            region(ArcMenuSchema.MOUNT_SIZES).map { it.index } shouldContainExactly (29..33).toList()
            region(ArcMenuSchema.MOUNT_ABILITIES).map { it.index } shouldContainExactly (38..42).toList()
            slot("back").index shouldBe 45
            slot("rider-view").index shouldBe 49
        }
        configuration.catalog.require(ArcMenuSchema.MOUNT_SKINS).apply {
            rows shouldBe 6
            backgroundTemplate shouldBe null
            region(ArcMenuSchema.MOUNT_SKIN_ENTRIES).map { it.index } shouldContainExactly (0..44).toList()
            slot("previous").index shouldBe 45
            slot("back").index shouldBe 49
            slot("next").index shouldBe 53
        }
        configuration.catalog.require(ArcMenuSchema.MOUNT_DETAIL).apply {
            backgroundTemplate shouldBe null
            slot("upgrade").index shouldBe 20
            slot("settings").index shouldBe 24
        }
        configuration.catalog.require(ArcMenuSchema.MOUNT_CONFIRM).backgroundTemplate shouldBe null
    }

    "legacy mount menus migrate glow into settings and preserve operator choices on repeated loads" {
        val root = Files.createTempDirectory("arc-menu-mount-settings-migration")
        val target = root.resolve(ArcMenuConfiguration.RESOURCE)
        Files.createDirectories(target.parent)
        javaClass.classLoader.getResourceAsStream(ArcMenuConfiguration.RESOURCE).use { source ->
            Files.copy(checkNotNull(source), target)
        }

        val config = Config(root, ArcMenuConfiguration.RESOURCE)
        config.removeKey("menus.layouts.mount-detail.elements.settings")
        config.setInt("menus.layouts.mount-detail.elements.glow.slot", 31)
        config.setString("menus.layouts.mount-detail.elements.glow.template", "lost-loot-back")
        config.setInt("menus.layouts.mount-detail.elements.skins.slot", 40)
        config.setString("menus.layouts.mount-detail.elements.skins.template", "background")
        config.setStringList("menus.layouts.mount-detail.regions.abilities.slots", listOf("29-33"))
        config.setInt("menus.layouts.mount-progression.elements.tuning.slot", 22)
        config.setString("menus.layouts.mount-progression.elements.tuning.template", "background")
        config.setInt("menus.layouts.mount-tuning.rows", 5)
        config.setInt("menus.layouts.mount-tuning.elements.back.slot", 36)
        config.setInt("menus.layouts.mount-tuning.elements.rider-view.slot", 40)
        config.setStringList("menus.layouts.mount-tuning.regions.speeds.slots", listOf("9-13"))
        config.setStringList("menus.layouts.mount-tuning.regions.steps.slots", listOf("18-22"))
        config.setStringList("menus.layouts.mount-tuning.regions.sizes.slots", listOf("27-31"))
        config.setInt("menus.templates.background.custom-model-data", 11999)
        config.setInt("menus.layouts.investigation-hub.elements.start.slot", 15)
        config.removeKey("menus.layouts.mount-list")
        config.saveStrict()

        val migrated = ArcMenuConfiguration.load(root)
        migrated.catalog.require(ArcMenuSchema.MOUNT_DETAIL).slot("settings").index shouldBe 31
        migrated.catalog.require(ArcMenuSchema.MOUNT_PROGRESSION).elements.keys shouldContainExactly
            setOf("info", "back").map(MenuElementId::of).toSet()
        migrated.catalog.require(ArcMenuSchema.MOUNT_TUNING).apply {
            rows shouldBe 6
            slot("back").index shouldBe 45
            slot("rider-view").index shouldBe 49
            region(ArcMenuSchema.MOUNT_SPEEDS).map { it.index } shouldContainExactly (9..13).toList()
            region(ArcMenuSchema.MOUNT_STEPS).map { it.index } shouldContainExactly (18..22).toList()
            region(ArcMenuSchema.MOUNT_SIZES).map { it.index } shouldContainExactly (27..31).toList()
        }

        val saved = Config(root, ArcMenuConfiguration.RESOURCE)
        saved.stringOrNull("menus.layouts.mount-detail.elements.settings.template") shouldBe "lost-loot-back"
        saved.intOrNull("menus.templates.background.custom-model-data") shouldBe 11999
        saved.intOrNull("menus.layouts.investigation-hub.elements.start.slot") shouldBe 15
        saved.intOrNull("menus.layouts.mount-detail.elements.glow.slot") shouldBe null
        saved.intOrNull("menus.layouts.mount-detail.elements.skins.slot") shouldBe null
        saved.stringList("menus.layouts.mount-detail.regions.abilities.slots") shouldBe emptyList()
        saved.intOrNull("menus.layouts.mount-progression.elements.tuning.slot") shouldBe null

        val afterMigration = Files.readString(target)
        ArcMenuConfiguration.load(root).catalog.require(ArcMenuSchema.MOUNT_TUNING).rows shouldBe 6
        Files.readString(target) shouldBe afterMigration
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
