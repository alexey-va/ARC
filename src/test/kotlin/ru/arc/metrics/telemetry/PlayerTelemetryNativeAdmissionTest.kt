package ru.arc.metrics.telemetry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import ru.arc.metrics.ProductUiKind
import ru.arc.metrics.ProductUiView
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetryStore
import java.util.UUID

class PlayerTelemetryNativeAdmissionTest : StringSpec({
    "native captures require a tracked human session while provider records may be offline" {
        val moduleClass = PlayerTelemetryModule::class.java
        val storeField = moduleClass.getDeclaredField("store").apply { isAccessible = true }
        val trackerField = moduleClass.getDeclaredField("tracker").apply { isAccessible = true }
        val contextsField = moduleClass.getDeclaredField("contexts").apply { isAccessible = true }
        val oldStore = storeField.get(PlayerTelemetryModule)
        val oldTracker = trackerField.get(PlayerTelemetryModule)
        @Suppress("UNCHECKED_CAST")
        val contexts = contextsField.get(PlayerTelemetryModule) as MutableMap<UUID, JourneyContext>
        val oldContexts = contexts.toMap()
        val captured = mutableListOf<PlayerTelemetryEvent>()
        val store = mockk<PlayerTelemetryStore>()
        every { store.offer(any()) } answers {
            captured += firstArg<PlayerTelemetryEvent>()
            true
        }
        val trackerEvents = mutableListOf<String>()
        val playerId = UUID.randomUUID()
        val offlineProducerId = UUID.randomUUID()

        try {
            contexts.clear()
            storeField.set(PlayerTelemetryModule, store)
            trackerField.set(PlayerTelemetryModule, PlayerJourneyTracker(
                idleAfterMillis = 60_000,
                positionIntervalMillis = 15_000,
                emit = { _, event, _, _ -> trackerEvents += event },
                publishContext = { id, context -> if (context == null) contexts.remove(id) else contexts[id] = context },
            ))

            val npc = mockk<Player>()
            every { npc.hasMetadata("NPC") } returns true
            PlayerTelemetryModule.join(npc, resumed = true)
            contexts.containsKey(playerId) shouldBe false
            trackerEvents shouldBe emptyList()

            val untrackedPlayer = mockk<Player>()
            every { untrackedPlayer.uniqueId } returns playerId
            PlayerTelemetryModule.recordNative(playerId, "player", "teleport") shouldBe false
            PlayerTelemetryModule.action(untrackedPlayer, "interact") shouldBe false
            PlayerTelemetryModule.ui(
                playerId.toString(), "visit", ProductUiKind.OPEN,
                ProductUiView("arc:help.root", "0123456789ab", emptyMap()), "", 0, System.currentTimeMillis(),
            )
            captured shouldBe emptyList()

            PlayerTelemetryModule.record(offlineProducerId, "arcfarms", "farm.observed") shouldBe true
            captured.single().playerId shouldBe offlineProducerId.toString()
            captured.single().sessionId shouldBe null

            val context = JourneyContext(
                playerId, "Player", UUID.randomUUID().toString(), false,
                JourneyPosition("spawn", 0.0, 64.0, 0.0),
            )
            contexts[playerId] = context
            PlayerTelemetryModule.recordNative(playerId, "player", "teleport") shouldBe true
            captured.last().sessionId shouldBe context.sessionId
        } finally {
            contexts.clear()
            contexts.putAll(oldContexts)
            storeField.set(PlayerTelemetryModule, oldStore)
            trackerField.set(PlayerTelemetryModule, oldTracker)
        }
    }
})
