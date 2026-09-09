package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ClaimBoundaryListenerTest : FreeSpec({
    val world = UUID.randomUUID()

    "entering any land from wilderness emits enter" {
        claimBoundaryNotice(
            ClaimBoundarySnapshot(world, null, null, false),
            ClaimBoundarySnapshot(world, "land", "Home", false),
        ) shouldBe ClaimBoundaryNotice.ENTER
    }

    "leaving own or trusted land to wilderness emits leave" {
        claimBoundaryNotice(
            ClaimBoundarySnapshot(world, "land", "Home", true),
            ClaimBoundarySnapshot(world, null, null, false),
        ) shouldBe ClaimBoundaryNotice.LEAVE
    }

    "foreign land and world changes do not emit a notice" {
        claimBoundaryNotice(
            ClaimBoundarySnapshot(world, "foreign", "Other", false),
            ClaimBoundarySnapshot(world, null, null, false),
        ) shouldBe ClaimBoundaryNotice.NONE
        claimBoundaryNotice(
            ClaimBoundarySnapshot(world, null, null, false),
            ClaimBoundarySnapshot(UUID.randomUUID(), "land", "Home", false),
        ) shouldBe ClaimBoundaryNotice.NONE
    }
})
