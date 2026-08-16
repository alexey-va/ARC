package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ProductInterestModelTest :
    StringSpec({
        "keeps only a sanitized command root" {
            ProductCommandClassifier.root("  /RTP secret-player 1000 2000 ") shouldBe "rtp"
            ProductCommandClassifier.classify("/RTP secret-player")?.feature shouldBe ProductFeature.RTP
            ProductCommandClassifier.root("/bad.command argument").shouldBeNull()
        }

        "round trips bounded details and exit context without command arguments" {
            val gson = Gson()
            val now = 1_800_000_000_000L
            val signal =
                ProductSignal(
                    eventId = "12345678-1234-1234-1234-123456789abc",
                    source = "survival",
                    player = "a".repeat(64),
                    occurredAt = now,
                    kind = ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.COMMAND, "rtp"),
                )

            val encoded = ProductWireCodec.encode(signal, gson)
            encoded shouldNotContain "secret-player"
            ProductWireCodec.decode(encoded, "survival", now, 35, gson) shouldBe signal

            val exit =
                signal.copy(
                    eventId = "22345678-1234-1234-1234-123456789abc",
                    kind = ProductEventKind.SESSION_END,
                    detail = null,
                    exit =
                        ProductExitContext(
                            world = "vanilla",
                            command = "rtp",
                            npcId = "17",
                            npcName = "Проводник",
                            feature = ProductFeature.RTP,
                            activity = ProductActivity.EXPLORATION,
                            stage = ProductExitStage.BEFORE_OUTCOME,
                            teleportCause = "plugin",
                            trail = listOf("world=vanilla", "npc=17", "command=rtp"),
                        ),
                    sessionSeconds = 120,
                    activeSeconds = 90,
                )
            ProductWireCodec.decode(ProductWireCodec.encode(exit, gson), "survival", now, 35, gson) shouldBe exit
        }

        "rejects spoofed origin and malformed dynamic values" {
            val gson = Gson()
            val now = 1_800_000_000_000L
            val valid =
                ProductSignal(
                    eventId = "32345678-1234-1234-1234-123456789abc",
                    source = "spawn",
                    player = "b".repeat(64),
                    occurredAt = now,
                    kind = ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.WORLD, "world"),
                )
            val payload = ProductWireCodec.encode(valid, gson)

            ProductWireCodec.decode(payload, "survival", now, 35, gson).shouldBeNull()
            ProductWireCodec.decode(payload.replace("\"world\"", "\"../../secret\""), "spawn", now, 35, gson).shouldBeNull()
            ProductWireCodec.decode(payload.replace("\"systems\":[]", "\"systems\":[\"invented\"]"), "spawn", now, 35, gson).shouldBeNull()
        }

        "normalizes teleport causes into bounded product classes" {
            ProductTeleportType.classify("NETHER_PORTAL") shouldBe ProductTeleportType.PORTAL
            ProductTeleportType.classify("CONSUMABLE_EFFECT") shouldBe ProductTeleportType.ITEM
            ProductTeleportType.classify("future_cause") shouldBe ProductTeleportType.OTHER
        }
    })
