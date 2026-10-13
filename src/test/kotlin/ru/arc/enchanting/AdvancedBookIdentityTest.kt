package ru.arc.enchanting

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.NamespacedKey

class AdvancedBookIdentityTest : FreeSpec({
    "legacy AE book marker also belongs to AE normalization" {
        ru.arc.dungeons.api.ArcBookIdentity.hasAdvancedEnchantmentsMarker(setOf(NamespacedKey("advancedenchantments", "book"))) shouldBe true
        ru.arc.dungeons.api.ArcBookIdentity.hasAdvancedEnchantmentsMarker(setOf(NamespacedKey("another", "book"))) shouldBe false
    }

})
