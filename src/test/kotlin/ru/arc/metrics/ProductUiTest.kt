package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ProductUiTest : StringSpec({
    val now = Instant.parse("2026-09-05T10:00:00Z").toEpochMilli()
    val config = ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC"))
    val revision = "0123456789ab"
    fun event(player: String, kind: ProductUiKind, button: String = "_menu", at: Long = now,
              surface: String = "arc:help.root", feature: String? = null) = ProductUiSignal(
        ProductPseudonym.eventId(), "spawn", ProductPseudonym.of(player), at, kind, surface, revision, button, feature)
    @Suppress("UNCHECKED_CAST")
    fun rows(store: ProductInterestStore, at: Long = now) =
        (store.report(at, 7, 100)["ui"] as Map<String, Any?>)["rows"] as List<Map<String, Any?>>

    "UI codec rejects source spoofing malformed identifiers and free text" {
        val valid = event("one", ProductUiKind.CLICK, "rtp")
        val gson = Gson()
        ProductUiCodec.decode(gson.toJson(valid), "spawn", now, 35, gson) shouldBe valid
        ProductUiCodec.decode(gson.toJson(valid), "other", now, 35, gson) shouldBe null
        ProductUiCodec.decode(gson.toJson(valid.copy(surface = "Player Alice balance 120")), "spawn", now, 35, gson) shouldBe null
        ProductUiCodec.decode("{\"kind\":\"CLICK\"}", "spawn", now, 35, gson) shouldBe null
        ProductUiCodec.decode(gson.toJson(valid.copy(durationMillis = -1)), "spawn", now, 35, gson) shouldBe null
        ProductUiCodec.decode(gson.toJson(valid).dropLast(1) + ",\"unknown\":true}", "spawn", now, 35, gson) shouldBe null
        ProductUiCodec.decode("{\"eventId\":\"0123456789abcdef\",\"eventId\":\"0123456789abcdef\",\"source\":\"spawn\",\"player\":\"${ProductPseudonym.of("one")}\",\"occurredAt\":$now,\"kind\":\"CLICK\",\"surface\":\"arc:help.root\",\"revision\":\"$revision\",\"button\":\"rtp\",\"durationMillis\":0}", "spawn", now, 35, gson) shouldBe null
    }

    "render refresh never inflates impressions and blocked clicks are choices without accepted clicks" {
        val events = mutableListOf<Pair<ProductUiKind, String>>()
        val tracker = ProductUiTracker { _, kind, _, button, _, _ -> events += kind to button }
        val view = ProductUiView("arc:help.root", revision, mapOf("rtp" to ProductUiButton(1)))
        tracker.open("player", "visit", view, now)
        tracker.render("player", "visit", view, now + 1)
        tracker.render("player", "visit", view.copy(buttons = view.buttons + ("homes" to ProductUiButton(2))), now + 2)
        tracker.click("player", "visit", view, "rtp", false, now + 3)
        tracker.close("player", "visit", now + 4, censored = false)
        events.count { it.first == ProductUiKind.OPEN } shouldBe 1
        events.count { it.first == ProductUiKind.IMPRESSION } shouldBe 2
        events.count { it.first == ProductUiKind.CLICK } shouldBe 0
        events.count { it.first == ProductUiKind.NO_CHOICE } shouldBe 0
        tracker.open("player", "other", view, now + 5)
        tracker.shutdown(now + 6)
        events.count { it.first == ProductUiKind.NO_CHOICE } shouldBe 0
        events.count { it.first == ProductUiKind.CENSORED } shouldBe 1
    }

    "raw attempts count as a choice while an untouched close is no-choice" {
        val events = mutableListOf<ProductUiKind>()
        val tracker = ProductUiTracker { _, kind, _, _, _, _ -> events += kind }
        val view = ProductUiView("arc:help.root", revision, mapOf("rtp" to ProductUiButton(1)))
        tracker.open("player", "attempt", view, now)
        tracker.attempt("player", "attempt", view, "rtp", now + 1)
        tracker.close("player", "attempt", now + 2, censored = false)
        events.count { it == ProductUiKind.ATTEMPT } shouldBe 1
        events.count { it == ProductUiKind.NO_CHOICE } shouldBe 0

        tracker.open("player", "untouched", view, now + 3)
        tracker.close("player", "untouched", now + 4, censored = false)
        events.count { it == ProductUiKind.NO_CHOICE } shouldBe 1
    }

    "render refresh becomes the authoritative view for later clicks" {
        val seen = mutableListOf<ProductUiView>()
        val tracker = ProductUiTracker { _, kind, view, _, _, _ -> if (kind == ProductUiKind.CLICK) seen += view }
        val first = ProductUiView("arc:help.root", revision, mapOf("rtp" to ProductUiButton(1)))
        val refreshed = first.copy(revision = "abcdef012345", buttons = mapOf("homes" to ProductUiButton(2)))
        tracker.open("player", "visit", first, now)
        tracker.render("player", "visit", refreshed, now + 1)
        tracker.click("player", "visit", refreshed, "homes", true, now + 2)
        seen.single() shouldBe refreshed
    }

    "unique clickers deduplicate across days and servers and survive persistence" {
        val path = Files.createTempDirectory("ui-counts-").resolve("state.json")
        val store = ProductInterestStore.open(path, config, now)
        store.applyUi(event("one", ProductUiKind.IMPRESSION, "rtp"))
        repeat(3) { store.applyUi(event("one", ProductUiKind.CLICK, "rtp")) }
        store.applyUi(event("one", ProductUiKind.IMPRESSION, "rtp", at = now + 86_400_000).copy(source = "survival"))
        store.applyUi(event("two", ProductUiKind.IMPRESSION, "rtp", at = now + 86_400_000))
        val at = now + 86_400_001
        store.flush(at, force = true)
        val restored = ProductInterestStore.open(path, config, at)
        val row = rows(restored, at).single()
        (row["events"] as Map<*, *>)["click"] shouldBe 3L
        (row["uniquePlayers"] as Map<*, *>)["click"] shouldBe 1
        (row["uniquePlayers"] as Map<*, *>)["impression"] shouldBe 2
        row["uniqueClickRate"] shouldBe 0.5
    }

    "attribution requires a matching result and expires after ten minutes" {
        val store = ProductInterestStore.open(Files.createTempDirectory("ui-result-").resolve("state.json"), config, now)
        store.applyUi(event("one", ProductUiKind.CLICK, "rtp", feature = "rtp"))
        store.applyUi(event("one", ProductUiKind.OPEN, surface = "arc:travel", at = now + 1_000))
        fun result(outcome: ProductOutcome, at: Long) = ProductSignal(ProductPseudonym.eventId(), "survival",
            ProductPseudonym.of("one"), at, ProductEventKind.MEANINGFUL_OUTCOME, outcome = outcome)
        store.apply(result(ProductOutcome.HOME_CREATED, now + 2_000))
        store.apply(result(ProductOutcome.RTP_COMPLETE, now + 3_000))
        store.apply(result(ProductOutcome.RTP_COMPLETE, now + 4_000))
        val row = rows(store, now + 4_000).first { it["button"] == "rtp" }
        (row["events"] as Map<*, *>)["result"] shouldBe 1L
        (row["events"] as Map<*, *>)["destination"] shouldBe 1L
        store.applyUi(event("one", ProductUiKind.CLICK, "rtp", at = now + 5_000, feature = "rtp"))
        store.apply(result(ProductOutcome.RTP_COMPLETE, now + 605_001))
        (rows(store, now + 605_001).first { it["button"] == "rtp" }["events"] as Map<*, *>)["result"] shouldBe 1L
    }

    "UI capacity loss is visible and does not grow storage without bounds" {
        val store = ProductInterestStore.open(Files.createTempDirectory("ui-bounds-").resolve("state.json"), config, now)
        repeat(130) { store.applyUi(event("one", ProductUiKind.IMPRESSION, "button$it")) }
        val ui = store.report(now, 1, 100)["ui"] as Map<*, *>
        ui["observedRows"] shouldBe 128
        ui["droppedEvents"] shouldBe 2L
        ui["complete"] shouldBe false
    }
})
