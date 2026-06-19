package ru.arc.common

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Pure unit tests — no Bukkit / plugin bootstrap. */
class WeightedRandomKotestTest : DescribeSpec({

    describe("WeightedRandom") {
        it("returns null from random() when empty") {
            val wr = WeightedRandom<String>()
            wr.random().shouldBeNull()
        }

        it("returns empty set from getNRandom when n is zero") {
            val wr = WeightedRandom<String>()
            wr.add("a", 1.0)
            wr.getNRandom(0).shouldHaveSize(0)
        }

        it("returns empty set from getNRandom when empty") {
            val wr = WeightedRandom<String>()
            wr.getNRandom(3).shouldHaveSize(0)
        }

        it("remove drops entry and random stays empty") {
            val wr = WeightedRandom<String>()
            wr.add("only", 1.0)
            wr.remove("only") shouldBe true
            wr.size() shouldBe 0
            wr.random().shouldBeNull()
        }

        it("remove returns false when value missing") {
            val wr = WeightedRandom<String>()
            wr.add("x", 1.0)
            wr.remove("y") shouldBe false
            wr.size() shouldBe 1
        }
    }
})

