package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain

class ProductInterestConfigTest : StringSpec({
    "treats foll as a QA player by default" {
        ProductInterestConfig().qaPlayerNames shouldContain "foll"
    }
})
