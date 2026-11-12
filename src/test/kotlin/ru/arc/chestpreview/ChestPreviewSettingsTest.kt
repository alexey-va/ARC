package ru.arc.chestpreview

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ChestPreviewSettingsTest : StringSpec({
    "defaults show the full dense nine-column grid without a backdrop" {
        ChestPreviewSettings() shouldBe ChestPreviewSettings(
            maxItems = 54,
            columns = 9,
            maxDistance = 4.5,
            verticalGap = 0.15,
            backgroundOpacity = 0,
        )
    }

    "group count and range stay bounded" {
        for (value in listOf(0, 55)) {
            shouldThrow<IllegalArgumentException> { ChestPreviewSettings(maxItems = value) }
        }
        for (value in listOf(0, 10)) {
            shouldThrow<IllegalArgumentException> { ChestPreviewSettings(columns = value) }
        }
        for (value in listOf(0.99, 4.5001, Double.NaN, Double.POSITIVE_INFINITY)) {
            shouldThrow<IllegalArgumentException> { ChestPreviewSettings(maxDistance = value) }
        }
        for (value in listOf(-0.01, 0.51, Double.NaN)) {
            shouldThrow<IllegalArgumentException> { ChestPreviewSettings(verticalGap = value) }
        }
    }

    "background opacity includes fully transparent and opaque bounds" {
        ChestPreviewSettings(backgroundOpacity = 0).backgroundOpacity shouldBe 0
        ChestPreviewSettings(backgroundOpacity = 100).backgroundOpacity shouldBe 100
        shouldThrow<IllegalArgumentException> { ChestPreviewSettings(backgroundOpacity = -1) }
        shouldThrow<IllegalArgumentException> { ChestPreviewSettings(backgroundOpacity = 101) }
    }
})
