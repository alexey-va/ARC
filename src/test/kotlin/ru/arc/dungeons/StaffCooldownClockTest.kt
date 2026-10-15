package ru.arc.dungeons

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StaffCooldownClockTest : FreeSpec({
    "configured tick budgets expire exactly regardless of the old five-tick timer phase" {
        for (phase in 0..4) {
            var tick = phase
            val cooldowns = StaffCooldownClock { tick }
            val player = UUID.randomUUID()
            cooldowns.start(player, 19)
            cooldowns.remainingTicks(player) shouldBe 19L
            tick += 18
            cooldowns.remainingTicks(player) shouldBe 1L
            tick++
            cooldowns.remainingTicks(player) shouldBe 0L
            tick += 20
            cooldowns.remainingTicks(player) shouldBe 0L
        }
    }

    "independent players keep exact deadlines after replacement and explicit cleanup" {
        var tick = 0
        val cooldowns = StaffCooldownClock { tick }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        cooldowns.remainingTicks(first) shouldBe 0L
        cooldowns.start(first, 73)
        tick = 7
        cooldowns.start(second, 20)
        cooldowns.remainingTicks(first) shouldBe 66L
        cooldowns.remainingTicks(second) shouldBe 20L
        tick = 20
        cooldowns.start(second, 30)
        cooldowns.remainingTicks(second) shouldBe 30L
        cooldowns.clear(second)
        cooldowns.remainingTicks(second) shouldBe 0L
        cooldowns.remainingTicks(first) shouldBe 53L
        tick = 72
        cooldowns.remainingTicks(first) shouldBe 1L
        tick++
        cooldowns.remainingTicks(first) shouldBe 0L
    }

    "zero and negative starts clear an existing budget" {
        val player = UUID.randomUUID()
        val cooldowns = StaffCooldownClock { 42 }
        for (duration in listOf(0L, -1L)) {
            cooldowns.start(player, 20)
            cooldowns.start(player, duration)
            cooldowns.remainingTicks(player) shouldBe 0L
        }
    }

    "deadlines survive signed Bukkit tick rollover and the full unsigned boundary" {
        for (initial in listOf(Int.MAX_VALUE - 2, -3)) {
            var tick = initial
            val cooldowns = StaffCooldownClock { tick }
            val player = UUID.randomUUID()
            cooldowns.start(player, 8)
            tick += 4
            cooldowns.remainingTicks(player) shouldBe 4L
            tick += 3
            cooldowns.remainingTicks(player) shouldBe 1L
            tick++
            cooldowns.remainingTicks(player) shouldBe 0L
            cooldowns.start(player, 3)
            tick += 3
            cooldowns.remainingTicks(player) shouldBe 0L
        }
    }
})
