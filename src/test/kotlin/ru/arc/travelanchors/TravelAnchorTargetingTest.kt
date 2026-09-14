package ru.arc.travelanchors

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

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

    test("the scaled display edge expands the selectable angle") {
        val base = kotlin.math.cos(Math.toRadians(10.0))
        val expanded = travelAnchorExpandedSelectionDot(base, displayDistance = 5.0, scale = 3.0f)
        val edgeDot = kotlin.math.cos(Math.toRadians(20.0))

        (expanded < edgeDot) shouldBe true
        chooseTravelAnchorTarget(
            listOf(AimCandidate("scaled-edge", 25.0, edgeDot, expanded)),
            maxDistanceSquared = 64.0,
            minimumDot = base,
        )?.target shouldBe "scaled-edge"
    }

    test("the outline grows as the crosshair approaches the anchor") {
        travelAnchorScale(0.4, 0.4, 1.0f, 3.0f) shouldBe 1.0f
        travelAnchorScale(0.7, 0.4, 1.0f, 3.0f) shouldBe 2.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f) shouldBe 3.0f
        (travelAnchorScale(0.8, 0.4, 1.0f, 3.0f) > travelAnchorScale(0.6, 0.4, 1.0f, 3.0f)) shouldBe true
    }

    test("near anchors stay compact while distant aimed anchors grow") {
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, distance = 4.0, distanceScalingStart = 8.0, distanceScalingEnd = 64.0) shouldBe 1.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, distance = 36.0, distanceScalingStart = 8.0, distanceScalingEnd = 64.0) shouldBe 2.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, distance = 64.0, distanceScalingStart = 8.0, distanceScalingEnd = 64.0) shouldBe 3.0f
    }

    test("standing on an anchor gives Shift priority over the staff hint") {
        travelAnchorTargetMessage(hasAnchorBelow = true, staffHeld = true) shouldBe "target-anchor"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = true) shouldBe "target-staff"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = false) shouldBe null
    }

    test("Shift prefers the aimed anchor over the elevator below") {
        travelAnchorSneakTarget("aimed", "elevator") shouldBe "aimed"
        travelAnchorSneakTarget<String>(null, "elevator") shouldBe "elevator"
    }

    test("denial messages identify the predicate that actually failed") {
        travelAnchorDenialMessage(featureAvailable = false, ownerAllowed = true) shouldBe "wrong-world"
        travelAnchorDenialMessage(featureAvailable = true, ownerAllowed = false) shouldBe "no-permission"
        travelAnchorDenialMessage(featureAvailable = true, ownerAllowed = true) shouldBe null
    }

    test("admins bypass anchor ownership without changing ordinary access") {
        travelAnchorAdminAllows(isAdmin = true, ordinaryAccess = false) shouldBe true
        travelAnchorAdminAllows(isAdmin = false, ordinaryAccess = true) shouldBe true
        travelAnchorAdminAllows(isAdmin = false, ordinaryAccess = false) shouldBe false
    }

    test("shared anchors are usable by everyone but editable only by admins") {
        travelAnchorAccessDecision(shared = true, isAdmin = false, ordinaryAccess = false) shouldBe true
        travelAnchorEditDecision(shared = true, isAdmin = false, ownerMatches = true) shouldBe false
        travelAnchorEditDecision(shared = true, isAdmin = true, ownerMatches = false) shouldBe true
    }

    test("give commands accept an optional bounded amount") {
        parseTravelAnchorGiveAmount(null) shouldBe 1
        parseTravelAnchorGiveAmount("64") shouldBe 64
        parseTravelAnchorGiveAmount("0") shouldBe null
        parseTravelAnchorGiveAmount("4097") shouldBe null
        parseTravelAnchorGiveAmount("many") shouldBe null
    }

    test("legacy give commands default to the player and one item") {
        resolveTravelAnchorGiveRequest("GrocerMC", emptyList()) shouldBe TravelAnchorGiveRequest("GrocerMC", 1)
        resolveTravelAnchorGiveRequest(null, emptyList()) shouldBe null
        resolveTravelAnchorGiveRequest("GrocerMC", listOf("Friend")) shouldBe TravelAnchorGiveRequest("Friend", 1)
        resolveTravelAnchorGiveRequest("GrocerMC", listOf("Friend", "8")) shouldBe TravelAnchorGiveRequest("Friend", 8)
        resolveTravelAnchorGiveRequest("GrocerMC", listOf("Friend", "many")) shouldBe null
    }


    test("anchor command parses personal, shared and staff grants") {
        resolveTravelAnchorCommand("GrocerMC", listOf("give")) shouldBe
            TravelAnchorCommandRequest(TravelAnchorGiveKind.PERSONAL, "GrocerMC", 1)
        resolveTravelAnchorCommand("GrocerMC", listOf("public", "Friend", "8")) shouldBe
            TravelAnchorCommandRequest(TravelAnchorGiveKind.PUBLIC, "Friend", 8)
        resolveTravelAnchorCommand(null, listOf("staff", "Friend", "2")) shouldBe
            TravelAnchorCommandRequest(TravelAnchorGiveKind.STAFF, "Friend", 2)
        resolveTravelAnchorCommand(null, listOf("public")) shouldBe null
        resolveTravelAnchorCommand("GrocerMC", listOf("unknown")) shouldBe null
    }

    test("vertical anchors behave like an OpenBlocks elevator") {
        travelAnchorElevatorTargetY(64, listOf(12, 80, 96), upward = true) shouldBe 80
        travelAnchorElevatorTargetY(64, listOf(12, 48, 80), upward = false) shouldBe 48
        travelAnchorElevatorTargetY(64, listOf(64), upward = true) shouldBe null
    }

    test("far anchors use a nearby proxy while near anchors keep their real distance") {
        travelAnchorDisplayDistance(actualDistance = 32.0, proxyDistance = 48.0) shouldBe 32.0
        travelAnchorDisplayDistance(actualDistance = 900.0, proxyDistance = 48.0) shouldBe 48.0
    }

    test("only distant proxies are flattened into camera-facing squares") {
        travelAnchorDisplayShape(actualDistance = 32.0, proxyDistance = 48.0, scale = 3.0f) shouldBe
            TravelAnchorDisplayShape(cameraFacing = false, depth = 3.0f)
        travelAnchorDisplayShape(actualDistance = 900.0, proxyDistance = 48.0, scale = 3.0f) shouldBe
            TravelAnchorDisplayShape(cameraFacing = true, depth = 0.03f)
    }

    test("selected anchor labels stay readable at near and proxy distances") {
        travelAnchorLabelScale(distance = 6.0, minimumScale = 1.8f, maximumScale = 6.0f) shouldBe 1.8f
        travelAnchorLabelScale(distance = 48.0, minimumScale = 1.8f, maximumScale = 6.0f) shouldBe 6.0f
    }

    test("proxy coordinates never inherit camera rotation") {
        val center = travelAnchorDisplayCenter(
            eye = Location(null, 0.0, 64.0, 0.0, 90f, 45f),
            target = Location(null, 100.0, 64.0, 0.0),
            distance = 48.0,
        )

        center.x shouldBe 48.0
        center.yaw shouldBe 0f
        center.pitch shouldBe 0f
    }

    test("clicking an anchor without the staff opens naming") {
        travelAnchorInteraction(clickedAnchor = true, staffHeld = false) shouldBe TravelAnchorInteraction.RENAME
        travelAnchorInteraction(clickedAnchor = true, staffHeld = true) shouldBe TravelAnchorInteraction.TELEPORT
        travelAnchorInteraction(clickedAnchor = false, staffHeld = true) shouldBe TravelAnchorInteraction.TELEPORT
        travelAnchorInteraction(clickedAnchor = false, staffHeld = false) shouldBe TravelAnchorInteraction.IGNORE
    }

    test("anchor names validate and survive the world index codec") {
        normalizeTravelAnchorName("  Дом у шахты  ") shouldBe "Дом у шахты"
        normalizeTravelAnchorName("   ") shouldBe null
        normalizeTravelAnchorName("строка\nдва") shouldBe null
        normalizeTravelAnchorName("я".repeat(33)) shouldBe null

        val names = listOf(
            TravelAnchorNameEntry(-12, 64, 7, "Дом у шахты"),
            TravelAnchorNameEntry(240, -20, -99, "Северный портал"),
        )
        decodeTravelAnchorNames(encodeTravelAnchorNames(names)) shouldBe names
    }

    test("anchor ownership follows player names and migrates legacy UUID identities") {
        val grocer = UUID.fromString("c63d7480-5db5-4d1d-9ac6-9abeb8ee3a40")
        val other = UUID.fromString("b2d896e9-e2f3-4389-a28a-07ef05f7a694")

        travelAnchorIdentityAllows(null, "GrocerMC", grocer) shouldBe true
        travelAnchorIdentityAllows("grocermc", "GrocerMC", other) shouldBe true
        travelAnchorIdentityAllows(grocer.toString(), "GrocerMC", other) { legacy ->
            if (legacy == grocer) "GrocerMC" else null
        } shouldBe true
        travelAnchorIdentityAllows(other.toString(), "GrocerMC", grocer) shouldBe false
        normalizeTravelAnchorOwner(grocer.toString()) { legacy ->
            if (legacy == grocer) "GrocerMC" else null
        } shouldBe "GrocerMC"

        val owners = listOf(
            TravelAnchorOwnerEntry(-12, 64, 7, "GrocerMC"),
            TravelAnchorOwnerEntry(240, -20, -99, other.toString()),
        )
        decodeTravelAnchorOwners(encodeTravelAnchorOwners(owners)) shouldBe owners
    }

    test("an owner can share every anchor with selected players") {
        val owner = UUID.fromString("c63d7480-5db5-4d1d-9ac6-9abeb8ee3a40")
        val friend = UUID.fromString("b2d896e9-e2f3-4389-a28a-07ef05f7a694")
        val stranger = UUID.fromString("0ba1653d-4d64-4ca4-8388-94b3381dc090")

        travelAnchorAccessAllows("GrocerMC", "GrocerMC", owner, setOf("Friend")) shouldBe true
        travelAnchorAccessAllows("GrocerMC", "Friend", friend, setOf("friend")) shouldBe true
        travelAnchorAccessAllows("GrocerMC", "Stranger", stranger, setOf("friend")) shouldBe false

        val access = listOf(TravelAnchorAccessEntry("GrocerMC", setOf("Friend", stranger.toString())))
        decodeTravelAnchorAccess(encodeTravelAnchorAccess(access)) shouldBe access
        normalizeTravelAnchorAccessNames("alterra, foll alterra") shouldBe listOf("alterra", "foll")
        normalizeTravelAnchorAccessNames("") shouldBe emptyList()
        normalizeTravelAnchorAccessNames("bad-name") shouldBe null
    }

    test("the staff receives right-click-air events that Bukkit pre-cancels") {
        val handler = TravelAnchorsModule::class.java
            .getDeclaredMethod("onInteract", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.priority shouldBe EventPriority.HIGHEST
        handler.ignoreCancelled shouldBe false
    }
})
