package ru.arc.jobs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import java.util.UUID

class JobsModuleSafetyTest : FreeSpec({
    beforeEach {
        JobsModule.shutdown()
    }

    "shutdown is idempotent before initialization" {
        JobsModule.shutdown()
        JobsModule.shutdown()
    }

    "cache reads are safe before initialization" {
        JobsModule.getBoostData(UUID.randomUUID()).shouldBeNull()
    }

    "addBoost rejects work before initialization" {
        JobsModule
            .addBoost(
                player = UUID.randomUUID(),
                jobs = listOf("Miner"),
                boost = 0.5,
                expires = System.currentTimeMillis() + 60_000,
                boostId = "not-initialized",
                types = listOf(BoostType.MONEY),
            ).join()
            .shouldBeFalse()
    }

    "config access fails with a clear lifecycle error before initialization" {
        shouldThrow<IllegalStateException> {
            JobsModule.getConfig()
        }
    }

    "boost request validation rejects unsafe values" {
        val now = 10_000L

        isValidBoostRequest(0.5, now + 1, "valid", now).shouldBeTrue()
        isValidBoostRequest(Double.NaN, now + 1, "valid", now).shouldBeFalse()
        isValidBoostRequest(0.0, now + 1, "valid", now).shouldBeFalse()
        isValidBoostRequest(0.5, now, "valid", now).shouldBeFalse()
        isValidBoostRequest(0.5, now + 1, " ", now).shouldBeFalse()
        isValidBoostRequest(0.5, now + 1, " padded ", now).shouldBeFalse()
    }
})
