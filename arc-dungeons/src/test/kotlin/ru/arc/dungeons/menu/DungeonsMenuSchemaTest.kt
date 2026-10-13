package ru.arc.dungeons.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class DungeonsMenuSchemaTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "bundled child defaults match the three owned contracts and eleven templates" {
        val root = Files.createTempDirectory("arc-dungeons-bundled-menus")
        try {
            val target = root.resolve(DungeonsMenuSchema.RESOURCE)
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream(DungeonsMenuSchema.RESOURCE).use { source ->
                Files.copy(checkNotNull(source), target)
            }

            val loaded = DungeonsMenuSchema.load(root)

            loaded.catalog.layouts.keys.map { it.value } shouldContainExactlyInAnyOrder listOf(
                "elite-loot",
                "lost-elite-loot",
                "elite-mobs-shop",
            )
            loaded.catalog.require(DungeonsMenuSchema.ELITE_LOOT).apply {
                rows shouldBe 6
                backgroundTemplate shouldBe MenuTemplateId.of("background")
                slot("previous").index shouldBe 45
                slot("back").index shouldBe 49
                slot("next").index shouldBe 53
                region("items").size shouldBe 45
            }
            loaded.catalog.require(DungeonsMenuSchema.LOST_LOOT).apply {
                slot("back").index shouldBe 45
                slot("info").index shouldBe 49
                region("lost-items").size shouldBe 45
            }
            loaded.catalog.require(DungeonsMenuSchema.EM_SHOP).apply {
                slot("change").index shouldBe 4
                slot("update").index shouldBe 8
                region("shop-items").size shouldBe 45
            }
            loaded.templates.keys shouldContainExactlyInAnyOrder setOf(
                "background",
                "elite-loot-previous",
                "elite-loot-back",
                "elite-loot-next",
                "lost-loot-back",
                "lost-loot-info",
                "menu-previous",
                "menu-next",
                "em-shop-change",
                "em-shop-update",
                "em-shop-item",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "loads only ArcDungeons contracts while preserving legacy operator edits on disk" {
        val root = Files.createTempDirectory("arc-dungeons-menus")
        try {
            val file = root.resolve(DungeonsMenuSchema.RESOURCE)
            Files.createDirectories(file.parent)
            val yaml = legacyMenus()
            Files.writeString(file, yaml)

            val loaded = DungeonsMenuSchema.load(root)

            loaded.catalog.layouts.keys.map { it.value } shouldContainExactlyInAnyOrder listOf(
                "elite-loot",
                "lost-elite-loot",
                "elite-mobs-shop",
            )
            loaded.catalog.require(DungeonsMenuSchema.ELITE_LOOT).slot("previous").index shouldBe 46
            loaded.catalog.require(DungeonsMenuSchema.ELITE_LOOT).region("items").size shouldBe 45
            loaded.template(MenuTemplateId.of("elite-loot-previous")).source shouldBe
                ru.arc.paper.menu.PaperMenuItemSource.MaterialItem(Material.BLUE_STAINED_GLASS_PANE)
            loaded.templates.keys shouldContainExactlyInAnyOrder setOf(
                "background",
                "elite-loot-previous",
                "elite-loot-back",
                "elite-loot-next",
                "lost-loot-back",
                "lost-loot-info",
                "menu-previous",
                "menu-next",
                "em-shop-change",
                "em-shop-update",
                "em-shop-item",
            )

            Files.readString(file) shouldBe yaml
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun legacyMenus(): String =
    """
    menus:
      layouts:
        elite-loot:
          schema-version: 1
          rows: 6
          background: { template: background }
          elements:
            previous: { slot: 46, template: elite-loot-previous }
            back: { slot: 49, template: elite-loot-back }
            next: { slot: 53, template: elite-loot-next }
          regions:
            items: { slots: ['0-44'] }
          pagination: { region: items, previous: previous, next: next }
        lost-elite-loot:
          schema-version: 1
          rows: 6
          elements:
            back: { slot: 45, template: lost-loot-back }
            previous: { slot: 48, template: menu-previous }
            info: { slot: 49, template: lost-loot-info, kind: decoration }
            next: { slot: 50, template: menu-next }
          regions:
            lost-items: { slots: ['0-44'] }
          pagination: { region: lost-items, previous: previous, next: next }
        elite-mobs-shop:
          schema-version: 1
          rows: 6
          elements:
            change: { slot: 4, template: em-shop-change }
            update: { slot: 8, template: em-shop-update, kind: decoration }
          regions:
            shop-items: { slots: ['9-53'] }
        unrelated-layout:
          schema-version: invalid
          rows: 99
      templates:
        background: { material: BLACK_STAINED_GLASS_PANE }
        elite-loot-previous: { material: BLUE_STAINED_GLASS_PANE }
        elite-loot-back: { material: BLUE_STAINED_GLASS_PANE }
        elite-loot-next: { material: BLUE_STAINED_GLASS_PANE }
        lost-loot-back: { material: ARROW }
        lost-loot-info: { material: ENDER_CHEST }
        menu-previous: { material: BLUE_STAINED_GLASS_PANE }
        menu-next: { material: BLUE_STAINED_GLASS_PANE }
        em-shop-change:
          material: GREEN_STAINED_GLASS_PANE
          name: '<gold><type>'
        em-shop-update:
          material: PAPER
          name: '<gold><player>'
          lore: ['<gray><minutes> <balance>']
        em-shop-item:
          material: PAPER
          name: '<name>'
          lore:
            - '<gold><price>'
            - { repeat: original, text: '<gray><line>' }
        unrelated-template: { material: NOT_A_REAL_MATERIAL }
    """.trimIndent() + "\n"
