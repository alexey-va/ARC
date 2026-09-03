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
import me.angeschossen.lands.api.applicationframework.util.ULID
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
        val landUlid = mockk<ULID>()
        val landId = "01KLAND"
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        every { integration.getLandPlayer(id) } returns landPlayer
        every { landPlayer.lands } returns setOf(land)
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns landId
        every { land.exists() } returns true
        every { landPlayer.setEditLand(land) } just runs
        every { player.performCommand("lands land delete") } returns true

        val result = BukkitLandsUiGateway(integration)
            .selectAndExecute(player, landId, "lands land delete")

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
        every { landPlayer.lands } returns emptySet<Land>()

        val result = BukkitLandsUiGateway(integration)
            .selectAndExecute(player, "01KLAND", "lands land delete")

        result shouldBe LandsUiCommandResult.LAND_UNAVAILABLE
        verify(exactly = 0) { player.performCommand(any()) }
    }

    "reports and explicitly changes the selected settlement" {
        val id = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val land = mockk<Land>()
        val landUlid = mockk<ULID>()
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        every { integration.getLandPlayer(id) } returns landPlayer
        every { landPlayer.lands } returns setOf(land)
        every { landPlayer.editLand } returns land
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "01KLAND"
        every { land.exists() } returns true
        every { land.name } returns "Берег"
        every { land.ownerUID } returns id
        every { land.chunksAmount } returns 4
        every { land.maxChunks } returns 64
        every { land.maxMembers } returns 8
        every { land.balance } returns 0.0
        every { land.trustedPlayers } returns emptyMap()
        every { landPlayer.setEditLand(land) } just runs

        val gateway = BukkitLandsUiGateway(integration)
        gateway.lands(player).single().selected shouldBe true
        gateway.select(player, "01KLAND") shouldBe true

        verify { landPlayer.setEditLand(land) }
    }
})
