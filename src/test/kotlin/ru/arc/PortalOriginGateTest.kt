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
            descriptor.contains("arc.portal.origin-gate:").shouldBeTrue()
            descriptor.substringAfter("arc.portal.origin-gate:").substringBefore("arc.items-catalog.use:")
                .contains("default: op")
                .shouldBeTrue()
        }
    }

    "origin-gate settings" - {
        "normalizes valid ItemsAdder ids and accepts bounded animation values" {
            val settings = settings(astral = " Origin_Gate_Portals:Astral_Portal ")

            settings.shouldNotBeNull()
            settings.astralItemId shouldBe "origin_gate_portals:astral_portal"
            settings.chaosItemId shouldBe "origin_gate_portals:chaos_portal"
        }

        "fails closed for invalid ids and resource-heavy bounds" {
            settings(astral = "not namespaced").shouldBeNull()
            settings(openingDuration = 0).shouldBeNull()
            settings(closingDuration = 61).shouldBeNull()
            settings(width = Float.NaN).shouldBeNull()
            settings(height = 4.1f).shouldBeNull()
            settings(viewRange = 0f).shouldBeNull()
            settings(suctionStreams = 17).shouldBeNull()
            settings(reducedSuctionStreams = 9).shouldBeNull()
            settings(suctionRadius = 5.1).shouldBeNull()
        }

        "keeps the bundled portable profile disabled" {
            val defaults = checkNotNull(PortalOriginGateTest::class.java.getResource("/misc.yml")).readText()
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
            controller.tickOpening(18).shouldBeTrue()
            controller.tickOpening(36).shouldBeTrue()
            controller.tickOpening(37).shouldBeTrue()
            spawnCount shouldBe 1
            handle.scales.first() shouldBe 0.02f
            (handle.scales[1] > handle.scales.first()).shouldBeTrue()
            (handle.scales[1] < 1f).shouldBeTrue()
            handle.scales[2] shouldBe 1f
            (handle.scales[3] > 1f).shouldBeTrue()

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
        "faces the creator with a fixed billboard" {
            originGateFacingYaw(0.0, 0.0, 0.0, 5.0) shouldBe 0f
            originGateFacingYaw(0.0, 0.0, 5.0, 0.0) shouldBe -90f
            originGateFacingYaw(0.0, 0.0, -5.0, 0.0) shouldBe 90f
        }

        "generates a bounded spiral converging on the portal center" {
            val offsets = originGateParticleOffsets(tick = 0, streams = 8, radius = 2.25)

            offsets.size shouldBe 8
            offsets.all { sqrt((it.x * it.x) + (it.z * it.z)) <= 2.25 }.shouldBeTrue()
            offsets.any { sqrt((it.x * it.x) + (it.z * it.z)) < 0.5 }.shouldBeTrue()
        }
    }
})

private fun settings(
    astral: String = "origin_gate_portals:astral_portal",
    chaos: String = "origin_gate_portals:chaos_portal",
    openingDuration: Int = 36,
    closingDuration: Int = 12,
    width: Float = 2.0f,
    height: Float = 2.8f,
    viewRange: Float = 1f,
    suctionStreams: Int = 8,
    reducedSuctionStreams: Int = 3,
    suctionRadius: Double = 2.25,
): PortalOriginGateSettings? =
    PortalOriginGateSettings.validated(
        astralItemId = astral,
        chaosItemId = chaos,
        openingStartTick = 0,
        openingDurationTicks = openingDuration,
        closingDurationTicks = closingDuration,
        width = width,
        height = height,
        verticalOffset = 1.65,
        viewRange = viewRange,
        suctionEnabled = true,
        suctionStreams = suctionStreams,
        reducedSuctionStreams = reducedSuctionStreams,
        suctionRadius = suctionRadius,
        suctionParticleSize = 0.8f,
    )

private class RecordingOriginGateHandle : PortalOriginGateHandle {
    val scales = mutableListOf<Float>()
    var prepareClosingCount = 0
    var removeCount = 0

    override fun updateScale(multiplier: Float) {
        scales += multiplier
    }

    override fun prepareClosing() {
        prepareClosingCount++
    }

    override fun remove() {
        removeCount++
    }
}
