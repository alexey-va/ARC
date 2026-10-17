package ru.arc.homemarkers

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import com.olziedev.playerwarps.api.player.WPlayer
import com.olziedev.playerwarps.api.warp.Warp
import com.olziedev.playerwarps.api.warp.WLocation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.position.Home
import net.william278.huskhomes.position.World
import net.william278.huskhomes.user.OnlineUser
import net.william278.huskhomes.user.User
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HomeMarkerGatewayTest : StringSpec({
    "loads only this player's points on each provider's current server" {
        MockBukkitTestRuntime.open().use { paper ->
            val huskPlugin = paper.createSimplePlugin("HuskHomes")
            val warpPlugin = paper.createSimplePlugin("PlayerWarps") as JavaPlugin
            warpPlugin.config.set("settings.database.mysql.server-name", "survival")
            val player = paper.addPlayer("MarkerOwner")
            val foreignOwner = UUID.fromString("00000000-0000-0000-0000-000000000099")

            val adaptedUser = mockk<OnlineUser>()
            val huskApi = mockk<HuskHomesAPI>()
            val homes = listOf(
                testHome("current-home", player.uniqueId, "survival", "world"),
                testHome("remote-home", player.uniqueId, "spawn", "world"),
                testHome("foreign-home", foreignOwner, "survival", "world"),
            )
            mockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            every { adaptedUser.uuid } returns player.uniqueId
            every { HuskHomesAPI.getInstance() } returns huskApi
            every { huskApi.server } returns "survival"
            every { huskApi.adaptUser(player) } returns adaptedUser
            every { huskApi.getUserHomes(adaptedUser) } returns CompletableFuture.completedFuture(homes)

            val ownedWarps = mockk<WPlayer>()
            val playerWarpsApi = mockk<PlayerWarpsAPI>()
            val warps = listOf(
                testWarp(7L, player.uniqueId, "Market", "survival", "world"),
                testWarp(8L, player.uniqueId, "Remote", "spawn", "world"),
                testWarp(9L, foreignOwner, "Foreign", "survival", "world"),
            )
            every { PlayerWarpsAPI.getInstance() } returns playerWarpsApi
            every { playerWarpsApi.getWarpPlayer(player.uniqueId) } returns ownedWarps
            every { ownedWarps.getWarps(false) } returns warps

            try {
                val markers = HomeMarkerGateway().load(player).join()
                markers.map { it.id }.shouldContainExactly(
                    homes.first().uuid.toString(),
                    "7",
                )
                markers.all { it.ownerId == player.uniqueId } shouldBe true
                markers.all { it.world == "world" } shouldBe true
                Bukkit.getPluginManager().isPluginEnabled(huskPlugin.name) shouldBe true
            } finally {
                unmockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            }
        }
    }

    "does not open settings for a stale owned warp marker" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("PlayerWarps")
            val warpPlugin = Bukkit.getPluginManager().getPlugin("PlayerWarps") as JavaPlugin
            warpPlugin.config.set("settings.database.mysql.server-name", "survival")
            val player = paper.addPlayer("MarkerOwner")
            val owner = mockk<WPlayer>()
            val api = mockk<PlayerWarpsAPI>()

            mockkStatic(PlayerWarpsAPI::class)
            try {
                every { PlayerWarpsAPI.getInstance() } returns api
                every { api.getWarpPlayer(player.uniqueId) } returns owner
                every { owner.getWarps(false) } returns emptyList()

                HomeMarkerGateway().openSettings(
                    player,
                    HomeMarker(
                        kind = HomeMarkerKind.WARP,
                        id = "123",
                        ownerId = player.uniqueId,
                        name = "OldName",
                        world = "world",
                        x = 1.5,
                        y = 70.0,
                        z = 2.5,
                    ),
                ) shouldBe false
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }

    "opens the exact native settings command only for the marker owner" {
        MockBukkitTestRuntime.open().use { paper ->
            val warpPlugin = paper.createSimplePlugin("PlayerWarps") as JavaPlugin
            warpPlugin.config.set("settings.main-command", "marketwarp")
            warpPlugin.config.set("settings.database.mysql.server-name", "survival")

            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000007")
            val player = mockk<Player>()
            every { player.uniqueId } returns ownerId
            every { player.performCommand("marketwarp settings Market") } returns true

            val owner = mockk<WPlayer>()
            val api = mockk<PlayerWarpsAPI>()

            mockkStatic(PlayerWarpsAPI::class)
            try {
                every { PlayerWarpsAPI.getInstance() } returns api
                every { api.getWarpPlayer(ownerId) } returns owner
                every { owner.getWarps(false) } returns listOf(
                    testWarp(123L, ownerId, "Market", "survival", "world"),
                )

                val marker = HomeMarker(
                    kind = HomeMarkerKind.WARP,
                    id = "123",
                    ownerId = ownerId,
                    name = "Market",
                    world = "world",
                    x = 1.5,
                    y = 70.0,
                    z = 2.5,
                )

                HomeMarkerGateway().openSettings(player, marker) shouldBe true
                verify(exactly = 1) { player.performCommand("marketwarp settings Market") }

                val otherPlayer = mockk<Player>()
                every { otherPlayer.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000008")
                HomeMarkerGateway().openSettings(otherPlayer, marker) shouldBe false
                verify(exactly = 0) { otherPlayer.performCommand(any()) }
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }
})

private fun testHome(name: String, ownerId: UUID, server: String, worldName: String): Home {
    val owner = User.of(ownerId, "owner-$name")
    return mockk<Home> {
        every { this@mockk.owner } returns owner
        every { uuid } returns UUID.nameUUIDFromBytes(name.toByteArray())
        every { this@mockk.name } returns name
        every { this@mockk.server } returns server
        every { this@mockk.world } returns World.from(worldName)
        every { x } returns 1.5
        every { y } returns 70.0
        every { z } returns 2.5
    }
}

private fun testWarp(id: Long, ownerId: UUID, name: String, server: String, world: String): Warp {
    val location = mockk<WLocation>()
    every { location.warpServer } returns server
    every { location.world } returns world
    every { location.x } returns 1.5
    every { location.y } returns 70.0
    every { location.z } returns 2.5

    return mockk<Warp> {
        every { this@mockk.id } returns id
        every { uuid } returns ownerId
        every { warpName } returns name
        every { warpLocation } returns location
    }
}
