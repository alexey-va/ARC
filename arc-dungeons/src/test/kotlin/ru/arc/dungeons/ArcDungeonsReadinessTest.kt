package ru.arc.dungeons

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.ModuleRuntimeStatus

class ArcDungeonsReadinessTest : StringSpec({
    "an optional passive API mismatch leaves remaining native initialization available" {
        val failure = NoSuchFieldException("native passive field")
        var passiveClosed = false
        var menusReady = false
        var nativeRollback = false
        var reported: Throwable? = null
        attemptOptionalNativeIntegration(
            initialize = {
                attemptOptionalNativeIntegration(
                    initialize = { throw failure },
                    rollback = { passiveClosed = true; null },
                    onFailure = { reported = it },
                    requireCleanRollback = true,
                ) shouldBe false
                menusReady = true
            },
            rollback = { nativeRollback = true; null },
            onFailure = { throw AssertionError("Unrelated native integration must remain available", it) },
        ) shouldBe true
        passiveClosed shouldBe true
        menusReady shouldBe true
        nativeRollback shouldBe false
        (reported === failure) shouldBe true
    }

    "failed passive cleanup still rolls back native initialization and retains both causes" {
        val failure = IllegalStateException("passive install failed")
        val cleanup = IllegalStateException("passive cleanup failed")
        var nativeRollback = false
        var reported: Throwable? = null
        attemptOptionalNativeIntegration(
            initialize = {
                attemptOptionalNativeIntegration(
                    initialize = { throw failure },
                    rollback = { cleanup },
                    onFailure = { error("Failed cleanup must propagate") },
                    requireCleanRollback = true,
                )
                error("Unsafe partial initialization must stop")
            },
            rollback = { nativeRollback = true; null },
            onFailure = { reported = it },
        ) shouldBe false
        nativeRollback shouldBe true
        (reported === failure) shouldBe true
        failure.suppressed.toList() shouldBe listOf(cleanup)
    }

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
