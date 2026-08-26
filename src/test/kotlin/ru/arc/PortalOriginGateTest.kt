package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class PortalOriginGateTest : FreeSpec({
    "origin-gate permission" - {
        "requires both the feature flag and the exact permission" {
            shouldUseOriginGate(enabled = true, hasPermission = true).shouldBeTrue()
            shouldUseOriginGate(enabled = false, hasPermission = true).shouldBeFalse()
            shouldUseOriginGate(enabled = true, hasPermission = false).shouldBeFalse()
        }

        "is declared as an operator-only experimental permission" {
            val descriptor = checkNotNull(PortalOriginGateTest::class.java.getResource("/plugin.yml")).readText()
            descriptor.contains("arc.portal.origin.gate:").shouldBeTrue()
            descriptor.substringAfter("arc.portal.origin.gate:").substringBefore("arc.items.catalog.use:")
                .contains("default: op")
                .shouldBeTrue()
        }
    }

    "origin-gate settings" - {
        "normalizes valid ItemsAdder ids and accepts bounded animation values" {
            val settings =
                settings(
                    astral = " Origin_Gate_Portals:Astral_Portal ",
                    openingSoundId = " Minecraft:Block.End_Portal.Spawn ",
                )

            settings.shouldNotBeNull()
            settings.astralItemId shouldBe "origin_gate_portals:astral_portal"
            settings.chaosItemId shouldBe "origin_gate_portals:chaos_portal"
            settings.openingCurve shouldBe OriginGateOpeningCurve.DRAMATIC
            settings.openingSoundDelayTicks shouldBe 40
            settings.openingSoundId shouldBe "minecraft:block.end_portal.spawn"
        }

        "fails closed for invalid ids and resource-heavy bounds" {
            settings(astral = "not namespaced").shouldBeNull()
            settings(openingDuration = 0).shouldBeNull()
            settings(openingDuration = 201).shouldBeNull()
            settings(openingCurve = "linear").shouldBeNull()
            settings(openingDuration = 39, openingSoundDelayTicks = 40).shouldBeNull()
            settings(closingDuration = 61).shouldBeNull()
            settings(width = Float.NaN).shouldBeNull()
            settings(height = 12.1f).shouldBeNull()
            settings(yawOffsetDegrees = Float.NaN).shouldBeNull()
            settings(viewRange = 0f).shouldBeNull()
            settings(openingSoundId = "not namespaced").shouldBeNull()
            settings(openingSoundVolume = 4.1f).shouldBeNull()
            settings(suctionStreams = 17).shouldBeNull()
            settings(reducedSuctionStreams = 11).shouldBeNull()
            settings(suctionPointsPerStream = 7).shouldBeNull()
            settings(suctionStreams = 16, suctionPointsPerStream = 6).shouldBeNull()
            settings(suctionRadius = 12.1).shouldBeNull()
            settings(suctionTurns = 4.1).shouldBeNull()
        }

        "keeps the bundled portable profile disabled" {
            val defaults = checkNotNull(PortalOriginGateTest::class.java.getResource("/modules/misc.yml")).readText()
            val originGate = defaults.substringAfter("origin-gate:").substringBefore("# DUST_COLOR_TRANSITION")
            originGate.contains("enabled: false").shouldBeTrue()
            originGate.contains("astral-item: \"\"").shouldBeTrue()
            originGate.contains("chaos-item: \"\"").shouldBeTrue()
        }
    }

    "origin-gate controller" - {
        "renders server-driven opening, idle pulse, closing, and idempotent cleanup" {
            val handle = RecordingOriginGateHandle()
            var spawnCount = 0
            val controller =
                PortalOriginGateController(settings().shouldNotBeNull()) {
                    spawnCount++
                    handle
                }

            controller.tickOpening(0).shouldBeTrue()
            controller.tickOpening(33).shouldBeTrue()
            controller.tickOpening(39).shouldBeTrue()
            handle.playOpeningSoundCount shouldBe 0
            controller.tickOpening(40).shouldBeTrue()
            handle.playOpeningSoundCount shouldBe 1
            controller.tickOpening(66).shouldBeTrue()
            controller.tickOpening(67).shouldBeTrue()
            spawnCount shouldBe 1
            handle.scales.first() shouldBe 0.02f
            (handle.scales[1] > handle.scales.first()).shouldBeTrue()
            (handle.scales[1] < 1f).shouldBeTrue()
            handle.scales[4] shouldBe 1f
            (handle.scales[5] > 1f).shouldBeTrue()
            handle.playOpeningSoundCount shouldBe 1

            controller.beginClosing()
            controller.beginClosing()
            handle.prepareClosingCount shouldBe 1
            repeat(12) { controller.tickClosing().shouldBeTrue() }
            controller.tickClosing().shouldBeFalse()
            handle.scales.last() shouldBe 0.02f

            controller.remove()
            controller.remove()
            handle.removeCount shouldBe 1
            controller.isActive.shouldBeFalse()
        }

        "attempts a missing provider once and leaves the legacy visual active" {
            var spawnCount = 0
            val controller =
                PortalOriginGateController(settings().shouldNotBeNull()) {
                    spawnCount++
                    null
                }

            controller.tickOpening(0).shouldBeFalse()
            controller.tickOpening(1).shouldBeFalse()
            controller.tickOpening(58).shouldBeFalse()
            spawnCount shouldBe 1
            controller.isActive.shouldBeFalse()
            controller.tickClosing().shouldBeFalse()
        }
    }

    "origin-gate visual math" - {
        "turns the model front toward the creator with a configurable offset" {
            originGateFacingYaw(0.0, 0.0, 0.0, 5.0) shouldBe 0f
            originGateFacingYaw(0.0, 0.0, 5.0, 0.0) shouldBe -90f
            originGateFacingYaw(0.0, 0.0, -5.0, 0.0) shouldBe 90f
            originGateDisplayYaw(0.0, 0.0, 0.0, 5.0, 180f) shouldBe 180f
            originGateDisplayYaw(0.0, 0.0, 5.0, 0.0, 180f) shouldBe 90f
        }

        "generates bounded multi-point ribbons converging on the portal center" {
            val offsets =
                originGateParticleOffsets(
                    tick = 0,
                    streams = 10,
                    pointsPerStream = 3,
                    radius = 6.0,
                    height = 7.0,
                    turns = 2.25,
                )

            offsets.size shouldBe 30
            offsets.all { sqrt((it.x * it.x) + (it.z * it.z)) <= 6.0 }.shouldBeTrue()
            offsets.all { kotlin.math.abs(it.y) <= 3.5 }.shouldBeTrue()
            offsets.any { sqrt((it.x * it.x) + (it.z * it.z)) < 0.75 }.shouldBeTrue()
            offsets.map { it.stream }.distinct().size shouldBe 10
            offsets.map { it.trailPoint }.distinct().size shouldBe 3
        }

        "keeps the portal closed until the configured opening finishes" {
            settings().shouldNotBeNull().entryTick shouldBe 66
            settings(openingDuration = 96).shouldNotBeNull().entryTick shouldBe 96
        }

        "supports a sharp charge-and-snap opening while retaining the smooth option" {
            val dramaticMidpoint = originGateOpeningScale(33, 66, OriginGateOpeningCurve.DRAMATIC)
            val smoothMidpoint = originGateOpeningScale(33, 66, OriginGateOpeningCurve.SMOOTH)

            (dramaticMidpoint in 0.1f..0.2f).shouldBeTrue()
            smoothMidpoint shouldBe 0.51f
            originGateOpeningScale(66, 66, OriginGateOpeningCurve.DRAMATIC) shouldBe 1f
        }
    }
})

