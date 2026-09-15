package ru.arc.hooks.slimefun

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SlimefunBlockIdentityTest : StringSpec({
    "legacy storage identifies non tile addon blocks such as celestial panel" {
        resolveSlimefunBlockId(primary = { null }, legacy = { "CELESTIAL_PANEL" }) shouldBe "CELESTIAL_PANEL"
    }

    "modern block data wins and blank identities are ignored" {
        resolveSlimefunBlockId(primary = { "MODERN_MACHINE" }, legacy = { "LEGACY_MACHINE" }) shouldBe "MODERN_MACHINE"
        resolveSlimefunBlockId(primary = { "  " }, legacy = { "" }) shouldBe null
    }
})
