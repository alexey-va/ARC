package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class DungeonProgressionTest : FreeSpec({
    "completion books preserve actual branch attribution and round down one ten percent budget" {
        val player = UUID.randomUUID()
        val ledger = DungeonRunSkillLedger()
        ledger.record(player, "WANDS", 51)
        ledger.record(player, "STAVES", 50)
        ledger.record(player, "ARMOR", 38)
        ledger.complete(setOf(player)) shouldBe mapOf(player to mapOf("WANDS" to 5L, "STAVES" to 5L, "ARMOR" to 3L))
    }

    "one completed run cannot issue twice or accept late XP" {
        val player = UUID.randomUUID()
        val ledger = DungeonRunSkillLedger()
        ledger.record(player, "SWORDS", 100)
        ledger.complete(setOf(player)).getValue(player).getValue("SWORDS") shouldBe 10
        ledger.record(player, "SWORDS", 1000)
        ledger.complete(setOf(player)) shouldBe emptyMap()
    }

    "AFK participants leavers zero and sub-ten XP never receive a book" {
        val active = UUID.randomUUID()
        val afk = UUID.randomUUID()
        val left = UUID.randomUUID()
        val ledger = DungeonRunSkillLedger()
        ledger.record(active, "SWORDS", 9)
        ledger.record(active, "ARMOR", 0)
        ledger.record(active, "ARMOR", -100)
        ledger.record(left, "SWORDS", 100)
        ledger.complete(setOf(active, afk)) shouldBe emptyMap()
    }

    "the reward never exceeds ten percent even at integer boundaries" {
        val player = UUID.randomUUID()
        val ledger = DungeonRunSkillLedger()
        ledger.record(player, "SWORDS", Long.MAX_VALUE - 2)
        ledger.record(player, "SWORDS", 10)
        ledger.complete(setOf(player)).getValue(player).getValue("SWORDS") shouldBe Long.MAX_VALUE / 10
        for (xp in 0L..500) {
            val split = DungeonRunSkillLedger()
            split.record(player, "SWORDS", xp / 2)
            split.record(player, "AXES", xp - xp / 2)
            val reward = split.complete(setOf(player))[player].orEmpty().values.sum()
            (reward <= xp / 10) shouldBe true
        }
    }
})
