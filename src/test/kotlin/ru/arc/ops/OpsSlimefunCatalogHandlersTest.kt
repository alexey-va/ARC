package ru.arc.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OpsSlimefunCatalogHandlersTest : FreeSpec({
    "Slimefun catalog paging" - {
        "should order and bound pages while preserving vanilla and Slimefun stack identities" {
            val entries =
                listOf(
                    SlimefunCatalogEntry(
                        id = "z-last",
                        addon = "Addon Z",
                        plugin = "Plugin Z",
                        enabled = false,
                        material = "PAPER",
                        recipeType = null,
                        inputs = emptyList(),
                        output = null,
                        displayRecipes = null,
                    ),
                    SlimefunCatalogEntry(
                        id = "a-first",
                        addon = "Addon A",
                        plugin = "Plugin A",
                        enabled = true,
                        material = "IRON_INGOT",
                        recipeType = "slimefun:enhanced_crafting_table",
                        inputs =
                            listOf(
                                SlimefunCatalogStack(sfId = null, material = "COBBLESTONE", amount = 3),
                                SlimefunCatalogStack(sfId = "IRON_DUST", material = "IRON_INGOT", amount = 2),
                            ),
                        output = SlimefunCatalogStack(sfId = "A_FIRST", material = "IRON_INGOT", amount = 1),
                        displayRecipes = emptyList(),
                    ),
                )

            val page = slimefunCatalogPage(entries, offset = 0, limit = 2)
            page["total"] shouldBe 2
            page["offset"] shouldBe 0
            page["limit"] shouldBe 2
            page["recipeCoverage"] shouldBe
                mapOf(
                    "staticItemRecipes" to true,
                    "displayRecipes" to "RecipeDisplayItem",
                    "dynamicMachineRecipes" to "not-enumerated",
                )

            @Suppress("UNCHECKED_CAST")
            val items = page["items"] as List<Map<String, Any?>>
            items.map { it["id"] } shouldBe listOf("a-first", "z-last")
            @Suppress("UNCHECKED_CAST")
            val inputs = items.first()["inputs"] as List<Map<String, Any?>>
            inputs.map { it["sfId"] } shouldBe listOf(null, "IRON_DUST")
            items[1]["output"] shouldBe null
            items[1]["enabled"] shouldBe false
            val json = slimefunCatalogJson(page)
            json shouldContain "\"ok\":true"
            json shouldContain "\"sfId\":null"
            json shouldContain "\"output\":null"
            json shouldContain "\"displayRecipes\":null"

            @Suppress("UNCHECKED_CAST")
            val nextPage = slimefunCatalogPage(entries, offset = 1, limit = 1)["items"] as List<Map<String, Any?>>
            nextPage.map { it["id"] } shouldBe listOf("z-last")
            shouldThrow<IllegalArgumentException> { slimefunCatalogPage(entries, offset = 0, limit = 101) }
        }
    }
})
