package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class LandsUiClaimTest : StringSpec({
    val world = UUID.randomUUID()
    val claim = LandsUiClaim("land-a", world, -3, 7)

    "accepts an unchanged current claim" {
        canConfirmUnclaim(claim, LandsUiClaim("land-a", world, -3, 7), "land-a") shouldBe true
    }

    "rejects a changed position, world, or selected land" {
        canConfirmUnclaim(claim, claim.copy(chunkX = -2), "land-a") shouldBe false
        canConfirmUnclaim(claim, claim.copy(chunkZ = 8), "land-a") shouldBe false
        canConfirmUnclaim(claim, claim.copy(worldId = UUID.randomUUID()), "land-a") shouldBe false
        canConfirmUnclaim(claim, claim.copy(landId = "land-b"), "land-a") shouldBe false
        canConfirmUnclaim(claim, claim, "land-b") shouldBe false
        canConfirmUnclaim(claim, null, "land-a") shouldBe false
    }
})
