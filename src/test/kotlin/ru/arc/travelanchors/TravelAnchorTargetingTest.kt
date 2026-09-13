package ru.arc.travelanchors

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent

class TravelAnchorTargetingTest : FunSpec({
    test("the closest aim wins and distance breaks an exact tie") {
        val candidates = listOf(
            AimCandidate("edge", distanceSquared = 4.0, dot = 0.92),
            AimCandidate("far-center", distanceSquared = 100.0, dot = 0.99),
            AimCandidate("near-center", distanceSquared = 25.0, dot = 0.99),
        )

        chooseTravelAnchorTarget(candidates, maxDistanceSquared = 64.0, minimumDot = 0.95)?.target shouldBe "near-center"
    }

    test("targets outside the range or selection angle are rejected") {
        chooseTravelAnchorTarget(
            listOf(AimCandidate("far", distanceSquared = 65.0, dot = 1.0)),
            maxDistanceSquared = 64.0,
            minimumDot = 0.95,
        ) shouldBe null
        chooseTravelAnchorTarget(
            listOf(AimCandidate("edge", distanceSquared = 4.0, dot = 0.94)),
            maxDistanceSquared = 64.0,
            minimumDot = 0.95,
        ) shouldBe null
    }

    test("the outline grows as the crosshair approaches the anchor") {
        travelAnchorScale(0.4, 0.4, 1.0f, 3.0f) shouldBe 1.0f
        travelAnchorScale(0.7, 0.4, 1.0f, 3.0f) shouldBe 2.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f) shouldBe 3.0f
        (travelAnchorScale(0.8, 0.4, 1.0f, 3.0f) > travelAnchorScale(0.6, 0.4, 1.0f, 3.0f)) shouldBe true
    }

    test("standing on an anchor gives Shift priority over the staff hint") {
        travelAnchorTargetMessage(hasAnchorBelow = true, staffHeld = true) shouldBe "target-anchor"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = true) shouldBe "target-staff"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = false) shouldBe null
    }

    test("far anchors use a nearby proxy while near anchors keep their real distance") {
        travelAnchorDisplayDistance(actualDistance = 32.0, proxyDistance = 48.0) shouldBe 32.0
        travelAnchorDisplayDistance(actualDistance = 900.0, proxyDistance = 48.0) shouldBe 48.0
    }

    test("the staff receives right-click-air events that Bukkit pre-cancels") {
        val handler = TravelAnchorsModule::class.java
            .getDeclaredMethod("onInteract", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.priority shouldBe EventPriority.HIGHEST
        handler.ignoreCancelled shouldBe false
    }
})
