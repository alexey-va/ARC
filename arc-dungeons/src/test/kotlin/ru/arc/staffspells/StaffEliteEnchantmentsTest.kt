package ru.arc.staffspells

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StaffEliteEnchantmentsTest : FreeSpec({
    "native multicast keeps the total damage budget across the complete spell volley" {
        listOf(2.0 to .75, 2.0 to .85, 3.0 to .70).forEach { (count, factor) ->
            StaffEliteEnchantments.fromContributions(listOf(
                "on_missile_count" to count, "on_missile_damage_factor" to factor,
            )).damageMultiplier shouldBe count * factor
        }
    }
    "native blast and ignition stack with provider-neutral absent hooks" {
        StaffEliteEnchantments.fromContributions(listOf(
            "on_blast_radius_factor" to 1.3, "on_ignition_ticks" to 60.0,
            "on_ignition_ticks" to null,
        )) shouldBe StaffEliteEnchantments(1.0, 1.3, 60)
        StaffEliteEnchantments.fromContributions(emptyList()) shouldBe StaffEliteEnchantments.NONE
    }
    "malformed provider output cannot become damage or a radius" {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0).forEach { value ->
            shouldThrow<IllegalArgumentException> {
                StaffEliteEnchantments.fromContributions(listOf("on_blast_radius_factor" to value))
            }
        }
        shouldThrow<IllegalArgumentException> {
            StaffEliteEnchantments.fromContributions(listOf("on_missile_count" to 2.0, "on_missile_count" to 3.0))
        }
    }
})
