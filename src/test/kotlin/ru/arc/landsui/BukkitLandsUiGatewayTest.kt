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
import me.angeschossen.lands.api.player.Selection
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID

class BukkitLandsUiGatewayTest : StringSpec({
    afterTest { PlayerManager.readMessage("[]") }

    "suggests local and proxy-wide players once by UUID and falls back to local players" {
        MockBukkitTestRuntime.open().use { runtime ->
            val local = runtime.server.addPlayer("LocalPlayer")
            val remoteId = UUID.randomUUID()
            PlayerManager.readMessage(
                """[
                    {"username":"RenamedOnNetwork","server":"spawn","uuid":"${local.uniqueId}","joinTime":1},
                    {"username":"RemotePlayer","server":"survival","uuid":"$remoteId","joinTime":2}
                ]""".trimIndent(),
            )

            val gateway = BukkitLandsUiGateway(mockk())
            val players = gateway.onlinePlayers()
            players.size shouldBe 2
            players.associateBy(LandsUiPlayer::id) shouldBe mapOf(
                local.uniqueId to LandsUiPlayer(local.uniqueId, "LocalPlayer"),
                remoteId to LandsUiPlayer(remoteId, "RemotePlayer"),
            )

            PlayerManager.readMessage("[]")
            gateway.onlinePlayers() shouldBe listOf(LandsUiPlayer(local.uniqueId, "LocalPlayer"))
        }
    }

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
        every { landPlayer.selection } returns null
        every { player.performCommand("lands unclaim") } returns true

        val result = BukkitLandsUiGateway(integration)
            .unclaimCurrent(player, landId)

        result shouldBe LandsUiCommandResult.EXECUTED
        verifyOrder {
            landPlayer.setEditLand(land)
            player.performCommand("lands unclaim")
        }
    }

    "does not dispatch native unclaim while a selection is active" {
        val id = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val landPlayer = mockk<LandPlayer>()
        val land = mockk<Land>()
        val landUlid = mockk<ULID>()
        val selection = mockk<Selection>()
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        every { integration.getLandPlayer(id) } returns landPlayer
        every { landPlayer.lands } returns setOf(land)
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "01KLAND"
        every { land.exists() } returns true
        every { landPlayer.selection } returns selection
        every { player.performCommand(any()) } returns true

        BukkitLandsUiGateway(integration).unclaimCurrent(player, "01KLAND") shouldBe LandsUiCommandResult.ACTIVE_SELECTION
        verify(exactly = 0) { player.performCommand(any()) }
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
            .selectAndExecute(player, "01KLAND", "lands delete")

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
        every { land.trustedPlayers } returns emptySet<java.util.UUID>()
        every { landPlayer.setEditLand(land) } just runs

        val gateway = BukkitLandsUiGateway(integration)
        gateway.lands(player).single().selected shouldBe true
        gateway.select(player, "01KLAND") shouldBe true

        verify { landPlayer.setEditLand(land) }
    }

    "inspects the land at the current chunk without requiring membership" {
        val playerId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val integration = mockk<LandsIntegration>()
        val land = mockk<Land>()
        val landUlid = mockk<ULID>()
        val player = mockk<Player>()
        val location = mockk<Location>()
        val world = mockk<World>()
        every { player.uniqueId } returns playerId
        every { player.location } returns location
        every { location.world } returns world
        every { location.blockX } returns 48
        every { location.blockZ } returns -17
        every { integration.getLandByUnloadedChunk(world, 3, -2) } returns land
        every { land.exists() } returns true
        every { land.ulid } returns landUlid
        every { landUlid.toString() } returns "01KFOREIGN"
        every { land.name } returns "Чужой приват"
        every { land.ownerUID } returns ownerId
        every { land.chunksAmount } returns 7
        every { land.maxChunks } returns 64
        every { land.trustedPlayers } returns setOf(memberId)
        every { land.maxMembers } returns 12
        every { land.balance } returns 50.0

        val inspected = BukkitLandsUiGateway(integration).inspectedLand(player)

        inspected?.id shouldBe "01KFOREIGN"
        inspected?.ownerId shouldBe ownerId
        inspected?.memberIds shouldBe setOf(ownerId, memberId)
        inspected?.selected shouldBe false
        verify(exactly = 0) { integration.getLandPlayer(playerId) }
    }

    "admin menu handoffs select the current foreign land before running native commands" {
        listOf(
            LandsUiAdminAction.MENU to "lands.command.menu",
            LandsUiAdminAction.MEMBERS to "lands.command.member.menu",
        ).forEach { (action, commandPermission) ->
            val playerId = UUID.randomUUID()
            val integration = mockk<LandsIntegration>()
            val landPlayer = mockk<LandPlayer>()
            val land = mockk<Land>()
            val landUlid = mockk<ULID>()
            val player = mockk<Player>()
            val location = mockk<Location>()
            val world = mockk<World>()
            every { player.uniqueId } returns playerId
            every { player.location } returns location
            every { location.world } returns world
            every { location.blockX } returns 32
            every { location.blockZ } returns 64
            every { player.hasPermission("lands.admin.command.edit") } returns true
            every { player.hasPermission(commandPermission) } returns true
            every { integration.getLandByUnloadedChunk(world, 2, 4) } returns land
            every { integration.getLandPlayer(playerId) } returns landPlayer
            every { land.exists() } returns true
            every { land.ulid } returns landUlid
            every { landUlid.toString() } returns "01KFOREIGN"
            every { landPlayer.setEditLand(land) } just runs
            every { landPlayer.lands } returns emptySet<Land>()
            every { player.performCommand(action.command) } returns true

            BukkitLandsUiGateway(integration).administerCurrent(player, "01KFOREIGN", action) shouldBe
                LandsUiCommandResult.EXECUTED

            verifyOrder {
                landPlayer.setEditLand(land)
                player.performCommand(action.command)
            }
            verify(exactly = 0) { landPlayer.lands }
            verify(exactly = 0) { integration.getLandByULID(any()) }
        }
    }

    "admin handoffs fail closed when either required permission is revoked" {
        listOf(
            LandsUiAdminAction.MENU to "lands.command.menu",
            LandsUiAdminAction.MEMBERS to "lands.command.member.menu",
        ).forEach { (action, commandPermission) ->
            listOf("lands.admin.command.edit", commandPermission).forEach { revokedPermission ->
                val integration = mockk<LandsIntegration>()
                val player = mockk<Player>()
                every { player.hasPermission("lands.admin.command.edit") } returns
                    (revokedPermission != "lands.admin.command.edit")
                every { player.hasPermission(commandPermission) } returns
                    (revokedPermission != commandPermission)

                BukkitLandsUiGateway(integration).administerCurrent(player, "01KFOREIGN", action) shouldBe
                    LandsUiCommandResult.COMMAND_REJECTED

                verify(exactly = 0) { integration.getLandByUnloadedChunk(any(), any(), any()) }
                verify(exactly = 0) { integration.getLandPlayer(any()) }
                verify(exactly = 0) { player.performCommand(any()) }
            }
        }
    }

    "admin handoffs reject a moved or deleted current land" {
        listOf(
            Triple("01KOTHER", true, "different land at the location"),
            Triple("01KFOREIGN", false, "deleted land"),
        ).forEach { (currentLandId, exists, _) ->
            val playerId = UUID.randomUUID()
            val integration = mockk<LandsIntegration>()
            val land = mockk<Land>()
            val landUlid = mockk<ULID>()
            val player = mockk<Player>()
            val location = mockk<Location>()
            val world = mockk<World>()
            every { player.location } returns location
            every { location.world } returns world
            every { location.blockX } returns 32
            every { location.blockZ } returns 64
            every { player.hasPermission("lands.admin.command.edit") } returns true
            every { player.hasPermission("lands.command.menu") } returns true
            every { integration.getLandByUnloadedChunk(world, 2, 4) } returns land
            every { land.exists() } returns exists
            every { land.ulid } returns landUlid
            every { landUlid.toString() } returns currentLandId

            BukkitLandsUiGateway(integration).administerCurrent(player, "01KFOREIGN", LandsUiAdminAction.MENU) shouldBe
                LandsUiCommandResult.LAND_UNAVAILABLE

            verify(exactly = 0) { integration.getLandPlayer(playerId) }
            verify(exactly = 0) { player.performCommand(any()) }
        }
    }
})
