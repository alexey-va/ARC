package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class DungeonInterestMetricsTest :
    StringSpec({
        "classifies declared dungeon worlds and excludes the EliteMobs hub" {
            val config =
                DungeonInterestConfig(
                    instanceWorldBases =
                        setOf(
                            "em_id_the_quarry",
                            "em_id_the_bridge",
                            "em_id_binder_of_worlds",
                            "em_id_enchantment_challenge_10",
                        ),
                )

            config.dungeonWorld("EM_Invasion") shouldBe "em_invasion"
            config.dungeonWorld("em_id_the_quarry_1") shouldBe "em_id_the_quarry"
            config.dungeonWorld("em_id_the_bridge_27") shouldBe "em_id_the_bridge"
            config.dungeonWorld("em_id_binder_of_worlds_15") shouldBe "em_id_binder_of_worlds"
            config.dungeonWorld("em_id_enchantment_challenge_10") shouldBe "em_id_enchantment_challenge_10"
            config.dungeonWorld("em_id_enchantment_challenge_10_3") shouldBe "em_id_enchantment_challenge_10"
            config.dungeonWorld("em_yggdrasil_1") shouldBe "em_yggdrasil_1"
            config.dungeonWorld("spn_the_hell_ring_25") shouldBe "spn_the_hell_ring_25"
            config.dungeonWorld("spn_the_skeleton_casttle") shouldBe "spn_the_skeleton_casttle"
            config.dungeonWorld("otd_dungeon") shouldBe "otd_dungeon"
            config.dungeonWorld("em_adventurers_guild").shouldBeNull()
            config.dungeonWorld("survival").shouldBeNull()
            config.dungeonWorld("unsafe world name").shouldBeNull()
        }

        "records aggregate time visits and only completed session duration" {
            var now = 0L
            val registry = SimpleMeterRegistry()
            val metrics = DungeonInterestMetrics(registry, DungeonInterestConfig(), nanoTime = { now })

            metrics.enter("player-one", "em_invasion")
            now = 5_000_000_000L
            metrics.sample()
            metrics.enter("player-one", "em_invasion")
            now = 8_000_000_000L
            metrics.enter("player-one", "survival")

            registry.get("arc_dungeon_visits").tag("world", "em_invasion").counter().count() shouldBeExactly 1.0
            registry.get("arc_dungeon_player_time").tag("world", "em_invasion").counter().count() shouldBeExactly 8.0
            registry.get("arc_dungeon_session_duration").tag("world", "em_invasion").timer().count() shouldBe 1L
            registry.get("arc_dungeon_session_duration").tag("world", "em_invasion").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS) shouldBeExactly 8.0

            metrics.enter("player-two", "em_invasion")
            now = 10_000_000_000L
            metrics.shutdown()

            registry.get("arc_dungeon_player_time").tag("world", "em_invasion").counter().count() shouldBeExactly 10.0
            registry.get("arc_dungeon_session_duration").tag("world", "em_invasion").timer().count() shouldBe 1L
        }

        "aggregates separate EliteMobs instance worlds into one dungeon series" {
            var now = 0L
            val registry = SimpleMeterRegistry()
            val metrics =
                DungeonInterestMetrics(
                    registry,
                    DungeonInterestConfig(instanceWorldBases = setOf("em_id_the_quarry")),
                    nanoTime = { now },
                )

            metrics.enter("player-one", "em_id_the_quarry_1")
            now = 2_000_000_000L
            metrics.leave("player-one")
            metrics.enter("player-two", "em_id_the_quarry_2")
            now = 5_000_000_000L
            metrics.leave("player-two")

            registry.get("arc_dungeon_visits").tag("world", "em_id_the_quarry").counter().count() shouldBeExactly 2.0
            registry.get("arc_dungeon_player_time").tag("world", "em_id_the_quarry").counter().count() shouldBeExactly 5.0
            registry.find("arc_dungeon_visits").tag("world", "em_id_the_quarry_1").counter().shouldBeNull()
            registry.find("arc_dungeon_visits").tag("world", "em_id_the_quarry_2").counter().shouldBeNull()
        }

        "does not invent a visit when seeding an already-online player" {
            var now = 1_000_000_000L
            val registry = SimpleMeterRegistry()
            val metrics = DungeonInterestMetrics(registry, DungeonInterestConfig(), nanoTime = { now })

            metrics.trackExisting("player", "spn_the_jungle")
            now = 4_000_000_000L
            metrics.sample()

            registry.get("arc_dungeon_visits").tag("world", "spn_the_jungle").counter().count() shouldBeExactly 0.0
            registry.get("arc_dungeon_player_time").tag("world", "spn_the_jungle").counter().count() shouldBeExactly 3.0
        }

        "bounds world-label cardinality" {
            val registry = SimpleMeterRegistry()
            val config = DungeonInterestConfig(maxTrackedWorlds = 1)
            val metrics = DungeonInterestMetrics(registry, config)

            metrics.registerWorld("em_invasion") shouldBe true
            metrics.registerWorld("spn_the_jungle") shouldBe false
            registry.find("arc_dungeon_visits").tag("world", "spn_the_jungle").counter().shouldBeNull()
        }

        "uses the Micrometer 1.14 Prometheus counter and timer names consumed by Grafana" {
            var now = 0L
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = DungeonInterestMetrics(registry, DungeonInterestConfig(), nanoTime = { now })

            metrics.enter("player", "em_invasion")
            now = 2_000_000_000L
            metrics.leave("player")
            val scrape = registry.scrape()

            scrape.contains("arc_dungeon_player_time_seconds_total") shouldBe true
            scrape.contains("arc_dungeon_visits_total") shouldBe true
            scrape.contains("arc_dungeon_session_duration_seconds_count") shouldBe true
            scrape.contains("arc_dungeon_session_duration_seconds_sum") shouldBe true
        }
    })
