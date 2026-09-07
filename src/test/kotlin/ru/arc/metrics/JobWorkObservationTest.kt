package ru.arc.metrics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.util.Common
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class JobWorkObservationTest : StringSpec({
    val player = "a".repeat(64)
    val now = Instant.parse("2026-09-07T12:00:00Z").toEpochMilli()
    val utc = ZoneId.of("UTC")

    "sampling starts at zero and skips bursts without manufacturing work" {
        val clock = JobWorkClock()
        clock.action(player, "miner", now) shouldBe JobWorkObservation("miner", now, now)
        clock.action(player, "miner", now + 100) shouldBe null
        clock.action(player, "miner", now + 999) shouldBe null
        clock.action(player, "miner", now + 1000) shouldBe JobWorkObservation("miner", now, now + 1000)
        clock.action(player, "farmer", now + 1000) shouldBe JobWorkObservation("farmer", now + 1000, now + 1000)
        clock.action(player, "miner", now + 31_001) shouldBe JobWorkObservation("miner", now + 31_001, now + 31_001)
    }

    "logout AFK and reset break continuity and backward time never inflates duration" {
        val clock = JobWorkClock()
        clock.action(player, "miner", now)
        clock.action(player, "miner", now - 1000) shouldBe null
        clock.action(player, "miner", now + 1000) shouldBe JobWorkObservation("miner", now, now + 1000)
        clock.breakContinuity(player)
        clock.action(player, "miner", now + 2000) shouldBe JobWorkObservation("miner", now + 2000, now + 2000)
        clock.clear()
        clock.action(player, "miner", now + 3000) shouldBe JobWorkObservation("miner", now + 3000, now + 3000)
    }

    "clock capacity loss is counted and reset starts fresh quality accounting" {
        val clock = JobWorkClock(maxCursors = 1)
        clock.action(player, "miner", now)
        clock.action(player, "farmer", now)
        clock.capacityEvictions shouldBe 1L
        clock.action(player, "miner", now + 1000) shouldBe JobWorkObservation("miner", now + 1000, now + 1000)
        clock.capacityEvictions shouldBe 2L
        clock.action(player, "miner", now) shouldBe null
        clock.clockRegressions shouldBe 1L
        clock.clear()
        clock.capacityEvictions shouldBe 0L
        clock.clockRegressions shouldBe 0L
    }

    "intervals split at midnight and reset boundary and reject invalid ranges" {
        val midnight = Instant.parse("2026-09-08T00:00:00Z").toEpochMilli()
        val observation = JobWorkObservation("miner", midnight - 10_000, midnight + 10_000)
        observation.days(utc, midnight - 5000) shouldBe listOf(
            JobWorkDay("2026-09-07", midnight - 5000, midnight, 0),
            JobWorkDay("2026-09-08", midnight, midnight + 10_000, 1),
        )
        observation.days(utc, midnight + 10_001) shouldBe emptyList()
        JobWorkObservation("miner", midnight - 1000, midnight).days(utc, 0) shouldBe listOf(
            JobWorkDay("2026-09-07", midnight - 1000, midnight, 0),
            JobWorkDay("2026-09-08", midnight, midnight, 1),
        )
        JobWorkObservation("unknown", now, now).valid() shouldBe false
        JobWorkObservation("miner", now, now - 1).valid() shouldBe false
        JobWorkObservation("miner", now, now + 30_001).valid() shouldBe false
    }

    "overlap and replay cannot double count and omitted late prefixes remain visible" {
        val totals = JobWorkTotals()
        totals.add(JobWorkDay("2026-09-07", now, now + 10_000, 1))
        totals.add(JobWorkDay("2026-09-07", now + 5000, now + 15_000, 1))
        totals.add(JobWorkDay("2026-09-07", now, now + 10_000, 1))
        totals.add(JobWorkDay("2026-09-07", now - 1000, now + 1000, 1))
        totals.observedMillis shouldBe 15_000L
        totals.observations shouldBe 2L
        totals.lateObservations shouldBe 2L
    }

    "bounded job work wire contract round trips and rejects forged dimensions and stale time" {
        val codec = JobWorkEnvelopeCodec.codec(Common.gson) { now }
        val envelope = JobWorkEnvelope(origin = "survival", player = player,
            operationId = "00000000-0000-0000-0000-000000000001", job = "miner", startedAt = now - 1000, endedAt = now)
        codec.decode(codec.encode(envelope)) shouldBe envelope
        listOf(envelope.copy(job = "arbitrary"), envelope.copy(player = "raw-player"),
            envelope.copy(startedAt = now - 30_001), envelope.copy(endedAt = now + 300_001),
            envelope.copy(startedAt = 1, endedAt = 2)).forEach { bad ->
            shouldThrow<IllegalArgumentException> { codec.decode(Common.gson.toJson(bad)) }
        }
    }

    "product store persists time by day clips resets and supports old state without fabricated data" {
        val path = Files.createTempDirectory("job-work-").resolve("product.json")
        val config = ProductInterestConfig(networkEnabled = false, zoneId = utc)
        val store = ProductInterestStore.open(path, config, now, Common.prettyGson)
        store.resetPeriod(now, now)
        store.applyJobWork(player, JobWorkObservation("miner", now - 2000, now + 1000)) shouldBe true
        store.applyJobWork(player, JobWorkObservation("miner", now + 1000, now + 2000)) shouldBe true
        store.applyJobWork(player, JobWorkObservation("farmer", now, now + 2000)) shouldBe true
        store.flush(now + 2000, force = true) shouldBe true
        val restored = ProductInterestStore.open(path, config, now + 3000, Common.prettyGson)
        val report = restored.report(now + 3000, 1, 10)
        val work = report["jobWork"] as Map<*, *>
        work["coversAllWorkingTime"] shouldBe false
        val rows = work["professions"] as List<Map<String, Any?>>
        rows.associate { it["job"] to it["observedMillis"] } shouldBe mapOf("miner" to 2000L, "farmer" to 2000L)
        report.toString().contains(player) shouldBe false
        Files.writeString(path, """{"version":1,"savedAt":$now,"players":[]}""")
        val old = ProductInterestStore.open(path, config, now + 3000, Common.prettyGson)
        ((old.report(now + 3000, 1, 10)["jobWork"] as Map<*, *>)["professions"] as List<*>).isEmpty() shouldBe true
    }

    "period rollover clears local clocks and clips remote intervals at the same boundary" {
        var at = now
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val redis = ru.arc.redis.InMemoryRedis()
        val id = "00000000-0000-0000-0000-000000000018"
        val hash = ProductPseudonym.of(id)
        val telemetry = ProductInterestTelemetry(registry, ProductInterestConfig(networkEnabled = true), "spawn",
            Files.createTempDirectory("job-work-reset-").resolve("product.json"), true, redis, clockMillis = { at })
        try {
            telemetry.start()
            telemetry.pollMeasurementReset().join()
            telemetry.join(id, false, ProductPlayerSample(id, ProductWorldType.SURVIVAL, "world", 0.0, 64.0, 0.0), now = at)
            telemetry.observeJobWork(hash, "miner", at)
            at += 1000
            telemetry.observeJobWork(hash, "miner", at)
            at += 1000
            telemetry.requestMeasurementReset(0).join()
            telemetry.observeJobWork(hash, "miner", at) shouldBe JobWorkObservation("miner", at, at)
            telemetry.receiveJobWork(player, JobWorkObservation("miner", now, now + 1500)) shouldBe false
            at += 500
            telemetry.receiveJobWork(player, JobWorkObservation("miner", now + 1500, at)) shouldBe true
            val work = telemetry.report(1, 10, true)["jobWork"] as Map<*, *>
            val row = (work["professions"] as List<Map<String, Any?>>).single()
            row["observedMillis"] shouldBe 500L
            row["players"] shouldBe 2
        } finally { telemetry.shutdown(at); registry.close() }
    }

    "QA missing sessions and reconnect cannot contribute invented working time" {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val id = "00000000-0000-0000-0000-000000000017"
        val hash = ProductPseudonym.of(id)
        val telemetry = ProductInterestTelemetry(registry, ProductInterestConfig(networkEnabled = false), "spawn",
            Files.createTempDirectory("job-work-session-").resolve("product.json"), true, clockMillis = { now })
        fun sample() = ProductPlayerSample(id, ProductWorldType.SURVIVAL, "world", 0.0, 64.0, 0.0)
        try {
            telemetry.observeJobWork(hash, "miner", now) shouldBe null
            telemetry.join(id, false, sample(), qa = true, now = now)
            telemetry.observeJobWork(hash, "miner", now) shouldBe null
            telemetry.leave(id, now)
            telemetry.join(id, false, sample(), now = now)
            telemetry.observeJobWork(hash, "miner", now) shouldBe JobWorkObservation("miner", now, now)
            telemetry.observeJobWork(hash, "miner", now + 1000) shouldBe JobWorkObservation("miner", now, now + 1000)
            telemetry.leave(id, now + 1500)
            telemetry.join(id, false, sample(), now = now + 2000)
            telemetry.observeJobWork(hash, "miner", now + 2000) shouldBe JobWorkObservation("miner", now + 2000, now + 2000)
        } finally { telemetry.shutdown(now + 3000); registry.close() }
    }
})
