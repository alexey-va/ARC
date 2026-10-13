package ru.arc.dungeons

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.ModuleRuntimeStatus

class ArcDungeonsReadinessTest : StringSpec({
    "lost loot availability overrides registry readiness without changing sibling modules" {
        val statuses = listOf(
            ModuleRuntimeStatus("EliteLoot", ready = true),
            ModuleRuntimeStatus("LostLoot", ready = true),
            ModuleRuntimeStatus("StaffSpells", ready = true),
        )

        moduleStatusesWithLostLootReadiness(statuses, lostLootReady = false)
            .map { it.name to it.ready } shouldBe listOf(
            "EliteLoot" to true,
            "LostLoot" to false,
            "StaffSpells" to true,
        )
        moduleStatusesWithLostLootReadiness(statuses, lostLootReady = true)
            .map { it.name to it.ready } shouldBe
            statuses.map { it.name to it.ready }
    }
})
