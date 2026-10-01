package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.customtreasurechests.CustomTreasureChestConfigFields
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

class EMChestCooldownsTest : FreeSpec({
    "native timer snapshots work without the removed getter and follow native replacements" {
        val fields = mockk<CustomTreasureChestConfigFields> {
            every { getRestockTimers() } throws NoSuchMethodError("EliteMobs 10.9.7 removed this getter")
        }
        val nativeTimers = CustomTreasureChestConfigFields::class.java
            .getDeclaredField("restockTimers").apply { isAccessible = true }
        val player = UUID.randomUUID()
        val timers = arrayListOf("$player:1030", "invalid")
        nativeTimers.set(fields, timers)

        val snapshot = EMChestCooldowns.timers(fields)
        snapshot shouldBe timers
        timers shouldBe listOf("$player:1030", "invalid")

        nativeTimers.set(fields, arrayListOf("$player:1060"))
        EMChestCooldowns.timers(fields) shouldBe listOf("$player:1060")
        snapshot shouldBe listOf("$player:1030", "invalid")
    }

    "an uninitialised native timer list remains absent" {
        val fields = mockk<CustomTreasureChestConfigFields>()
        val nativeTimers = CustomTreasureChestConfigFields::class.java
            .getDeclaredField("restockTimers").apply { isAccessible = true }
        nativeTimers.set(fields, null)

        EMChestCooldowns.timers(fields) shouldBe null
    }
})
