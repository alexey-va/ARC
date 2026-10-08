package ru.arc.enchanting

import com.magmaguy.elitemobs.items.upgradesystem.EnchantmentProgression
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EliteEnchantmentOutcomeTest : StringSpec({
    val quote = EnchantmentProgression.Quote(14641, .36, .01, .19, .44)

    "former arena outcomes succeed while direct destruction and failure keep their intervals" {
        eliteEnchantmentOutcome(quote, 0.0) shouldBe EliteEnchantmentOutcome.SUCCESS
        eliteEnchantmentOutcome(quote, .359999) shouldBe EliteEnchantmentOutcome.SUCCESS
        eliteEnchantmentOutcome(quote, .36) shouldBe EliteEnchantmentOutcome.DESTROYED
        eliteEnchantmentOutcome(quote, .369999) shouldBe EliteEnchantmentOutcome.DESTROYED
        eliteEnchantmentOutcome(quote, .37) shouldBe EliteEnchantmentOutcome.SUCCESS
        eliteEnchantmentOutcome(quote, .559999) shouldBe EliteEnchantmentOutcome.SUCCESS
        eliteEnchantmentOutcome(quote, .56) shouldBe EliteEnchantmentOutcome.FAILURE
        eliteEnchantmentOutcome(quote, .999999) shouldBe EliteEnchantmentOutcome.FAILURE
    }
    "weight ten quote delivers 55 percent success one percent destruction and 44 percent failure" {
        (0 until 10000).map { eliteEnchantmentOutcome(quote, (it + .5) / 10000) }
            .groupingBy { it }.eachCount() shouldBe mapOf(
                EliteEnchantmentOutcome.SUCCESS to 5500,
                EliteEnchantmentOutcome.DESTROYED to 100,
                EliteEnchantmentOutcome.FAILURE to 4400,
            )
    }
    "guaranteed and zero arena quotes keep their native outcomes" {
        eliteEnchantmentOutcome(EnchantmentProgression.Quote(1, 1.0, 0.0, 0.0, 0.0), .999999) shouldBe EliteEnchantmentOutcome.SUCCESS
        eliteEnchantmentOutcome(EnchantmentProgression.Quote(1, .36, .01, 0.0, .63), .37) shouldBe EliteEnchantmentOutcome.FAILURE
    }
    "invalid random samples cannot silently destroy or consume a purchase" {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.001, 1.0).forEach { roll ->
            shouldThrow<IllegalArgumentException> { eliteEnchantmentOutcome(quote, roll) }
        }
    }
    "displayed percentages preserve native precision without floating point noise" {
        enchantmentPercent(.0001) shouldBe "0.01"
        enchantmentPercent(.5555) shouldBe "55.55"
        enchantmentPercent(.36 + .19) shouldBe "55"
        enchantmentPercent(1.0) shouldBe "100"
    }
})