private fun settings(
    astral: String = "origin_gate_portals:astral_portal",
    chaos: String = "origin_gate_portals:chaos_portal",
    openingDuration: Int = 66,
    openingCurve: String = "dramatic",
    closingDuration: Int = 12,
    width: Float = 6.0f,
    height: Float = 8.4f,
    yawOffsetDegrees: Float = 180f,
    viewRange: Float = 1f,
    openingSoundId: String = "minecraft:block.end_portal.spawn",
    openingSoundDelayTicks: Int = 40,
    openingSoundVolume: Float = 1.35f,
    suctionStreams: Int = 10,
    reducedSuctionStreams: Int = 4,
    suctionPointsPerStream: Int = 3,
    suctionRadius: Double = 6.0,
    suctionTurns: Double = 2.25,
): PortalOriginGateSettings? =
    PortalOriginGateSettings.validated(
        astralItemId = astral,
        chaosItemId = chaos,
        openingStartTick = 0,
        openingDurationTicks = openingDuration,
        openingCurve = openingCurve,
        closingDurationTicks = closingDuration,
        width = width,
        height = height,
        verticalOffset = 2.75,
        yawOffsetDegrees = yawOffsetDegrees,
        viewRange = viewRange,
        openingSoundEnabled = true,
        openingSoundDelayTicks = openingSoundDelayTicks,
        openingSoundId = openingSoundId,
        openingSoundVolume = openingSoundVolume,
        openingSoundPitch = 0.9f,
        suctionEnabled = true,
        suctionStreams = suctionStreams,
        reducedSuctionStreams = reducedSuctionStreams,
        suctionPointsPerStream = suctionPointsPerStream,
        reducedSuctionPointsPerStream = 1,
        suctionRadius = suctionRadius,
        suctionHeight = 7.0,
        suctionTurns = suctionTurns,
        suctionParticleSize = 0.8f,
        suctionCoreCount = 12,
    )

private class RecordingOriginGateHandle : PortalOriginGateHandle {
    val scales = mutableListOf<Float>()
    var playOpeningSoundCount = 0
    var prepareClosingCount = 0
    var removeCount = 0

    override fun updateScale(multiplier: Float) {
        scales += multiplier
    }

    override fun playOpeningSound() {
        playOpeningSoundCount++
    }

    override fun prepareClosing() {
        prepareClosingCount++
    }

    override fun remove() {
        removeCount++
    }
}
