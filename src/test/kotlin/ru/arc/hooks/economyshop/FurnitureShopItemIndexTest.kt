package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FurnitureShopItemIndexTest : StringSpec({
    "indexes exact namespace IDs without conflating similar furniture names" {
        val index = FurnitureShopItemIndex.from(
            sequenceOf("furniture:oak_chair" to 1, "furniture:oak_chair_small" to 2),
            maxEntries = 2,
        )

        index.complete shouldBe true
        index.entries("FURNITURE:OAK_CHAIR") shouldBe listOf(1)
        index.entries("furniture:oak") shouldBe emptyList()
        index.entries("furniture:oak_chair_small") shouldBe listOf(2)
    }

    "keeps duplicate SKUs ambiguous and fails closed when the snapshot exceeds its bound" {
        val ambiguous = FurnitureShopItemIndex.from(
            sequenceOf("furniture:oak_chair" to "chairs.oak", "furniture:oak_chair" to "decor.oak"),
            maxEntries = 2,
        )
        ambiguous.entries("furniture:oak_chair") shouldBe listOf("chairs.oak", "decor.oak")

        val overflow = FurnitureShopItemIndex.from(
            sequenceOf("furniture:one" to 1, "furniture:two" to 2),
            maxEntries = 1,
        )
        overflow.complete shouldBe false
        overflow.entries("furniture:one") shouldBe emptyList()
    }
})
