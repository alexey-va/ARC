package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.arc.util.Common
import java.nio.file.Files

class ExternalProductEventTest : StringSpec({
    "bounded envelope round trips and rejects unknown catalog values" {
        val envelope = ExternalProductEnvelope(
            origin = "classic",
            player = "a".repeat(64),
            source = ExternalProductSource.FARMS.label,
            event = ExternalProductEvent.FARM_REWARD_CLAIMED.label,
            operationId = "farm:zone-1:grant-2",
            occurredAt = 1_700_000_000_000,
        )
        val codec = ExternalProductEnvelopeCodec.codec(Common.gson) { 1_700_000_000_100 }
        codec.decode(codec.encode(envelope)) shouldBe envelope
        shouldThrow<IllegalArgumentException> { codec.decode(Common.gson.toJson(envelope.copy(event = "fake"))) }
        shouldThrow<IllegalArgumentException> { codec.decode(Common.gson.toJson(envelope.copy(source = "arcvotes"))) }
        shouldThrow<IllegalArgumentException> { codec.decode(Common.gson.toJson(envelope.copy(occurredAt = 1_700_000_300_101))) }
        shouldThrow<IllegalArgumentException> { codec.decode(Common.gson.toJson(envelope.copy(occurredAt = 1L))) }
        shouldThrow<IllegalArgumentException> {
            codec.decode("{\"version\":1,\"player\":\"${"a".repeat(64)}\",\"source\":\"arcfarms\",\"event\":\"fake\",\"operationId\":\"x\",\"occurredAt\":1700000000000}")
        }
    }

    "external event survives store persistence and old files default empty" {
        val path = Files.createTempDirectory("arc-product").resolve("state.json")
        val config = ProductInterestConfig(networkEnabled = false, persistIntervalSeconds = 10)
        val player = ProductPseudonym.of("00000000-0000-0000-0000-000000000001")
        val store = ProductInterestStore.open(path, config, 1_700_000_000_000, Common.prettyGson)
        store.applyExternal(player, ExternalProductSource.FARMS, ExternalProductEvent.FARM_REWARD_CLAIMED, 1_700_000_000_000) shouldBe true
        store.flush(1_700_000_000_100, force = true) shouldBe true
        val restored = ProductInterestStore.open(path, config, 1_700_000_000_200, Common.prettyGson)
        val report = restored.report(1_700_000_000_200, 1, 10)
        (report["externalEvents"] as Map<*, *>)["arcfarms:farm_reward_claimed"] shouldBe 1L
        Files.writeString(path, "{\"version\":1,\"savedAt\":1700000000000,\"players\":[]}")
        val legacy = ProductInterestStore.open(path, config, 1_700_000_000_200, Common.prettyGson)
        (legacy.report(1_700_000_000_200, 1, 10)["externalEvents"] as Map<*, *>).isEmpty() shouldBe true
        Files.deleteIfExists(path)
    }
})
