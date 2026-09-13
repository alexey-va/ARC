package ru.arc.treasure.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class TreasureRollRangeTest : DescribeSpec({
    describe("TreasureRollRange") {
        it("keeps legacy treasure items at one roll") {
            TreasureRollRange.resolve(null, null) shouldBe TreasureRollRange(1, 1)
        }

        it("uses the inclusive configured range") {
            val range = TreasureRollRange.resolve(4, 5)

            range.roll { origin, bound ->
                origin shouldBe 4
                bound shouldBe 6
                5
            } shouldBe 5
        }

        it("bounds malformed metadata") {
            TreasureRollRange.resolve(-10, 1000) shouldBe TreasureRollRange(1, 64)
            TreasureRollRange.resolve(7, 3) shouldBe TreasureRollRange(7, 7)
        }
    }
})
