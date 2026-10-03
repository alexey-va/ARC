package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopWarehouseGeometryTest : FreeSpec({
    "warehouse blocks and furniture stay grounded inside the surveyed footprint" {
        val geometry = originWorkshopWarehouseGeometry()

        (geometry.blocks.size in 35..60) shouldBe true
        geometry.blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        (abs(geometry.blocks.maxOf { it.y + it.height / 2.0 } - 3.18) < 1e-9) shouldBe true
        geometry.blocks.all { it.x - it.width / 2.0 >= -5.0 && it.x + it.width / 2.0 <= 5.0 } shouldBe true
        geometry.blocks.all { it.z - it.depth / 2.0 >= -1.5 && it.z + it.depth / 2.0 <= 1.5 } shouldBe true
        geometry.items.size shouldBe 6
        geometry.items.all { item ->
            val bounds = modelBounds(item.itemId)
            item.x + bounds.minX * item.scale >= -5.0 &&
                item.x + bounds.maxX * item.scale <= 5.0 &&
                item.z + bounds.minZ * item.scale >= -1.5 &&
                item.z + bounds.maxZ * item.scale <= 1.5
        } shouldBe true
        geometry.items.all { it.z in -1.0..-0.8 } shouldBe true
        geometry.items.none { item ->
            val bounds = modelBounds(item.itemId)
            listOf(-2.5, 0.0, 3.0).any { stockX ->
                item.x + bounds.minX * item.scale < stockX + 0.5625 &&
                    item.x + bounds.maxX * item.scale > stockX - 0.5625 &&
                    item.z + bounds.minZ * item.scale < 0.9625 &&
                    item.z + bounds.maxZ * item.scale > -0.1625
            }
        } shouldBe true
    }

    "static furniture rests on the two shelf surfaces and preserves headroom" {
        val geometry = originWorkshopWarehouseGeometry()
        val shelves = geometry.blocks.filter { it.key.startsWith("shelf-") && !it.key.startsWith("shelf-support-") && !it.key.startsWith("shelf-lip-") }
        val supportHeights = shelves.map { it.y + it.height / 2.0 }.distinct().sorted()

        supportHeights shouldBe listOf(0.32, 2.04)
        geometry.items.all { it.y in supportHeights } shouldBe true
        val upperShelfBottom = shelves.filter { it.key.startsWith("shelf-upper-") }.minOf { it.y - it.height / 2.0 }
        geometry.items.filter { it.y == supportHeights.first() }.all { item ->
            val bounds = modelBounds(item.itemId)
            item.y + (bounds.maxY + 0.5) * item.scale < upperShelfBottom
        } shouldBe true
        val palletTops = geometry.blocks.filter { it.key.contains("-deck-") }
        palletTops.all { abs(it.y + it.height / 2.0 - 0.18) < 1e-9 } shouldBe true
        palletTops.map { it.x }.distinct() shouldBe listOf(-2.5, 0.0, 3.0)
        geometry.items.all { item ->
            val bounds = modelBounds(item.itemId)
            val anchor = if (item.itemId.endsWith("white_wooden_diningtable")) 0.51875 else 0.5
            val bottom = item.y + (bounds.minY + anchor) * item.scale
            val top = item.y + (bounds.maxY + anchor) * item.scale
            bottom >= item.y - 1e-9 && bottom <= item.y + 0.002 && top <= 3.2
        } shouldBe true
    }
})

private data class ModelBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
)

private fun modelBounds(itemId: String) = when {
    itemId.endsWith("white_wooden_chair") -> ModelBounds(-0.39375, 0.4, -0.5, 1.05625, -0.4375, 0.35)
    itemId.endsWith("white_wooden_diningtable") -> ModelBounds(-0.5, 0.5, -0.51875, 0.5, -0.5625, 0.5625)
    else -> ModelBounds(-0.5625, 0.5625, -0.5, 0.5, -0.5, 0.5)
}
