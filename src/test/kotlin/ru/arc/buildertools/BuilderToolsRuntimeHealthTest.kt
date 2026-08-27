package ru.arc.buildertools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.observability.RuntimeHealthState

class BuilderToolsRuntimeHealthTest : FunSpec({
    test("healthy runtime publishes only bounded aggregate readiness") {
        val contribution = BuilderToolsRuntimeHealth.contribution(healthyInputs())

        contribution.state shouldBe RuntimeHealthState.UP
        contribution.recoveryBacklog shouldBe 0
        contribution.activeLeases shouldBe 0
        contribution.schemas shouldBe mapOf("book_registry" to 2)
        contribution.dependencies shouldBe mapOf(
            "lands" to true,
            "coreprotect" to true,
            "book_registry" to true,
        )
    }

    test("startup and hard recovery failure are distinguishable") {
        BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(recovering = true, bookRegistryReady = false),
        ).state shouldBe RuntimeHealthState.STARTING

        val failed = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(
                recoveryBlocked = true,
                recoveryPlayers = 2,
                deliveryWaitingForSpace = 3,
                activeOperations = 1,
                bookLockedPlayers = 4,
            ),
        )
        failed.state shouldBe RuntimeHealthState.DOWN
        failed.recoveryBacklog shouldBe 5
        failed.activeLeases shouldBe 5
    }

    test("book registry failure degrades tools without hiding the dependency") {
        val contribution = BuilderToolsRuntimeHealth.contribution(
            healthyInputs().copy(bookRegistryReady = false, bookRegistryFailed = true),
        )

        contribution.state shouldBe RuntimeHealthState.DEGRADED
        contribution.dependencies["book_registry"] shouldBe false
    }

    test("external failure classification never includes the exception message") {
        BuilderToolsFailureType.of(IllegalStateException("jdbc:mysql://secret-host/password")) shouldBe
            "IllegalStateException"
        BuilderToolsFailureType.of(null) shouldBe "missing_result"
    }
})

private fun healthyInputs() = BuilderToolsRuntimeHealthInputs(
    closed = false,
    recovering = false,
    recoveryBlocked = false,
    recoveryPlayers = 0,
    deliveryWaitingForSpace = 0,
    activeOperations = 0,
    bookLockedPlayers = 0,
    landsRequired = true,
    landsAvailable = true,
    coreProtectRequired = true,
    coreProtectAvailable = true,
    bookContractsEnabled = true,
    bookRegistryReady = true,
    bookRegistryFailed = false,
)
