package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ItemInfoRuntimeCadenceTest : StringSpec({
    "chest preview viewers update every tick while others retain the five-tick cadence" {
        (1L..5L).map { shouldUpdateItemInfo(it, chestPreviewEnabled = false) } shouldBe
            listOf(true, false, false, false, true)
        (1L..5L).map { shouldUpdateItemInfo(it, chestPreviewEnabled = true) } shouldBe
            listOf(true, true, true, true, true)
    }
})
