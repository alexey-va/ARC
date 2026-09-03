package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import org.bukkit.entity.Player
import java.util.UUID

class BukkitLandsUiGatewayTest : StringSpec({
    "selects the freshly resolved land before dispatching a Lands command" {
        val id = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val land = mockk<Land>()
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        every { integration.getLandPlayer(id) } returns landPlayer
        every { landPlayer.lands } returns setOf(land)
        every { land.id } returns 7
        every { land.exists() } returns true
        every { landPlayer.setEditLand(land) } just runs
        every { player.performCommand("lands land delete") } returns true

        val result = BukkitLandsUiGateway(integration)
            .selectAndExecute(player, 7, "lands land delete")

        result shouldBe LandsUiCommandResult.EXECUTED
        verifyOrder {
            landPlayer.setEditLand(land)
            player.performCommand("lands land delete")
        }
    }

    "does not dispatch when the rendered land is no longer available" {
        val id = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        every { integration.getLandPlayer(id) } returns landPlayer
        every { landPlayer.lands } returns emptySet()

        val result = BukkitLandsUiGateway(integration)
            .selectAndExecute(player, 7, "lands land delete")

        result shouldBe LandsUiCommandResult.LAND_UNAVAILABLE
        verify(exactly = 0) { player.performCommand(any()) }
    }
})
