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
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.config.Config
import java.nio.file.Files

class ArcMenuConfigurationTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "bundled menu catalog validates every declared semantic screen" {
        val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)

        configuration.catalog.layouts.keys shouldContainExactly ArcMenuSchema.contracts.keys
        configuration.catalog.layouts.keys.any { it.value.startsWith("contracts-") } shouldBe false
        configuration.catalog.require(ArcMenuSchema.INVESTIGATION_HUB).slot("start").index shouldBe 13
        configuration.catalog.require(ArcMenuSchema.INVESTIGATION_CASE).region("witnesses")
            .map { it.index } shouldContainExactly listOf(18, 20, 22, 24, 26)
        configuration.templates.keys.containsAll(setOf("background", "menu-previous", "menu-next")) shouldBe true
        configuration.catalog.layouts.containsKey(MenuId.of("mount-list")) shouldBe false
        configuration.catalog.layouts.containsKey(MenuId.of("elite-loot")) shouldBe false
        configuration.catalog.layouts.containsKey(MenuId.of("lost-elite-loot")) shouldBe false
        configuration.catalog.layouts.containsKey(MenuId.of("elite-mobs-shop")) shouldBe false
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

    "filters child dungeon layouts while preserving host templates and the shared legacy config" {
        val root = Files.createTempDirectory("arc-menu-foreign-dungeon-config")
        val target = root.resolve(ArcMenuConfiguration.RESOURCE)
        Files.createDirectories(target.parent)
        javaClass.classLoader.getResourceAsStream(ArcMenuConfiguration.RESOURCE).use { source ->
            Files.copy(checkNotNull(source), target)
        }

        val legacy = Config(root, ArcMenuConfiguration.RESOURCE)
        legacy.setInt("menus.layouts.investigation-hub.elements.start.slot", 15)
        legacy.setString("menus.layouts.elite-loot.schema-version", "future")
        legacy.setString("menus.layouts.elite-loot.elements.back.template", "lost-loot-back")
        legacy.setString("menus.layouts.elite-loot-confirmation.schema-version", "future")
        legacy.setString("menus.layouts.elite-loot-confirmation.rows", "future")
        legacy.setString("menus.templates.em-shop-item.material", "NOT_A_REAL_MATERIAL")
        legacy.setString("menus.templates.lost-loot-back.material", "NOT_A_REAL_MATERIAL")
        legacy.setString("menus.templates.operator-custom.material", "STONE")
        legacy.saveStrict()
        val beforeLoad = Files.readString(target)

        val loaded = ArcMenuConfiguration.load(root)

        loaded.catalog.layouts.keys shouldContainExactly ArcMenuSchema.contracts.keys
        loaded.catalog.require(ArcMenuSchema.INVESTIGATION_HUB).slot("start").index shouldBe 15
        loaded.catalog.layouts.containsKey(MenuId.of("elite-loot")) shouldBe false
        loaded.catalog.layouts.containsKey(MenuId.of("elite-loot-confirmation")) shouldBe false
        loaded.templates.containsKey("em-shop-item") shouldBe false
        loaded.templates.containsKey("lost-loot-back") shouldBe false
        loaded.templates.keys.containsAll(setOf(
            "contract-desk-order", "contract-desk-empty", "board-contract-empty", "operator-custom",
        )) shouldBe true
        Files.readString(target) shouldBe beforeLoad

        val persisted = Config(root, ArcMenuConfiguration.RESOURCE)
        persisted.exists("menus.layouts.elite-loot-confirmation") shouldBe true
        persisted.stringOrNull("menus.layouts.elite-loot.schema-version") shouldBe "future"
        persisted.stringOrNull("menus.templates.lost-loot-back.material") shouldBe "NOT_A_REAL_MATERIAL"
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

    "unknown item tags reject a candidate before it can replace the active catalog" {
        val root = Files.createTempDirectory("arc-menu-invalid")
        val target = root.resolve("guis/menus.yml")
        Files.createDirectories(target.parent)
        val original = requireNotNull(javaClass.classLoader.getResource("guis/menus.yml")).readText()
        Files.writeString(target, original.replace("<action>", "<undeclared>"))

        shouldThrow<IllegalArgumentException> {
            ArcMenuConfiguration.load(root)
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
