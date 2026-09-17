package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.util.UUID

class OriginTrainingDummyModuleTest : StringSpec({
    "full charged hits advance the combo to eight" {
        var combo: OriginTrainingCombo? = null
        repeat(8) { index ->
            combo = nextTrainingCombo(combo, now = index * 500L, timeoutMillis = 15_000L, maximum = 8)
        }
        combo?.count shouldBe 8
    }

    "expired combo starts again from one" {
        nextTrainingCombo(
            previous = OriginTrainingCombo(count = 7, lastStrongHitAt = 1_000L),
            now = 16_001L,
            timeoutMillis = 15_000L,
            maximum = 8,
        ).count shouldBe 1
    }

    "weak reset and different players keep independent combos" {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val counter = OriginTrainingComboCounter()

        counter.recordStrong(first, 1_000L, 15_000L, 8).count shouldBe 1
        counter.recordStrong(first, 2_000L, 15_000L, 8).count shouldBe 2
        counter.recordStrong(second, 2_000L, 15_000L, 8).count shouldBe 1
        counter.reset(first)

        counter.recordStrong(first, 3_000L, 15_000L, 8).count shouldBe 1
        counter.recordStrong(second, 3_000L, 15_000L, 8).count shouldBe 2
    }

    "vanilla spears and the existing training weapon families are accepted" {
        val spears = Material.entries.filter { it.name.endsWith("_SPEAR") }
        spears.shouldNotBeEmpty()
        spears.all(::isTrainingWeapon) shouldBe true
        isTrainingWeapon(Material.IRON_SWORD) shouldBe true
        isTrainingWeapon(Material.COPPER_AXE) shouldBe true
        isTrainingWeapon(Material.TRIDENT) shouldBe true
        isTrainingWeapon(Material.MACE) shouldBe true
        isTrainingWeapon(Material.STICK) shouldBe false
    }

    "challenge rewards preserve the configured six and eight hit thresholds" {
        trainingReward(5, target = 8, partialReward = 20, fullReward = 60) shouldBe 0
        trainingReward(6, target = 8, partialReward = 20, fullReward = 60) shouldBe 20
        trainingReward(7, target = 8, partialReward = 20, fullReward = 60) shouldBe 20
        trainingReward(8, target = 8, partialReward = 20, fullReward = 60) shouldBe 60
    }
})
