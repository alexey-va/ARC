package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

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
        "opens once, closes for the configured duration, and removes idempotently" {
            val handle = RecordingOriginGateHandle()
            var spawnCount = 0
            val controller =
                PortalOriginGateController(settings().shouldNotBeNull()) {
                    spawnCount++
                    handle
                }

            controller.tickOpening(31).shouldBeFalse()
            controller.tickOpening(32).shouldBeTrue()
            controller.tickOpening(33).shouldBeTrue()
            controller.tickOpening(58).shouldBeTrue()
            spawnCount shouldBe 1
            handle.openDurations shouldBe listOf(24)

            controller.beginClosing()
            controller.beginClosing()
            handle.closeDurations shouldBe listOf(12)
            repeat(12) { controller.tickClosing().shouldBeTrue() }
            controller.tickClosing().shouldBeFalse()

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

            controller.tickOpening(32).shouldBeFalse()
            controller.tickOpening(33).shouldBeFalse()
            controller.tickOpening(58).shouldBeFalse()
            spawnCount shouldBe 1
            controller.isActive.shouldBeFalse()
            controller.tickClosing().shouldBeFalse()
        }
    }
})

private fun settings(
    astral: String = "origin_gate_portals:astral_portal",
    chaos: String = "origin_gate_portals:chaos_portal",
    openingDuration: Int = 24,
    closingDuration: Int = 12,
    width: Float = 1.2f,
    height: Float = 2.2f,
    viewRange: Float = 1f,
): PortalOriginGateSettings? =
    PortalOriginGateSettings.validated(
        astralItemId = astral,
        chaosItemId = chaos,
        openingStartTick = 32,
        openingDurationTicks = openingDuration,
        closingDurationTicks = closingDuration,
        width = width,
        height = height,
        verticalOffset = 2.0,
        viewRange = viewRange,
    )

private class RecordingOriginGateHandle : PortalOriginGateHandle {
    val openDurations = mutableListOf<Int>()
    val closeDurations = mutableListOf<Int>()
    var removeCount = 0

    override fun beginOpening(durationTicks: Int) {
        openDurations += durationTicks
    }

    override fun beginClosing(durationTicks: Int) {
        closeDurations += durationTicks
    }

    override fun remove() {
        removeCount++
    }
}
