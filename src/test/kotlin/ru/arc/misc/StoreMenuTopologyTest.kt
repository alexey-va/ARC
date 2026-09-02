package ru.arc.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.gui.ArcMenuSchema

class StoreMenuTopologyTest : StringSpec({
    "store capacity selects the smallest configured screen that can show it" {
        storeMenuForSize(-1) shouldBe ArcMenuSchema.STORE.getValue(2)
        storeMenuForSize(0) shouldBe ArcMenuSchema.STORE.getValue(2)
        storeMenuForSize(1) shouldBe ArcMenuSchema.STORE.getValue(2)
        storeMenuForSize(9) shouldBe ArcMenuSchema.STORE.getValue(2)
        storeMenuForSize(10) shouldBe ArcMenuSchema.STORE.getValue(3)
        storeMenuForSize(36) shouldBe ArcMenuSchema.STORE.getValue(5)
        storeMenuForSize(37) shouldBe ArcMenuSchema.STORE.getValue(6)
        storeMenuForSize(10_000) shouldBe ArcMenuSchema.STORE.getValue(6)
    }
})
