package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopStationStockGeometryTest : FreeSpec({
    "all local stocks are supported and reserve the live output without entering the front lane" {
        for (role in OriginWorkshopTableRole.entries) {
            val stock = originWorkshopStationStockGeometry(role)
            stock.blocks.map { it.key }.distinct().size shouldBe stock.blocks.size
            stock.blocks.all { piece ->
                piece.y - piece.height / 2.0 >= -1e-9 &&
                    abs(piece.x) + piece.width / 2.0 <= 1.45 + 1e-9 &&
                    abs(piece.z) + piece.depth / 2.0 <= 0.95 + 1e-9
            } shouldBe true
            val shelf = stock.blocks.single { it.key == "upper-shelf" }
            val upper = stock.items.filter { it.y > 1.0 }
            upper.size shouldBe 2
            upper.all { abs(it.y - (shelf.y + shelf.height / 2.0)) < 1e-9 && it.scale == 1.0 } shouldBe true
            val liveSlotTop = 0.18
            stock.blocks.filter { it.y - it.height / 2.0 > liveSlotTop + 1e-9 && it.y < 1.7 }.all { piece ->
                abs(piece.x) - piece.width / 2.0 > 0.57 || abs(piece.z) - piece.depth / 2.0 > 0.57
            } shouldBe true
            stock.items.count { it.y < 1.0 } shouldBe if (role == OriginWorkshopTableRole.FINISHER) 1 else 0
        }
    }
})
