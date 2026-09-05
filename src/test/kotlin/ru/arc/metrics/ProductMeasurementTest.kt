package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ProductMeasurementTest : StringSpec({
    val joined = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
    val day = 86_400_000L
    fun store() = ProductInterestStore.open(
        Files.createTempDirectory("product-measurement-").resolve("state.json"),
        ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC")), joined,
    )
    fun event(player: String, at: Long, kind: ProductEventKind, outcome: ProductOutcome? = null,
              detail: ProductDetail? = null) = ProductSignal(
        ProductPseudonym.eventId(), "survival", ProductPseudonym.of(player), at, kind,
        path = outcome?.path ?: ProductPath.NONE, outcome = outcome, detail = detail,
    )
    @Suppress("UNCHECKED_CAST")
    fun rows(report: Map<String, Any?>, key: String) = report[key] as List<Map<String, Any?>>

    "activation reports mature observation budgets and conditional latency without requiring menus" {
        val store = store()
        store.apply(event("fast", joined, ProductEventKind.FIRST_JOIN))
        store.apply(event("fast", joined + 120_000, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.BUILDING_THRESHOLD))
        store.apply(event("unreached", joined, ProductEventKind.FIRST_JOIN))
        store.apply(event("too-new", joined + 599_000, ProductEventKind.FIRST_JOIN))
        val activation = rows(store.report(joined + 600_000, 7, 20), "activation")
        activation[0]["eligiblePlayers"] shouldBe 2
        activation[0]["reachedPlayers"] shouldBe 1
        activation[0]["withoutOutcomePlayers"] shouldBe 1
        activation[0]["medianSecondsAmongReached"] shouldBe 120.0
        activation[1]["eligiblePlayers"] shouldBe 0
        activation[1]["medianSecondsAmongReached"] shouldBe null
    }

    "D7 is an exact closed day and W1 retains the separate weekly return measure" {
        val store = store()
        listOf("day-one", "day-seven", "midnight-only").forEach {
            store.apply(event(it, joined, ProductEventKind.FIRST_JOIN))
        }
        store.apply(event("day-one", joined + day, ProductEventKind.SESSION_START))
        store.apply(event("midnight-only", joined + day, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.BUILDING_THRESHOLD))
        store.apply(event("day-seven", joined + 7 * day, ProductEventKind.SESSION_START))
        rows(store.report(joined + 7 * day, 28, 20), "retention")[1]["eligiblePlayers"] shouldBe 0
        val retention = rows(store.report(joined + 8 * day, 28, 20), "retention")
        retention.map { it["eligiblePlayers"] } shouldBe listOf(3, 3, 3)
        retention.map { it["returnedPlayers"] } shouldBe listOf(1, 1, 2)
    }

    "backend quits are separate from network exits and preserve the trail across midnight" {
        val store = store()
        val midnight = Instant.parse("2026-08-17T00:00:00Z").toEpochMilli()
        store.apply(event("traveler", joined, ProductEventKind.FIRST_JOIN))
        store.apply(event("traveler", midnight - 10_000, ProductEventKind.DETAIL,
            detail = ProductDetail(ProductDetailType.WORLD, "vanilla")))
        store.apply(event("traveler", midnight - 5_000, ProductEventKind.MEANINGFUL_OUTCOME, ProductOutcome.BUILDING_THRESHOLD))
        store.apply(event("traveler", midnight - 1_000, ProductEventKind.SESSION_END).copy(
            exit = ProductExitContext(stage = ProductExitStage.ENGAGED)))
        rows(store.report(midnight, 7, 20), "networkExitContexts").size shouldBe 0
        store.apply(event("traveler", midnight + 1_000, ProductEventKind.DETAIL,
            detail = ProductDetail(ProductDetailType.CONNECTION, ProductConnection.DISCONNECT_ACTIVE.label)))
        val report = store.report(midnight + 2_000, 7, 20)
        rows(report, "exitContexts").size shouldBe 1
        val exit = rows(report, "networkExitContexts").single()
        exit["stage"] shouldBe "engaged"
        exit["world"] shouldBe "vanilla"
        exit["connection"] shouldBe "disconnect_active"
        exit["trail"] shouldBe listOf("world=vanilla", "outcome=building_threshold")
        val points = store.snapshot(midnight + 2_000, "network")
        points.single { it.name == "arc_product_network_exits" && it.tags["window"] == "7d" && it.tags["stage"] == "engaged" }.value shouldBe 1.0
    }

    "known capacity loss cannot be presented as complete" {
        val store = ProductInterestStore.open(Files.createTempDirectory("product-capacity-").resolve("state.json"),
            ProductInterestConfig(networkEnabled = false, maxTrackedPlayers = 1), joined)
        store.apply(event("one", joined, ProductEventKind.FIRST_JOIN))
        store.apply(event("two", joined + 1, ProductEventKind.FIRST_JOIN))
        store.report(joined + 2, 1, 20)["complete"] shouldBe false
    }
})
