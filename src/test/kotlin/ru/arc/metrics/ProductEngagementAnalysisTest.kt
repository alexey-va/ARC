package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ProductEngagementAnalysisTest : StringSpec({
    val zone = ZoneId.of("Europe/Moscow")
    fun time(value: String) = Instant.parse(value).toEpochMilli()
    val start = time("2026-08-01T09:00:00Z")
    fun day(at: Long, visited: Boolean = false, first: Map<String, Long> = emptyMap(), last: Map<String, Long> = first) =
        ProductEngagementDay(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), visited, first, last)
    fun player(joined: Long = start, observations: Map<String, Long> = emptyMap(), returns: List<Long> = emptyList()) =
        ProductEngagementPlayer(joined, listOf(day(joined, true, observations)) + returns.map { day(it, true) })

    "early behavior is fixed before return and each group includes early leavers" {
        val users = listOf(
            player(observations = mapOf("rtp|result" to start + 900_000), returns = listOf(start + 86_400_000)),
            player(),
            player(observations = mapOf("rtp|result" to start + 3_600_000)),
            player(joined = start - 1, observations = mapOf("rtp|result" to start)),
        )
        val report = ProductEngagementAnalysis(users, start + 2 * 86_400_000, zone, start, 35)
        val with30 = report.earlyRetention.single { it.within == "30m" && it.horizon == "d1" && it.group == "with" }
        with30.counts shouldBe ProductRetentionCounts(1, 1)
        report.earlyRetention.single { it.within == "30m" && it.horizon == "d1" && it.group == "without" }.counts shouldBe ProductRetentionCounts(2, 0)
        report.earlyRetention.filter { it.horizon == "d7" }.all { it.counts.eligiblePlayers == 0 } shouldBe true
        report.earlyRetention.single { it.within == "10m" && it.horizon == "d1" && it.group == "without" }.counts shouldBe ProductRetentionCounts(3, 1)
    }
    "target calendar day must close and zero denominator has no rate" {
        val users = listOf(player(observations = mapOf("rtp|result" to start)))
        val before = ProductEngagementAnalysis(users, time("2026-08-02T20:59:59Z"), zone, start, 35)
        before.earlyRetention.first().counts.returnRate shouldBe null
        val after = ProductEngagementAnalysis(users, time("2026-08-02T21:00:00Z"), zone, start, 35)
        after.earlyRetention.first { it.horizon == "d1" && it.group == "with" }.counts.eligiblePlayers shouldBe 1
    }
    "repeated selection is separate from results and same-day spam is not repetition" {
        val at = start + 86_400_000
        val users = listOf(
            ProductEngagementPlayer(start, listOf(day(start, true, mapOf("rtp|result" to start, "rtp|selected" to start)),
                day(at, true, mapOf("rtp|selected" to at)))),
            ProductEngagementPlayer(start, listOf(day(start, true, mapOf("rtp|result" to start), mapOf("rtp|result" to start + 3_600_000)))),
            ProductEngagementPlayer(start, listOf(day(start, true, mapOf("rtp|result" to start)), day(at, true, mapOf("rtp|result" to at)))),
        )
        val report = ProductEngagementAnalysis(users, start + 8 * 86_400_000, zone, start, 35)
        report.repeatUse.single { it.stage == "result" } shouldBe ProductRepeatRow("rtp", "result", 3, 3, 1)
        report.repeatUse.single { it.stage == "selected" } shouldBe ProductRepeatRow("rtp", "selected", 3, 1, 1)
    }
    "cross-midnight first thirty minutes are not counted as later repetition" {
        val joined = time("2026-08-01T20:50:00Z")
        val early = joined + 20 * 60_000
        val late = joined + 60 * 60_000
        val users = listOf(
            ProductEngagementPlayer(joined, listOf(day(joined, true, mapOf("rtp|result" to joined)), day(early, true, mapOf("rtp|result" to early)))),
            ProductEngagementPlayer(joined, listOf(day(joined, true, mapOf("rtp|result" to joined)), day(early, true, mapOf("rtp|result" to early), mapOf("rtp|result" to late)))),
        )
        ProductEngagementAnalysis(users, joined + 8 * 86_400_000, zone, joined, 35).repeatUse.single().repeatedPlayers shouldBe 1
    }
    "menu comparison excludes mixed early versions and ignores later versions" {
        val a = "menu|arc:main|0123456789ab"
        val b = "menu|arc:main|abcdef012345"
        val users = listOf(player(observations = mapOf(a to start)), player(observations = mapOf(a to start, b to start + 1)),
            player(observations = mapOf(a to start, b to start + 1_800_001), returns = listOf(start + 86_400_000)))
        val rows = ProductEngagementAnalysis(users, start + 8 * 86_400_000, zone, start, 35).menuRetention
        rows.single { it.revision == "0123456789ab" && it.horizon == "d1" }.let {
            it.counts shouldBe ProductRetentionCounts(2, 1)
            it.mixedVersionPlayers shouldBe 1
        }
        rows.single { it.revision == "abcdef012345" && it.horizon == "d1" }.counts shouldBe ProductRetentionCounts(0, 0)
    }
    "many menu revisions stay bounded and loss is explicit" {
        val path = Files.createTempDirectory("engagement-bound-").resolve("state.json")
        val store = ProductInterestStore.open(path, ProductInterestConfig(networkEnabled = false, zoneId = zone), start)
        repeat(500) { index ->
            store.applyUi(ProductUiSignal(ProductPseudonym.eventId(), "spawn", ProductPseudonym.of("one"), start,
                ProductUiKind.OPEN, "arc:main", index.toString(16).padStart(12, '0')))
        }
        store.flush(start, force = true)
        val json = Gson().fromJson(Files.readString(path), com.google.gson.JsonObject::class.java)
        val day = json.getAsJsonArray("players")[0].asJsonObject.getAsJsonArray("days")[0].asJsonObject
        day.getAsJsonObject("engagementFirst").size() shouldBe 128
        day.getAsJsonObject("engagementLast").size() shouldBe 128
        (store.report(start, 35, 100)["engagement"] as Map<*, *>)["complete"] shouldBe false
    }
    "future observation boundary is repaired durably without new events" {
        val path = Files.createTempDirectory("engagement-clock-").resolve("state.json")
        val config = ProductInterestConfig(networkEnabled = false, zoneId = zone)
        Files.writeString(path, """{"version":1,"savedAt":$start,"engagementObservationStartedAt":${start + 1000},"players":[]}""")
        val store = ProductInterestStore.open(path, config, start)
        store.flush(start + 30_000) shouldBe true
        val restored = ProductInterestStore.open(path, config, start + 30_100)
        (restored.report(start + 30_100, 35, 100)["engagement"] as Map<*, *>)["observationStartedAt"] shouldBe start
    }
    "store persists exact observations and establishes boundary for old data" {
        val path = Files.createTempDirectory("engagement-").resolve("state.json")
        val config = ProductInterestConfig(networkEnabled = false, zoneId = zone)
        val store = ProductInterestStore.open(path, config, start)
        fun signal(at: Long, kind: ProductEventKind, outcome: ProductOutcome? = null) = ProductSignal(
            ProductPseudonym.eventId(), "spawn", ProductPseudonym.of("one"), at, kind, outcome = outcome)
        store.apply(signal(start, ProductEventKind.FIRST_JOIN))
        store.apply(signal(start + 600_000, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.RTP_COMPLETE))
        store.apply(signal(start + 1, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.RTP_COMPLETE)).changed shouldBe true
        store.apply(signal(start + 700_000, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.RTP_COMPLETE)).changed shouldBe true
        store.apply(signal(start + 86_400_000, ProductEventKind.SESSION_START))
        val now = start + 2 * 86_400_000
        store.flush(now, force = true)
        val restored = ProductInterestStore.open(path, config, now)
        Gson().toJson(restored.report(now, 35, 100)["engagement"]) shouldBe Gson().toJson(store.report(now, 35, 100)["engagement"])
        val json = Gson().fromJson(Files.readString(path), com.google.gson.JsonObject::class.java)
        json.remove("engagementObservationStartedAt")
        Files.writeString(path, Gson().toJson(json))
        val old = ProductInterestStore.open(path, config, now)
        (old.report(now, 35, 100)["engagement"] as Map<*, *>)["observationStartedAt"] shouldBe now
        (old.report(now, 35, 100)["engagement"] as Map<*, *>)["earlyRetention"] shouldBe emptyList<ProductEarlyRetentionRow>()
    }
})
