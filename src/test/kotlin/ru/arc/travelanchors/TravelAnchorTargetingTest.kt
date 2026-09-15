package ru.arc.travelanchors

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent
import ru.arc.config.ConfigManager
import java.nio.file.Files
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

    test("anchor aim scaling starts at fifteen blocks and grows linearly") {
        travelAnchorScale(0.4, 0.4, 1.0f, 3.0f, 14.9, 15.0, 48.0) shouldBe 1.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, 15.0, 15.0, 48.0) shouldBe 1.0f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, 31.5, 15.0, 48.0) shouldBe 2.0f
        travelAnchorScale(0.7, 0.4, 1.0f, 3.0f, 31.5, 15.0, 48.0) shouldBe 1.5f
        travelAnchorScale(1.0, 0.4, 1.0f, 3.0f, 48.0, 15.0, 48.0) shouldBe 3.0f
    }

    test("teleport portals snap open smoothly, hold for a second and collapse") {
        val scales = (0..30).map { travelAnchorTeleportPortalScale(it, 5, 20, 4) }

        scales[0] shouldBe 0.02f
        (scales[1]!! > 0.45f) shouldBe true
        (0 until 5).all { scales[it]!! < scales[it + 1]!! } shouldBe true
        (5..25).all { scales[it] == 1f } shouldBe true
        (scales[26]!! > scales[27]!!) shouldBe true
        scales[29] shouldBe 0.02f
        scales[30] shouldBe null
    }

    test("touching horizontal anchors form one target group without merging elevator floors") {
        val world = UUID.fromString("c63d7480-5db5-4d1d-9ac6-9abeb8ee3a40")
        val adjacent = listOf(
            TravelAnchorPosition(world, 0, 64, 0),
            TravelAnchorPosition(world, 1, 64, 1),
            TravelAnchorPosition(world, 2, 64, 1),
        )
        val upperFloor = TravelAnchorPosition(world, 1, 65, 1)
        val isolated = TravelAnchorPosition(world, 8, 64, 8)

        clusterTravelAnchorPositions(adjacent + upperFloor + isolated).map { it.toSet() }.toSet() shouldBe setOf(
            adjacent.toSet(),
            setOf(upperFloor),
            setOf(isolated),
        )
    }

    test("a connected anchor shape is capped and the source group is hidden together") {
        val world = UUID.fromString("c63d7480-5db5-4d1d-9ac6-9abeb8ee3a40")
        val connected = (0 until 12).map { x -> TravelAnchorPosition(world, x, 64, 0) }
        val group = clusterTravelAnchorPositions(connected).single()

        travelAnchorDisplayMembers(group, maximumBlocks = 10) shouldBe connected.take(10)
        travelAnchorGroupContainsSource(group, connected[7]) shouldBe true
        travelAnchorGroupContainsSource(group, TravelAnchorPosition(world, 40, 64, 0)) shouldBe false
        travelAnchorGroupRadius(connected.take(2)) shouldBe (0.5 plusOrMinus 1.0e-9)
    }

    test("connected display blocks scale around one shared center") {
        val world = UUID.fromString("c63d7480-5db5-4d1d-9ac6-9abeb8ee3a40")
        val left = TravelAnchorPosition(world, 0, 64, 0)
        val right = TravelAnchorPosition(world, 1, 64, 0)

        val leftOffset = travelAnchorDisplayOffset(left, centerX = 1.0, centerY = 64.5, centerZ = 0.5, scale = 2.0f)
        val rightOffset = travelAnchorDisplayOffset(right, centerX = 1.0, centerY = 64.5, centerZ = 0.5, scale = 2.0f)

        leftOffset shouldBe TravelAnchorDisplayOffset(-1.0, 0.0, 0.0)
        rightOffset shouldBe TravelAnchorDisplayOffset(1.0, 0.0, 0.0)
    }

    test("standing on an anchor gives Shift priority over the staff hint") {
        travelAnchorTargetMessage(hasAnchorBelow = true, staffHeld = true) shouldBe "target-anchor"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = true) shouldBe "target-staff"
        travelAnchorTargetMessage(hasAnchorBelow = false, staffHeld = false) shouldBe null
    }

    test("Shift prefers the aimed anchor over the elevator below") {
        travelAnchorSneakTarget("aimed", "elevator") shouldBe TravelAnchorSneakTarget("aimed", enforceCooldown = true)
        travelAnchorSneakTarget<String>(null, "elevator") shouldBe TravelAnchorSneakTarget("elevator", enforceCooldown = false)
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

    test("a Lands owner may remove a foreign anchor only on their land") {
        travelAnchorBreakAllowed(ownsAnchor = true, ownsLand = false) shouldBe true
        travelAnchorBreakAllowed(ownsAnchor = false, ownsLand = true) shouldBe true
        travelAnchorBreakAllowed(ownsAnchor = false, ownsLand = false) shouldBe false
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

    test("standing anywhere over an anchor footprint activates it") {
        travelAnchorSupportColumns(0.5, 0.5) shouldBe listOf(0 to 0)
        travelAnchorSupportColumns(1.05, 0.5) shouldBe listOf(1 to 0, 0 to 0)
        travelAnchorSupportColumns(1.29, 1.29) shouldBe listOf(1 to 1, 0 to 0, 0 to 1, 1 to 0)
        travelAnchorSupportColumns(1.30, 0.5) shouldBe listOf(1 to 0)
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

    test("teleport portal stays behind the exact player position") {
        val southFacing = travelAnchorTeleportPortalCenter(
            Location(null, 10.18, 65.0, -3.82, 0f, 27f),
            verticalOffset = 2.15,
            behindPlayerOffset = 0.35,
            yawOffsetDegrees = 180f,
        )
        southFacing.x shouldBe (10.18 plusOrMinus 1.0e-9)
        southFacing.y shouldBe (67.15 plusOrMinus 1.0e-9)
        southFacing.z shouldBe (-4.17 plusOrMinus 1.0e-9)
        southFacing.yaw shouldBe -180f
        southFacing.pitch shouldBe 0f

        val westFacing = travelAnchorTeleportPortalCenter(
            Location(null, 10.18, 65.0, -3.82, 90f, 0f),
            verticalOffset = 2.15,
            behindPlayerOffset = 0.35,
            yawOffsetDegrees = 180f,
        )
        westFacing.x shouldBe (10.53 plusOrMinus 1.0e-9)
        westFacing.z shouldBe (-3.82 plusOrMinus 1.0e-9)
    }

    test("display tuning and portal offsets are reread through the ARC reload config path") {
        val directory = Files.createTempDirectory("arc-travel-anchor-reload")
        val configFile = ConfigManager.moduleYamlPath(directory, "teleport-anchors.yml").toFile()
        try {
            configFile.parentFile.mkdirs()
            configFile.writeText(
                """
                visual:
                  minimum-scale: 1.0
                  maximum-scale: 3.0
                  scale-start-distance: 15.0
                  full-scale-distance: 48.0
                  maximum-group-blocks: 10
                  teleport-portal:
                    vertical-offset: 2.15
                    behind-player-offset: 0.35
                    yaw-offset-degrees: 180.0
                """.trimIndent(),
            )
            val config = ConfigManager.ofModule(directory, "teleport-anchors.yml")
            val initial = config.travelAnchorTeleportPortalOffsets()
            initial.vertical shouldBe (2.15 plusOrMinus 1.0e-9)
            initial.behindPlayer shouldBe (0.35 plusOrMinus 1.0e-9)
            initial.yawDegrees shouldBe 180f
            val initialDisplay = config.travelAnchorDisplayTuning()
            initialDisplay.minimumScale shouldBe 1.0f
            initialDisplay.maximumScale shouldBe 3.0f
            initialDisplay.scaleStartDistance shouldBe (15.0 plusOrMinus 1.0e-9)
            initialDisplay.fullScaleDistance shouldBe (48.0 plusOrMinus 1.0e-9)
            initialDisplay.maximumGroupBlocks shouldBe 10

            configFile.writeText(
                """
                visual:
                  minimum-scale: 1.2
                  maximum-scale: 4.0
                  scale-start-distance: 20.0
                  full-scale-distance: 60.0
                  maximum-group-blocks: 7
                  teleport-portal:
                    vertical-offset: 1.8
                    behind-player-offset: 0.6
                    yaw-offset-degrees: 165.0
                """.trimIndent(),
            )
            ConfigManager.reloadAll()
            val reloaded = config.travelAnchorTeleportPortalOffsets()
            reloaded.vertical shouldBe (1.8 plusOrMinus 1.0e-9)
            reloaded.behindPlayer shouldBe (0.6 plusOrMinus 1.0e-9)
            reloaded.yawDegrees shouldBe 165f
            val reloadedDisplay = config.travelAnchorDisplayTuning()
            reloadedDisplay.minimumScale shouldBe 1.2f
            reloadedDisplay.maximumScale shouldBe 4.0f
            reloadedDisplay.scaleStartDistance shouldBe (20.0 plusOrMinus 1.0e-9)
            reloadedDisplay.fullScaleDistance shouldBe (60.0 plusOrMinus 1.0e-9)
            reloadedDisplay.maximumGroupBlocks shouldBe 7
        } finally {
            ConfigManager.clear()
            directory.toFile().deleteRecursively()
        }
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

    test("anchor display materials survive the world index codec and reject non-blocks") {
        val materials = listOf(
            TravelAnchorMaterialEntry(-12, 64, 7, Material.AMETHYST_BLOCK),
            TravelAnchorMaterialEntry(240, -20, -99, Material.RESPAWN_ANCHOR),
        )

        decodeTravelAnchorMaterials(encodeTravelAnchorMaterials(materials)) shouldBe materials
        decodeTravelAnchorMaterials("0,64,0|BLAZE_ROD\n1,64,0|NOT_A_MATERIAL") shouldBe emptyList()
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
