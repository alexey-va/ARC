package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ProductUiTest : StringSpec({
    val now = Instant.parse("2026-09-05T10:00:00Z").toEpochMilli()
    val config = ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC"))
    val revision = "0123456789ab"
    fun observation(owner: String, surface: String, player: String, visit: String, button: String) = mapOf(
        "protocol" to 1, "owner" to owner, "surface" to surface, "playerId" to player, "visitId" to visit,
        "revision" to revision, "buttons" to mapOf(button to 0),
    )
    fun id() = UUID.randomUUID().toString()
    fun event(player: String, kind: ProductUiKind, button: String = "_menu", at: Long = now,
              surface: String = "arc:help.root", feature: String? = null) = ProductUiSignal(
        ProductPseudonym.eventId(), "spawn", ProductPseudonym.of(player), at, kind, surface, revision, button, feature)
    @Suppress("UNCHECKED_CAST")
    fun rows(store: ProductInterestStore, at: Long = now) =
        (store.report(at, 7, 100)["ui"] as Map<String, Any?>)["rows"] as List<Map<String, Any?>>

    "native rendered buttons exclude filler and use the visible else button at an overlapping slot" {
        val visible = RenderedButton("locked_help")
        renderedZMenuButton(RenderedItem(null), RenderedButton::class.java) shouldBe null
        renderedZMenuButton(RenderedItem(RenderedClick(visible)), RenderedButton::class.java) shouldBe visible
    }

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
        val tracker = ProductUiTracker { _, _, kind, _, button, _, _ -> events += kind to button }
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
        tracker.shutdown(now + 6, "shutdown")
        events.count { it.first == ProductUiKind.NO_CHOICE } shouldBe 0
        events.count { it.first == ProductUiKind.CENSORED } shouldBe 1
    }

    "raw attempts count as a choice while an untouched close is no-choice" {
        val events = mutableListOf<ProductUiKind>()
        val tracker = ProductUiTracker { _, _, kind, _, _, _, _ -> events += kind }
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
        val tracker = ProductUiTracker { _, _, kind, view, _, _, _ -> if (kind == ProductUiKind.CLICK) seen += view }
        val first = ProductUiView("arc:help.root", revision, mapOf("rtp" to ProductUiButton(1)))
        val refreshed = first.copy(revision = "abcdef012345", buttons = mapOf("homes" to ProductUiButton(2)))
        tracker.open("player", "visit", first, now)
        tracker.render("player", "visit", refreshed, now + 1)
        tracker.click("player", "visit", refreshed, "homes", true, now + 2)
        seen.single() shouldBe refreshed
    }

    "menu history snapshots a changed frame once and links attempts to that frame and the destination" {
        val journal = mutableListOf<ProductMenuEvent>()
        val tracker = ProductUiTracker({ journal += it }) { _, _, _, _, _, _, _ -> }
        val screen = ProductUiView("arc:help.root", revision,
            mapOf("warps" to ProductUiButton(1), "homes" to ProductUiButton(0)))
        val nextPage = screen.copy(details = mapOf("pageIndex" to "1", "disabledSlots" to "2"),
            visibleSlots = mapOf(0 to "homes", 1 to "region:mounts", 2 to "region:mounts"))
        tracker.open("player", "first", screen, now)
        tracker.render("player", "first", screen, now + 1)
        tracker.render("player", "first", nextPage, now + 2)
        tracker.click("player", "first", nextPage, "homes", true, now + 3, "click-id")
        tracker.open("player", "second", screen.copy(surface = "arc:help.homes",
            details = mapOf("triggerAttemptId" to "click-id")), now + 4)

        journal.map { it.event } shouldBe listOf("menu.view", "menu.view", "menu.action", "menu.end", "menu.view")
        journal.first().details["buttons"] shouldBe "homes=0,warps=1"
        journal[1].details["buttons"] shouldBe "homes=0,region:mounts=1,region:mounts=2"
        journal[1].details["disabledSlots"] shouldBe "2"
        journal[2].details["frame"] shouldBe "1"
        journal[2].details["disabledSlots"] shouldBe null
        journal[2].actionId shouldBe "click-id"
        journal[3].details["reason"] shouldBe "replaced"
        journal[4].actionId shouldBe "click-id"
        journal[4].details["previousVisitId"] shouldBe "first"

        tracker.shutdown(now + 5, "reload")
        tracker.shutdown(now + 6, "shutdown")
        journal.size shouldBe 6
        journal.last().event shouldBe "menu.end"
        journal.last().details["reason"] shouldBe "reload"
    }

    "legacy ArcFarms open uses only an admitted same-player dispatch and keeps explicit attribution" {
        val journal = mutableListOf<ProductMenuEvent>()
        val listener = ProductUiListener(mockk(), null) { journal += it }
        val player = id()
        val otherPlayer = id()
        val attempt = id()
        val explicit = id()
        val source = observation("ARC", "help.category.activities", player, id(), "command_farms")
        val marker = source - "buttons" + mapOf("attemptId" to attempt, "started" to true)
        fun receive(payload: Map<String, Any>, phase: String) = listener.receive(payload + ("phase" to phase), now)
        fun openLegacy(viewer: String) = observation("ArcFarms", "farms.main", viewer, id(), "farm")

        receive(source, "open")
        receive(source + mapOf("attemptId" to attempt, "button" to "command_farms"), "click")
        receive(marker - "started", "dispatch_start")
        receive(openLegacy(player), "open")
        receive(marker, "dispatch_start")
        receive(openLegacy(otherPlayer), "open")

        val nested = marker + mapOf("attemptId" to id(), "playerId" to otherPlayer)
        receive(nested, "dispatch_start")
        receive(openLegacy(player), "open")
        receive(nested, "dispatch_end")
        val speculative = marker + mapOf("attemptId" to id(), "started" to false)
        receive(speculative, "dispatch_start")
        receive(openLegacy(player), "open")
        receive(speculative, "dispatch_end")

        val destination = openLegacy(player)
        receive(destination, "open")
        receive(destination, "open")
        receive(openLegacy(player) + ("triggerAttemptId" to explicit), "open")
        receive(source + mapOf("attemptId" to attempt, "outcome" to "requested", "target" to "command:farms"), "result")
        receive(marker, "dispatch_end")
        receive(openLegacy(player), "open")

        val views = journal.filter { it.event == "menu.view" && it.surface == "arcfarms:farms.main" }
        views.map { it.actionId } shouldBe listOf(null, null, null, null, attempt, explicit, null)
        views.filter { it.actionId == attempt }.single().details["buttons"] shouldBe "farm=0"
        journal.filter { it.event == "menu.action" }.single().actionId shouldBe attempt
        val result = journal.filter { it.event == "menu.result" }.single()
        result.actionId shouldBe attempt
        result.details shouldBe mapOf("target" to "command:farms", "outcome" to "requested")
        journal.any { it.event.startsWith("dispatch_") } shouldBe false
    }

    "generated history back reports only its first confirmed destination while its inner scope is active" {
        val journal = mutableListOf<ProductMenuEvent>()
        val listener = ProductUiListener(mockk(), null) { journal += it }
        val player = id()
        val attempt = id()
        val sourceVisit = id()
        val source = observation("ARC", "help.travel", player, sourceVisit, "back")
        val marker = source - "buttons" + mapOf("attemptId" to attempt, "started" to true)
        val footer = marker + ("historyFooter" to true)
        fun receive(payload: Map<String, Any>, phase: String) = listener.receive(payload + ("phase" to phase), now)
        fun open(surface: String) = receive(observation("ARC", surface, player, id(), "_close"), "open")

        receive(source, "open")
        receive(source + mapOf("attemptId" to attempt, "button" to "back"), "click")
        receive(marker, "dispatch_start")
        open("help.dismissal")
        receive(footer, "dispatch_start")
        receive(observation("ARC", "help.other", player, id(), "_close") + ("triggerAttemptId" to id()), "open")
        open("help.root")
        open("help.followup")
        receive(footer, "dispatch_end")
        open("help.dismissal_late")
        receive(marker, "dispatch_end")
        open("help.unrelated")

        val result = journal.filter { it.event == "menu.result" }.single()
        result.visit shouldBe sourceVisit
        result.surface shouldBe "arc:help.travel"
        result.actionId shouldBe attempt
        result.details shouldBe mapOf("outcome" to "succeeded", "reason" to "footer_back", "target" to "arc:help.root")
        journal.single { it.event == "menu.view" && it.surface == "arc:help.dismissal_late" }.actionId shouldBe attempt
        journal.single { it.event == "menu.view" && it.surface == "arc:help.unrelated" }.actionId shouldBe null
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

    "snapshot and report sort rows with and without view counters after persistence" {
        val path = Files.createTempDirectory("ui-mixed-counts-").resolve("state.json")
        val store = ProductInterestStore.open(path, config, now)
        store.applyUi(event("one", ProductUiKind.CLOSE, surface = "arc:a.closed")) shouldBe true
        store.applyUi(event("two", ProductUiKind.OPEN, surface = "arc:z.opened")) shouldBe true

        fun verifyCounts(candidate: ProductInterestStore) {
            rows(candidate).map { it["surface"] } shouldBe listOf("arc:z.opened", "arc:a.closed")
            candidate.snapshot(now, "network").isNotEmpty() shouldBe true
        }
        verifyCounts(store)
        store.flush(now, force = true) shouldBe true
        verifyCounts(ProductInterestStore.open(path, config, now))
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

private class RenderedButton(val name: String)
private class RenderedClick(private val button: RenderedButton)
private class RenderedItem(private val onClick: Any?)
