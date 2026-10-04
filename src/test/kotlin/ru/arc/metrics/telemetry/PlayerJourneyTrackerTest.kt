package ru.arc.metrics.telemetry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PlayerJourneyTrackerTest : StringSpec({
    "idle boundaries, movement delay and final durations are explicit and conserve session time" {
        val events = mutableListOf<Triple<String, Long, Map<String, String>>>()
        val contexts = mutableMapOf<UUID, JourneyContext?>()
        val tracker = PlayerJourneyTracker(60_000, 15_000, { _, event, at, data -> events += Triple(event, at, data) },
            { player, context -> contexts[player] = context })
        val player = UUID.randomUUID()
        val origin = JourneyPosition("spawn", 0.0, 64.0, 0.0)
        tracker.join(player, "Player", false, origin, 1_000, false)
        tracker.sample(player, origin, 91_000)
        events.single { it.first == "activity.idle" }.second shouldBe 61_000
        tracker.sample(player, origin.copy(x = 3.0), 101_000)
        events.single { it.first == "activity.first_movement" }.third["sinceJoinMs"] shouldBe "100000"
        events.single { it.first == "activity.resume" }.third["idleDurationMs"] shouldBe "40000"
        tracker.leave(player, 111_000, "quit", false)
        val end = events.last().third
        end["durationMs"] shouldBe "110000"
        end["activeMs"] shouldBe "70000"
        end["idleMs"] shouldBe "40000"
        contexts[player] shouldBe null
    }

    "teleports do not count as first movement and reload closes are censored" {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val tracker = PlayerJourneyTracker(60_000, 15_000, { _, event, _, data -> events += event to data }, { _, _ -> })
        val player = UUID.randomUUID()
        tracker.join(player, "Player", true, JourneyPosition("spawn", 0.0, 64.0, 0.0), 1_000, true)
        val destination = JourneyPosition("survival", 4_000.0, 70.0, -100.0)
        tracker.relocate(player, destination, 2_000)
        tracker.sample(player, destination, 3_000)
        tracker.close(4_000, "reload")
        events.first().first shouldBe "session.resume"
        events.none { it.first == "activity.first_movement" } shouldBe true
        events.last().second["censored"] shouldBe "true"
        events.last().second["sampledDistance"] shouldBe "0"
    }
})
