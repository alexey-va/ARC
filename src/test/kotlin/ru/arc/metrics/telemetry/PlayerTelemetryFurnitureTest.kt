package ru.arc.metrics.telemetry

import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.arc.core.EventScope
import ru.arc.core.TestEventBus

class PlayerTelemetryFurnitureTest : StringSpec({
    "plugin-spawned furniture without a player is ignored" {
        val event = mockk<FurniturePlaceSuccessEvent>()
        every { event.player as Player? } returns null
        val bus = TestEventBus()
        val scope = EventScope(bus)
        PlayerTelemetryFurniture.register(scope)

        bus.fire(event) shouldBe 1

        verify(exactly = 0) { event.namespacedID }
        scope.unregisterAll()
    }
})
