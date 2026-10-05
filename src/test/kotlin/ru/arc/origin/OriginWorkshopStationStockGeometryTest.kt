package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopStationStockGeometryTest : FreeSpec({
    "all stock fits the carpenter and upholsterer awning gap without intersecting the output" {
        for (role in OriginWorkshopTableRole.entries) {
            val stock = originWorkshopStationStockGeometry(role)
            stock.blocks.map { it.key }.distinct().size shouldBe stock.blocks.size
            (stock.blocks.size <= 29) shouldBe true
            stock.blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
            (stock.blocks.maxOf { it.y + it.height / 2.0 } <= 0.62 + 1e-9) shouldBe true
            stock.blocks.all { piece ->
                piece.x - piece.width / 2.0 >= -1.0 - 1e-9 &&
                    piece.x + piece.width / 2.0 <= 1.0 + 1e-9 &&
                    piece.z - piece.depth / 2.0 >= -1.325 - 1e-9 &&
                    piece.z + piece.depth / 2.0 <= 0.95 + 1e-9 &&
                    (piece.key.startsWith("pallet-") || piece.y - piece.height / 2.0 >= 0.18 - 1e-9)
            } shouldBe true
            stock.blocks.none { it.key.startsWith("rack-") || it.key.contains("shelf") } shouldBe true

            stock.items.size shouldBe 1
            val sample = stock.items.single()
            sample.x shouldBe 0.58
            sample.y shouldBe 0.18
            sample.z shouldBe -0.93
            sample.scale shouldBe 0.65

            val sampleBounds = modelBounds(sample.itemId)
            val outputBounds = modelBounds(sample.itemId)
            val sampleMinWorldX = sample.z + sampleBounds.minZ * sample.scale
            val sampleMaxWorldX = sample.z + sampleBounds.maxZ * sample.scale
            val sampleMinWorldZ = -sample.x - sampleBounds.maxX * sample.scale
            val sampleMaxWorldZ = -sample.x - sampleBounds.minX * sample.scale
            val outputMinWorldX = outputBounds.minZ
            val outputMaxWorldX = outputBounds.maxZ
            val outputMinWorldZ = -outputBounds.maxX
            val outputMaxWorldZ = -outputBounds.minX
            val separateFromOutput = sampleMaxWorldX <= outputMinWorldX ||
                outputMaxWorldX <= sampleMinWorldX ||
                sampleMaxWorldZ <= outputMinWorldZ ||
                outputMaxWorldZ <= sampleMinWorldZ
            separateFromOutput shouldBe true
            (sampleMinWorldX >= -1.325 - 1e-9 && sampleMaxWorldX <= 0.95 + 1e-9) shouldBe true
            (sampleMinWorldZ >= -0.95 - 1e-9 && sampleMaxWorldZ <= 0.95 + 1e-9) shouldBe true
            val modelAnchor = if (sample.itemId.endsWith("white_wooden_diningtable")) 0.51875 else 0.5
            abs(sample.y + (sampleBounds.minY + modelAnchor) * sample.scale - 0.18) shouldBe 0.0
            (sample.y + (sampleBounds.maxY + modelAnchor) * sample.scale <= 1.5) shouldBe true

            for (stockCenterZ in listOf(-67.0, -64.0)) {
                stock.blocks.all { piece ->
                    val minZ = stockCenterZ - piece.x - piece.width / 2.0
                    val maxZ = stockCenterZ - piece.x + piece.width / 2.0
                    minZ >= -68.0 - 1e-9 && maxZ <= -63.0 + 1e-9
                } shouldBe true
                stock.items.all { item ->
                    val bounds = modelBounds(item.itemId)
                    val minZ = stockCenterZ - item.x - bounds.maxX * item.scale
                    val maxZ = stockCenterZ - item.x - bounds.minX * item.scale
                    minZ >= -68.0 - 1e-9 && maxZ <= -63.0 + 1e-9
                } shouldBe true
                val outputMinZ = stockCenterZ - outputBounds.maxX
                val outputMaxZ = stockCenterZ - outputBounds.minX
                (outputMinZ >= -68.0 - 1e-9 && outputMaxZ <= -63.0 + 1e-9) shouldBe true
            }
        }
    }
})

private data class StationStockModelBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
)

private fun modelBounds(itemId: String) = when {
    itemId.endsWith("white_wooden_chair") -> StationStockModelBounds(-0.39375, 0.4, -0.5, 1.05625, -0.4375, 0.35)
    itemId.endsWith("white_wooden_diningtable") -> StationStockModelBounds(-0.5, 0.5, -0.51875, 0.5, -0.5625, 0.5625)
    else -> StationStockModelBounds(-0.5625, 0.5625, -0.5, 0.5, -0.5, 0.5)
}
