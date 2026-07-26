package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GiveBoostInputTest :
    FreeSpec({
        "positive durations are converted without overflow" {
            parseBoostDuration("1s") shouldBe 1_000L
            parseBoostDuration("2m") shouldBe 120_000L
            parseBoostDuration("3h") shouldBe 10_800_000L
            parseBoostDuration("4d") shouldBe 345_600_000L
        }

        "zero negative malformed and overflowing durations are rejected" {
            parseBoostDuration("0s").shouldBeNull()
            parseBoostDuration("-1h").shouldBeNull()
            parseBoostDuration("1w").shouldBeNull()
            parseBoostDuration("abc").shouldBeNull()
            parseBoostDuration("${Long.MAX_VALUE}d").shouldBeNull()
        }

        "boost multiplier must be positive and finite" {
            isValidBoostMultiplier(0.1).shouldBeTrue()
            isValidBoostMultiplier(0.0).shouldBeFalse()
            isValidBoostMultiplier(-1.0).shouldBeFalse()
            isValidBoostMultiplier(Double.NaN).shouldBeFalse()
            isValidBoostMultiplier(Double.POSITIVE_INFINITY).shouldBeFalse()
        }
    })
