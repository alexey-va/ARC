package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ProductInterestTelemetryTest :
    StringSpec({
        "records detailed journey and safe aggregate metrics" {
            var now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val telemetry =
                ProductInterestTelemetry(
                    registry = registry,
                    config = ProductInterestConfig(networkEnabled = false, buildingThreshold = 2, zoneId = ZoneId.of("UTC")),
                    rawServerName = "survival",
                    statePath = Files.createTempDirectory("product-telemetry-").resolve("product-interest-v1.json"),
                    primaryAggregator = true,
                    clockMillis = { now },
                )
            val playerId = "00000000-0000-0000-0000-000000000042"
            telemetry.join(playerId, true, sample(playerId, "vanilla"), now = now)
            now += 1_000
            telemetry.command(playerId, "/rtp secret-player 100 200", now)
            telemetry.npcClick(playerId, 17, "<gold>Проводник</gold>", now)
            telemetry.teleport(playerId, ProductWorldType.SURVIVAL, ProductWorldType.RESOURCE, "mining", "PLUGIN", now)
            telemetry.action(playerId, ProductAction.BLOCK_PLACE, now)
            telemetry.action(playerId, ProductAction.BLOCK_PLACE, now)
            now += 60_000
            telemetry.leave(playerId, now)

            val report = telemetry.report(7, 20, networkReady = false, now = now)
            report["complete"] shouldBe true
            report.toString().contains("rtp") shouldBe true
            report.toString().contains("secret-player") shouldBe false
            report.toString().contains("Проводник") shouldBe true
            report.toString().contains("mining") shouldBe true
            report.toString().contains("world=vanilla") shouldBe true
            report.toString().contains("npc=17") shouldBe true
            report.toString().contains(playerId) shouldBe false

            val scrape = registry.scrape()
            scrape shouldNotContain playerId
            scrape shouldNotContain "secret-player"
            scrape shouldNotContain "vanilla"
            registry.get("arc_product_meaningful_outcomes").tag("outcome", "building_threshold").counter().count() shouldBeExactly 1.0
            registry.get("arc_product_detail_events").tag("dimension", "command").counter().count() shouldBeExactly 1.0
            registry
                .get("arc_product_teleports")
                .tags("cause", "plugin", "from", "survival", "to", "resource")
                .counter()
                .count() shouldBeExactly 1.0
        }

        "separates known QA activity from organic rolling data" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val telemetry =
                ProductInterestTelemetry(
                    registry,
                    ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC")),
                    "survival",
                    Files.createTempDirectory("product-telemetry-qa-").resolve("product-interest-v1.json"),
                    true,
                    clockMillis = { now },
                )
            val qa = "00000000-0000-0000-0000-000000000099"
            telemetry.join(qa, false, sample(qa, "vanilla"), qa = true, now = now)
            telemetry.command(qa, "/rtp private target", now)
            telemetry.npcClick(qa, 17, "Проводник", now)
            telemetry.leave(qa, now + 1_000)

            telemetry.report(1, 20, networkReady = false, now = now + 1_000)["players"] shouldBe 0
            registry.get("arc_product_qa_events").tag("event", "command").counter().count() shouldBeExactly 1.0
            registry.get("arc_product_detail_events").tag("dimension", "command").counter().count() shouldBeExactly 0.0
        }

        "records funnel latency only on the first transition" {
            var now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val telemetry =
                ProductInterestTelemetry(
                    registry,
                    ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC")),
                    "spawn",
                    Files.createTempDirectory("product-telemetry-latency-").resolve("product-interest-v1.json"),
                    true,
                    clockMillis = { now },
                )
            val player = "00000000-0000-0000-0000-000000000077"
            telemetry.join(player, true, sample(player, "world"), now = now)
            now += 1_000
            telemetry.command(player, "/mm", now)
            now += 1_000
            telemetry.command(player, "/mm", now)

            registry
                .get("arc_product_funnel_latency")
                .tags("transition", "join_to_menu", "path", "none")
                .timer()
                .count() shouldBe 1
        }

        "accepts validated network detail once and rejects origin spoofing" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val telemetry =
                ProductInterestTelemetry(
                    registry,
                    ProductInterestConfig(networkEnabled = true, zoneId = ZoneId.of("UTC")),
                    "spawn",
                    Files.createTempDirectory("product-telemetry-network-").resolve("product-interest-v1.json"),
                    true,
                    redis,
                    clockMillis = { now },
                )
            telemetry.start()
            val player = ProductPseudonym.of("remote-player")
            val signal =
                ProductSignal(
                    ProductPseudonym.eventId(),
                    "survival",
                    player,
                    now,
                    ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.WORLD, "vanilla"),
                )
            val payload = ProductWireCodec.encode(signal, com.google.gson.Gson())
            redis.simulateExternalMessage(ProductInterestTelemetry.CHANNEL, payload, "survival")
            redis.simulateExternalMessage(ProductInterestTelemetry.CHANNEL, payload, "survival")
            redis.simulateExternalMessage(ProductInterestTelemetry.CHANNEL, payload, "spawn")

            telemetry.report(1, 20, networkReady = true, now = now)["players"] shouldBe 1
            registry.get("arc_product_telemetry_transport").tag("result", "receive").counter().count() shouldBeExactly 1.0
            registry.get("arc_product_telemetry_transport").tag("result", "duplicate").counter().count() shouldBeExactly 1.0
            registry.get("arc_product_telemetry_transport").tag("result", "invalid").counter().count() shouldBeExactly 1.0
        }
    })

private fun sample(
    playerId: String,
    world: String,
): ProductPlayerSample =
    ProductPlayerSample(playerId, ProductWorldType.classify(world), world, 0.0, 64.0, 0.0)
