package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class ItemsCatalogPlannerTest : StringSpec({
    "places the complete registry after groups and individual categories" {
        catalogRootOrder(
            groups = listOf("furniture", "sets"),
            categories = listOf("weapons", "vehicles"),
            all = "all",
        ) shouldContainExactly listOf("furniture", "sets", "weapons", "vehicles", "all")
    }

    "keeps 45 entries on the first page and clamps later pages" {
        val entries = (1..46).toList()

        val first = catalogPage(entries, 0, 45)
        val last = catalogPage(entries, 99, 45)

        first.pages shouldBe 2
        first.entries shouldContainExactly (1..45).toList()
        last.page shouldBe 1
        last.entries shouldContainExactly listOf(46)
        last.totalEntries shouldBe 46
    }

    "merges duplicate ItemsAdder categories and expands exact glob and regex entries" {
        val raw =
            listOf(
                category("japan_furniture", "Японская мебель", listOf("decor:chair"), source = "one.yml"),
                category("japan_furniture", "Японская мебель", listOf("decor\\:(.*)_table"), source = "two.yml"),
                category("gui_icons", "Иконки", listOf("icons:*")),
            )
        val plan =
            ItemsCatalogPlanner.plan(
                rawCategories = raw,
                registryIds = setOf("decor:chair", "decor:oak_table", "decor:lamp", "icons:back", "icons:next"),
                groupDefinitions =
                    listOf(
                        group("furniture", 10, listOf("*furniture*")),
                        group("gui-icons", 20, listOf("*_icons")),
                    ),
            ).snapshot

        plan.groups.map { it.definition.id } shouldContainExactly listOf("furniture", "gui-icons")
        plan.groups.first().categories.single().itemIds shouldContainExactly listOf("decor:chair", "decor:oak_table")
        plan.groups.last().categories.single().itemIds shouldContainExactly listOf("icons:back", "icons:next")
        plan.ungroupedCategories shouldBe emptyList()
    }

    "keeps a newly discovered unmatched category at the root" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories =
                    listOf(
                        category("known_furniture", "Мебель", listOf("decor:chair")),
                        category("brand_new_pack", "Новый набор", listOf("new:item")),
                    ),
                registryIds = setOf("decor:chair", "new:item"),
                groupDefinitions = listOf(group("furniture", 10, listOf("*furniture*"))),
            ).snapshot

        snapshot.groups.single().categories.map { it.id } shouldContainExactly listOf("known_furniture")
        snapshot.ungroupedCategories.map { it.id } shouldContainExactly listOf("brand_new_pack")
    }

    "filters disabled hidden and unresolved categories without inventing items" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories =
                    listOf(
                        category("disabled", "Disabled", listOf("pack:a"), enabled = false),
                        category("hidden", "Hidden", listOf("pack:a")),
                        category("missing", "Missing", listOf("pack:missing")),
                        category("visible", "Visible", listOf("pack:a")),
                    ),
                registryIds = setOf("pack:a"),
                groupDefinitions = emptyList(),
                hiddenCategoryIds = setOf("hidden"),
            ).snapshot

        snapshot.ungroupedCategories.map { it.id } shouldContainExactly listOf("visible")
        snapshot.registryItemIds shouldContainExactly listOf("pack:a")
    }

    "assigns an overlapping category to the first ordered group and records a diagnostic" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories = listOf(category("japan_furniture", "Япония", listOf("pack:a"))),
                registryIds = setOf("pack:a"),
                groupDefinitions =
                    listOf(
                        group("late", 20, listOf("*furniture*")),
                        group("first", 10, listOf("japan_*")),
                    ),
            ).snapshot

        snapshot.groups.single().definition.id shouldBe "first"
        snapshot.issues.map { it.code } shouldContainExactlyInAnyOrder listOf("category_multiple_groups")
    }

    "retains every permission when duplicate category definitions disagree" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories =
                    listOf(
                        category("shared", "Shared", listOf("pack:a"), permission = "pack.first"),
                        category("shared", "Shared", listOf("pack:b"), permission = "pack.second"),
                    ),
                registryIds = setOf("pack:a", "pack:b"),
                groupDefinitions = emptyList(),
            ).snapshot

        snapshot.ungroupedCategories.single().permissions shouldBe setOf("pack.first", "pack.second")
        snapshot.issues.map { it.code } shouldContainExactly listOf("category_permission_conflict")
    }

    "hides a category with an invalid permission instead of exposing it" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories = listOf(category("unsafe", "Unsafe", listOf("pack:a"), permission = "bad permission")),
                registryIds = setOf("pack:a"),
                groupDefinitions = emptyList(),
            ).snapshot

        snapshot.ungroupedCategories shouldBe emptyList()
        snapshot.issues.map { it.code } shouldContainExactly listOf("invalid_category_permission")
    }

    "removes configured internal items from both categories and the complete registry" {
        val snapshot =
            ItemsCatalogPlanner.plan(
                rawCategories = listOf(category("icons", "Icons", listOf("_iainternal:*", "pack:item"))),
                registryIds = setOf("_iainternal:back", "_iainternal:next", "pack:item"),
                groupDefinitions = emptyList(),
                hiddenItemPatterns = listOf("_iainternal:*"),
            ).snapshot

        snapshot.registryItemIds shouldContainExactly listOf("pack:item")
        snapshot.ungroupedCategories.single().itemIds shouldContainExactly listOf("pack:item")
    }

    "rejects regex constructs with unbounded backtracking risk" {
        val issues = mutableListOf<CatalogBuildIssue>()

        ItemsCatalogPlanner.matchesPattern("pack:(a+)+", "pack:aaaa", issues) shouldBe false

        issues.map { it.code } shouldContainExactly listOf("unsafe_pattern")
    }

    "normalizes third-party legacy formatting and localization keys to literal bounded names" {
        ItemsCatalogText.categoryName("&bЯпонская &fмебель", "ignored") shouldBe "Японская мебель"
        ItemsCatalogText.categoryName("display-category-armors", "arc_armor") shouldBe "Arc armor"
        ItemsCatalogText.categoryName("&kСекрет&f\u0000", "ignored") shouldBe "Секрет"
    }
}) {
    companion object {
        private fun category(
            id: String,
            name: String,
            patterns: List<String>,
            enabled: Boolean = true,
            source: String = "category.yml",
            permission: String = "ia.menu.seecategory.$id",
        ) = RawItemsAdderCategory(
            id = id,
            enabled = enabled,
            name = name,
            iconId = patterns.firstOrNull { '*' !in it && '\\' !in it },
            permission = permission,
            itemPatterns = patterns,
            source = source,
        )

        private fun group(
            id: String,
            order: Int,
            patterns: List<String>,
        ) = CatalogGroupDefinition(
            id = id,
            order = order,
            displayName = id,
            description = emptyList(),
            categoryPatterns = patterns,
            icon = CatalogIconStyle("CHEST"),
        )
    }
}
