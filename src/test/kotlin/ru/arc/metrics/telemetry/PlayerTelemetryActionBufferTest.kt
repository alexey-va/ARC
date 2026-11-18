package ru.arc.metrics.telemetry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.telemetry.PlayerTelemetryEvent
import java.util.UUID

class PlayerTelemetryActionBufferTest : StringSpec({
    val player = JourneyContext(UUID(0, 1), "Builder", UUID(0, 2).toString(), true,
        JourneyPosition("spawn", 1.0, 64.0, 3.0))

    "UTC windows preserve the actual first and last observations" {
        val events = mutableListOf<PlayerTelemetryEvent>()
        val watermarks = mutableListOf<Long?>()
        val watermarkAtOffer = mutableListOf<Long?>()
        val buffer = PlayerTelemetryActionBuffer("survival", pendingSince = { watermarks += it }) { snapshot ->
            watermarkAtOffer += watermarks.lastOrNull()
            events += snapshot()
        }
        val attrs = mutableMapOf("cancelled" to "false")
        buffer.add(player, "vanilla", "block.break", "minecraft:stone", attrs, 4_810)
        attrs["cancelled"] = "true"
        buffer.add(player, "vanilla", "block.break", "minecraft:stone", mapOf("cancelled" to "false"), 4_982)

        buffer.flushExpired(4_999)
        events shouldBe emptyList()
        buffer.flushExpired(5_000)

        val first = events.single()
        first.occurredAt shouldBe 4_810L
        first.server shouldBe "survival"
        first.playerId shouldBe player.playerId.toString()
        first.playerName shouldBe player.playerName
        first.sessionId shouldBe player.sessionId
        first.qa shouldBe true
        first.source shouldBe "player"
        first.event shouldBe "block.break"
        first.subject shouldBe "minecraft:stone"
        first.world shouldBe "vanilla"
        listOf(first.x, first.y, first.z) shouldBe listOf(null, null, null)
        first.attributes shouldBe mapOf("cancelled" to "false", "count" to "2", "lastAtMs" to "4982")
        first.occurrences shouldBe 2

        buffer.add(player, "vanilla", "block.break", "minecraft:stone", mapOf("cancelled" to "false"), 5_001)
        buffer.add(player, "vanilla", "block.break", "minecraft:stone", mapOf("cancelled" to "false"), 10_011)
        events.map { it.occurredAt } shouldBe listOf(4_810L, 5_001L)
        buffer.flushAll()
        events.last().occurredAt shouldBe 10_011L
        events.last().attributes shouldBe mapOf("cancelled" to "false", "count" to "1")
        watermarkAtOffer shouldBe listOf(1L, 5_000L, 10_000L)
        watermarks shouldBe listOf(1L, null, 5_000L, 10_000L, null)
    }

    "different sessions, worlds, targets and action details do not merge" {
        val events = mutableListOf<PlayerTelemetryEvent>()
        val buffer = PlayerTelemetryActionBuffer("survival") { events += it() }
        val click = mapOf("rawSlot" to "12", "clickType" to "LEFT", "action" to "PICKUP_ALL",
            "material" to "minecraft:stone", "cancelled" to "false")
        buffer.add(player, "vanilla", "inventory.click", "chest", click, 20_001)
        buffer.add(player.copy(position = player.position.copy(x = 10.0)), "vanilla", "inventory.click", "chest", click, 20_007)
        buffer.add(player, "vanilla", "inventory.click", "chest", click + ("cancelled" to "true"), 20_008)
        buffer.add(player, "vanilla", "inventory.click", "chest", click + ("rawSlot" to "13"), 20_009)
        buffer.add(player, "vanilla", "inventory.click", "barrel", click, 20_010)
        buffer.add(player, "mining", "inventory.click", "chest", click, 20_011)
        buffer.add(player.copy(sessionId = UUID(0, 3).toString()), "vanilla", "inventory.click", "chest", click, 20_012)
        buffer.add(player.copy(playerId = UUID(0, 4)), "vanilla", "inventory.click", "chest", click, 20_013)

        buffer.flushAll()
        events.map { it.occurredAt } shouldBe listOf(20_001L, 20_008L, 20_009L, 20_010L, 20_011L, 20_012L, 20_013L)
        events.map { it.occurrences } shouldBe listOf(2, 1, 1, 1, 1, 1, 1)
        events.first().attributes.filterKeys { it in click } shouldBe click
    }

    "overflow and explicit lifecycle drains keep every occurrence once" {
        val events = mutableListOf<PlayerTelemetryEvent>()
        val buffer = PlayerTelemetryActionBuffer("survival", capacity = 2) { events += it() }
        val other = player.copy(playerId = UUID(0, 3), sessionId = UUID(0, 4).toString())
        buffer.add(player, "vanilla", "block.place", "minecraft:stone", emptyMap(), 20_001)
        buffer.add(player, "vanilla", "block.place", "minecraft:stone", emptyMap(), 20_002)
        buffer.add(other, "vanilla", "block.place", "minecraft:dirt", emptyMap(), 20_003)
        val interact = mapOf("action" to "RIGHT_CLICK_BLOCK", "useBlock" to "ALLOW", "useItem" to "DENY")
        buffer.add(player, "vanilla", "interact", "minecraft:chest", interact, 20_004)
        buffer.add(player, "vanilla", "interact", "minecraft:chest", interact, 20_005)

        events.single().attributes.getValue("count") shouldBe "2"
        buffer.flushPlayer(player.playerId)
        events.map { it.subject } shouldBe listOf("minecraft:stone", "minecraft:chest")
        buffer.flushAll()
        buffer.flushAll()
        events.map { it.playerId } shouldBe listOf(player.playerId.toString(), player.playerId.toString(), other.playerId.toString())
        events.map { it.occurrences } shouldBe listOf(2, 2, 1)
    }
})
