package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify

class ExternalProductTelemetryBridgeTest : StringSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "rejects unknown labels, sources, and arbitrary action names" {
        ExternalProductTelemetryBridge.record(player, "arcranks", outcome = "made_up", operationId = "contract:1") shouldBe false
        ExternalProductTelemetryBridge.record(player, "arcranks", action = "sql", operationId = "contract:2") shouldBe false
        ExternalProductTelemetryBridge.record(player, "unknown", feature = "contracts", operationId = "contract:3") shouldBe false
    }

    "rejects mixed signals and unsafe operation ids" {
        ExternalProductTelemetryBridge.record(player, "arcranks", feature = "contracts", action = "move", operationId = "contract:4") shouldBe false
        ExternalProductTelemetryBridge.record(player, "arcranks", outcome = "contract_complete", operationId = "bad id") shouldBe false
    }

    "routes valid signals once and does not replay them" {
        ExternalProductTelemetryBridge.clearReplayState()
        var calls = 0
        val sink: (String, ProductFeature?, ProductOutcome?, ProductAction?) -> Boolean = { _, feature, outcome, action ->
            calls++
            feature shouldBe null
            outcome shouldBe ProductOutcome.CONTRACT_COMPLETE
            action shouldBe null
            true
        }
        ExternalProductTelemetryBridge.recordForSink(player, "arcranks", outcome = "contract_complete", operationId = "contract:6", sink = sink) shouldBe true
        ExternalProductTelemetryBridge.recordForSink(player, "arcranks", outcome = "contract_complete", operationId = "contract:6", sink = sink) shouldBe false
        calls shouldBe 1
    }

    "does not change result when the sink fails" {
        ExternalProductTelemetryBridge.clearReplayState()
        ExternalProductTelemetryBridge.recordForSink(player, "arcranks", outcome = "contract_complete", operationId = "contract:7", sink = { _, _, _, _ -> error("sink") }) shouldBe false
    }

    "external domain events bind source and keep purchase and activation identities separate" {
        ExternalProductTelemetryBridge.clearReplayState()
        mockkObject(MetricsModule)
        try {
            every { MetricsModule.recordExternalEvent(any(), any(), any(), any()) } returns true
            ExternalProductTelemetryBridge.recordEvent(player, "arcvotes", "farm_reward_claimed", "grant-1") shouldBe false
            ExternalProductTelemetryBridge.recordEvent(player, "arcecojobs", "job_boost_purchased", "voucher-1") shouldBe true
            ExternalProductTelemetryBridge.recordEvent(player, "arcecojobs", "job_boost_activated", "voucher-1") shouldBe true
            ExternalProductTelemetryBridge.recordEvent(player, "arcecojobs", "job_boost_activated", "voucher-1") shouldBe false
            verify(exactly = 2) { MetricsModule.recordExternalEvent(any(), ExternalProductSource.JOBS, any(), ProductPseudonym.of("voucher-1")) }
            val giveaway = "giveaway:$player:$player"
            ExternalProductTelemetryBridge.recordEvent(player, "arcgiveaways", "giveaway_item_granted", giveaway) shouldBe true
            ExternalProductTelemetryBridge.recordEvent(player, "arcgiveaways", "giveaway_item_granted", giveaway) shouldBe false
            verify(exactly = 1) { MetricsModule.recordExternalEvent(player, ExternalProductSource.GIVEAWAYS, ExternalProductEvent.GIVEAWAY_ITEM_GRANTED, ProductPseudonym.of(giveaway)) }
            ExternalProductTelemetryBridge.recordEvent(player, "arcvotes", "vote_reward_claimed", "x".repeat(257)) shouldBe false
            every { MetricsModule.recordExternalEvent(any(), any(), any(), ProductPseudonym.of("failing")) } throws IllegalStateException("unavailable sink")
            ExternalProductTelemetryBridge.recordEvent(player, "arcvotes", "vote_reward_claimed", "failing") shouldBe false
        } finally {
            unmockkObject(MetricsModule)
            ExternalProductTelemetryBridge.clearReplayState()
        }
    }
})
