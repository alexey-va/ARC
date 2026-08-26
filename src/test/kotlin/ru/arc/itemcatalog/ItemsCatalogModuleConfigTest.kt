package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ItemsCatalogModuleConfigTest : StringSpec({
    "bundled config is resource-pack-neutral and declares the curated hierarchy" {
        val root = Files.createTempDirectory("arc-items-catalog-config")
        try {
            ConfigManager.clear()
            val settings = ItemsCatalogModuleConfig.load(root).snapshot()

            settings.enabled shouldBe false
            settings.groups.map { it.id } shouldContainExactly
                listOf("furniture", "sets", "food", "alchemy", "gui-icons", "technical")
            settings.groups.first().categoryPatterns.toSet().contains("*furniture*") shouldBe true
            settings.groups.first().itemAction shouldBe
                CatalogClickAction.PlayerCommand("warp furniture", "перейти к мебели")
            settings.groups.single { it.id == "sets" }.categoryPatterns.toSet().contains("*set*") shouldBe true
            settings.groups.single { it.id == "food" }.categoryPatterns.toSet().contains("ff_*") shouldBe true
            settings.groups.single { it.id == "alchemy" }.categoryPatterns.toSet().contains("al_*") shouldBe true
            settings.groups.single { it.id == "gui-icons" }.categoryPatterns.toSet().contains("arc_icons") shouldBe true
            settings.groups.last().categoryPatterns.toSet().contains("generic_items") shouldBe true
            settings.hiddenCategoryIds shouldContainExactly setOf("arc_shields")
            settings.givePermission shouldBe "arc.items.catalog.give"
            settings.recipeClicksEnabled shouldBe true
            settings.allPermission shouldBe "arc.items.catalog.all"
            settings.allIcon.customModelData shouldBe 0
            settings.categoryFallbackIcon.customModelData shouldBe 0
            val descriptor = checkNotNull(ItemsCatalogModuleConfigTest::class.java.getResource("/plugin.yml")).readText()
            descriptor.contains("arc.items.catalog.give:\n    description: Give yourself one item by clicking it in the ARC catalog\n    default: op") shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "rejects an unsafe custom-model-data value" {
        val root = Files.createTempDirectory("arc-items-catalog-invalid-config")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                modules.resolve("items-catalog.yml"),
                """
                enabled: true
                all-items:
                  icon:
                    material: CHEST
                    customModelData: -1
                groups: {}
                """.trimIndent(),
            )
            ConfigManager.clear()

            runCatching { ItemsCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "reads per-category hidden flags and keeps the legacy list compatible" {
        val root = Files.createTempDirectory("arc-items-catalog-category-flags")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                modules.resolve("items-catalog.yml"),
                """
                categories:
                  arc_shields:
                    hidden: true
                  arc_bows:
                    hidden: false
                    item-action:
                      type: player-command
                      command: "warp bows"
                      hint: "перейти к лукам"
                hidden-categories:
                  - legacy_category
                groups: {}
                """.trimIndent(),
            )
            ConfigManager.clear()

            val settings = ItemsCatalogModuleConfig.load(root).snapshot()
            settings.hiddenCategoryIds shouldBe setOf("arc_shields", "legacy_category")
            settings.categoryOverrides.getValue("arc_bows").itemAction shouldBe
                CatalogClickAction.PlayerCommand("warp bows", "перейти к лукам")
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "rejects player commands outside the fixed safe grammar" {
        val root = Files.createTempDirectory("arc-items-catalog-unsafe-action")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                modules.resolve("items-catalog.yml"),
                """
                groups:
                  unsafe:
                    categories: [items]
                    item-action:
                      type: player-command
                      command: "warp furniture; op someone"
                """.trimIndent(),
            )
            ConfigManager.clear()

            runCatching { ItemsCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "curated rules include every current grouped category family" {
        val root = Files.createTempDirectory("arc-items-catalog-current-groups")
        try {
            ConfigManager.clear()
            val settings = ItemsCatalogModuleConfig.load(root).snapshot()
            val furniture = settings.groups.single { it.id == "furniture" }.categoryPatterns
            val sets = settings.groups.single { it.id == "sets" }.categoryPatterns
            val food = settings.groups.single { it.id == "food" }.categoryPatterns
            val alchemy = settings.groups.single { it.id == "alchemy" }.categoryPatterns
            val gui = settings.groups.single { it.id == "gui-icons" }.categoryPatterns
            val technical = settings.groups.single { it.id == "technical" }.categoryPatterns
            val currentFurniture =
                setOf(
                    "fu_casino_decoration_v1",
                    "chinese_furniture_v1",
                    "classroom_furniture_v1",
                    "dungeon_decoration",
                    "egyptian_decoration_v1",
                    "farmer_decoration_v1",
                    "halloween_decoration_v1",
                    "japan_furniture",
                    "fu_medieval_market_decoration_v2",
                    "fu_blacksmith_decoration_v1",
                    "fu_forest_decoration_v1",
                    "fu_fountain_decoration_v1",
                    "park_plus",
                    "restaurant_decoration_v1",
                    "summoning_circle_decoration_v1",
                    "fu_furnituresplus",
                    "fu_gardenplus",
                    "itemshopplus",
                    "royal",
                    "graves",
                    "stones",
                    "branches",
                )
            val currentGui =
                setOf(
                    "arc_icons",
                    "mcicons_catalog",
                    "boxpixstudio_icons",
                    "mccomputericons_numbers",
                    "spectra_shopgui_plus",
                )
            val currentSets =
                setOf(
                    "akiraset",
                    "autumn_festival",
                    "bear_set",
                    "bloody_emperor_animated_weapon_set",
                    "bonekeeper_set",
                    "darkworldpack",
                    "deceaseset",
                    "discordnitroset",
                    "dragonsoulset",
                    "dreadknight",
                    "easterbunny_animated_weapon_set",
                    "ender_eye_set",
                    "fallenset",
                    "fiendskullset",
                    "galaxy_set",
                    "halloween23",
                    "karozset",
                    "littlecatset",
                    "malikaset",
                    "shogunset",
                    "sweetheartset",
                    "thunderboltset",
                    "valerieset",
                    "vinland_animated_weapon",
                    "vladimir_pack",
                    "voxelspawns_lich",
                    "voxelspawns_thorns",
                    "xmasset",
                )
            val currentFood =
                setOf(
                    "ff_baked",
                    "ff_beverages",
                    "ff_cookedfood",
                    "ff_cupscontainers",
                    "ff_deserts",
                    "ff_farming",
                    "ff_fruitvegi",
                    "ff_ingredients",
                    "ff_packaging",
                    "ff_rawfood",
                )
            val currentAlchemy = setOf("al_alchemy_items", "al_potions", "al_splash_potions")
            val currentTechnical =
                setOf(
                    "generic_items",
                    "arc_other",
                    "lztooltips_items",
                    "lemon_vfxdrop",
                    "advancedenchantments_controls",
                    "advancedenchantments_books",
                )

            currentFurniture.all { id -> furniture.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
            currentSets.all { id -> sets.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
            currentFood.all { id -> food.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
            currentAlchemy.all { id -> alchemy.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
            currentGui.all { id -> gui.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
            currentTechnical.all { id -> technical.any { ItemsCatalogPlanner.matchesPattern(it, id) } } shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
