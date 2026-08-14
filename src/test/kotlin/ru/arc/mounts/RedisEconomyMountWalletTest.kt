package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RedisEconomyMountWalletTest : StringSpec({
    "provider balance tolerates only sub-cent floating point drift" {
        1_970_147.040334559.toProviderMinorOrNull() shouldBe 197_014_704L
        10.0.toProviderMinorOrNull() shouldBe 1_000L
        10.001.toProviderMinorOrNull() shouldBe null
        Double.NaN.toProviderMinorOrNull() shouldBe null
    }
})
