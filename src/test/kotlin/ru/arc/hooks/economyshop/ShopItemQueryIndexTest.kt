package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ShopItemQueryIndexTest :
    FreeSpec({
        val items =
            listOf(
                descriptor("Blocks", "pages.page1.items.12", "TNT"),
                descriptor("Blocks", "pages.page1.items.13", "STONE"),
                descriptor("Furniture", "pages.page1.items.1", "PAPER"),
                descriptor("Furniture", "pages.page1.items.2", "PAPER"),
            )

        "prefers unique material names and falls back to unique short ids" {
            ShopItemQueryIndex.preferredQueries(items) shouldContainExactly
                listOf("Blocks.STONE", "Blocks.TNT", "Furniture.1", "Furniture.2")
        }

        "resolves canonical paths, material names, and short ids" {
            ShopItemQueryIndex.resolve("Blocks.pages.page1.items.12", items) shouldBe
                "Blocks.pages.page1.items.12"
            ShopItemQueryIndex.resolve("blocks.tnt", items) shouldBe "Blocks.pages.page1.items.12"
            ShopItemQueryIndex.resolve("Furniture.2", items) shouldBe "Furniture.pages.page1.items.2"
        }

        "rejects ambiguous material aliases" {
            ShopItemQueryIndex.resolve("Furniture.PAPER", items) shouldBe null
        }

        "selects the cheapest finite positive offer with a stable path tie break" {
            ShopMaterialOfferSelector.cheapest(
                listOf(
                    ShopMaterialOffer("Blocks.z", 15.0),
                    ShopMaterialOffer("Blocks.b", 10.0),
                    ShopMaterialOffer("Blocks.a", 10.0),
                    ShopMaterialOffer("Blocks.free", 0.0),
                    ShopMaterialOffer("Blocks.nan", Double.NaN),
                ),
            ) shouldBe ShopMaterialOffer("Blocks.a", 10.0)
        }
    }) {
    companion object {
        private fun descriptor(section: String, location: String, material: String) =
            ShopItemDescriptor(
                canonicalPath = "$section.$location",
                section = section,
                relativeLocation = location,
                material = material,
            )
    }
}
