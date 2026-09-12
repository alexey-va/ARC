package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ItemsCatalogModuleResilienceTest : StringSpec({
    "an invalid optional reward catalogue cannot disable the main items catalogue" {
        var reported: Throwable? = null

        val fallback = rewardCatalogOrDefault(
            load = { error("unsupported optional presentation field") },
            onFailure = { reported = it },
        )

        fallback.enabled shouldBe false
        fallback.categories shouldBe emptyList()
        reported?.message shouldBe "unsupported optional presentation field"
    }
})
