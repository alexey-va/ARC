package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopWarehouseGeometryTest : FreeSpec({
    "warehouse storage is low, bounded and uses only the existing display budget" {
        val geometry = originWorkshopWarehouseGeometry()

        geometry.blocks.size shouldBe 33
        (geometry.blocks.size <= 42) shouldBe true
        geometry.blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        (geometry.blocks.maxOf { it.y + it.height / 2.0 } <= 0.62 + 1e-9) shouldBe true
        geometry.blocks.all { it.x - it.width / 2.0 >= -5.0 && it.x + it.width / 2.0 <= 5.0 } shouldBe true
        geometry.blocks.all { it.z - it.depth / 2.0 >= -1.5 && it.z + it.depth / 2.0 <= 1.5 } shouldBe true
        geometry.blocks.filter { it.key.contains("deck") }.all {
            abs(it.y + it.height / 2.0 - 0.18) < 1e-9
        } shouldBe true
        geometry.blocks.none { it.key.startsWith("rack-") || it.key.contains("shelf") } shouldBe true

        geometry.items.size shouldBe 6
        geometry.items.map { it.itemId.substringAfter(':') }.groupingBy { it }.eachCount() shouldBe mapOf(
            "white_wooden_chair" to 2,
            "red_wooden_sofa_single" to 3,
            "white_wooden_diningtable" to 1,
        )
        geometry.items.map { it.x } shouldBe listOf(-3.70, -2.30, -0.78, 0.78, 2.30, 3.70)
        geometry.items.all { item ->
            item.y == 0.18 && item.z == 0.0 && item.scale == 0.84 &&
                listOf(-3.0, 0.0, 3.0).any { center ->
                    val bounds = modelBounds(item.itemId)
                    item.x + bounds.minX * item.scale >= center - 1.6 &&
                        item.x + bounds.maxX * item.scale <= center + 1.6 &&
                        item.z + bounds.minZ * item.scale >= -0.63 &&
                        item.z + bounds.maxZ * item.scale <= 1.03
                }
        } shouldBe true
        geometry.items.all { item ->
            val bounds = modelBounds(item.itemId)
            val anchor = if (item.itemId.endsWith("white_wooden_diningtable")) 0.51875 else 0.5
            val bottom = item.y + (bounds.minY + anchor) * item.scale
            val top = item.y + (bounds.maxY + anchor) * item.scale
            abs(bottom - 0.18) < 1e-9 && top <= 1.5
        } shouldBe true

        for (first in geometry.items.indices) for (second in first + 1 until geometry.items.size) {
            val a = geometry.items[first]
            val b = geometry.items[second]
            val boundsA = modelBounds(a.itemId)
            val boundsB = modelBounds(b.itemId)
            val separated =
                a.x + boundsA.maxX * a.scale <= b.x + boundsB.minX * b.scale ||
                    b.x + boundsB.maxX * b.scale <= a.x + boundsA.minX * a.scale ||
                    a.z + boundsA.maxZ * a.scale <= b.z + boundsB.minZ * b.scale ||
                    b.z + boundsB.maxZ * b.scale <= a.z + boundsA.minZ * a.scale
            separated shouldBe true
        }
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
