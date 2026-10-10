package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.helpcenter.HelpCenterEnchantment

class AdvancedBookShopTest : StringSpec({
    "shop offers only positive prices for actual configured levels and keeps level gaps" {
        val offer = enchantment(group = "Unique")
        val prices = mapOf(1 to 3_000L, 3 to 0L, 7 to 12_000L)

        advancedBookLevelOffers(offer, listOf(7, 3, 1, 1, 0, -1)) { group, level ->
            group shouldBe "UNIQUE"
            prices[level]
        } shouldBe listOf(
            AdvancedBookLevelOffer(level = 1, priceMinor = 3_000L),
            AdvancedBookLevelOffer(level = 7, priceMinor = 12_000L),
        )
    }

    "shop excludes non-public groups and enchantments unavailable from the enchanter" {
        val fabled = enchantment(group = "FABLED")
        val hidden = enchantment(group = "SOUL")
        val unavailable = enchantment(group = "ELITE", available = false)
        val price: (String, Int) -> Long? = { _, _ -> 100L }

        advancedBookLevelOffers(fabled, listOf(1), price) shouldBe listOf(AdvancedBookLevelOffer(1, 100L))
        advancedBookLevelOffers(hidden, listOf(1), price) shouldBe emptyList()
        advancedBookLevelOffers(unavailable, listOf(1), price) shouldBe emptyList()
    }

    "shop hides configured levels without a valid positive price" {
        advancedBookLevelOffers(enchantment(group = "SIMPLE"), listOf(1, 2, 4)) { _, level ->
            if (level == 2) 0L else null
        } shouldBe emptyList()
    }
})

private fun enchantment(group: String, available: Boolean = true) = HelpCenterEnchantment(
    id = "test_book_enchant",
    name = "Проверка",
    description = "Описание",
    maxLevelDescription = "Описание уровня",
    materials = setOf("DIAMOND_SWORD"),
    group = group,
    maxLevel = 7,
    availableFromEnchanter = available,
)
