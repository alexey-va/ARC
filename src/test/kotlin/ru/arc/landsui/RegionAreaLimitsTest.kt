package ru.arc.landsui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RegionAreaLimitsTest : FreeSpec({
    "uses actual claimed chunks with the bounded native formula" {
        RegionAreaLimits.target(1, 0) shouldBe 32
        RegionAreaLimits.target(1, 100) shouldBe 200
        RegionAreaLimits.target(1, 2_000) shouldBe 2_048
        RegionAreaLimits.target(500, 100) shouldBe 500
        RegionAreaLimits.target(-1, 2_000) shouldBe -1
        RegionAreaLimits.target(1, Int.MAX_VALUE) shouldBe 2_048
    }
})
