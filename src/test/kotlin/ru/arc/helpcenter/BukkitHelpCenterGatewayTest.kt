package ru.arc.helpcenter

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import com.olziedev.playerwarps.api.expansion.WCurrency
import com.olziedev.playerwarps.api.player.WPlayer
import com.olziedev.playerwarps.api.warp.WLocation
import com.olziedev.playerwarps.api.warp.Warp
import io.kotest.assertions.throwables.shouldThrow
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
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.function.Function

class BukkitHelpCenterGatewayTest : StringSpec({
    afterTest { PlayerManager.readMessage("[]") }

    "dungeon menu uses the current custom provider and fails closed when native data is unavailable" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DungeonsGateway")
            val player = paper.addPlayer("DungeonVisitor")
            val service = mockk<ru.arc.dungeons.api.ArcDungeonsService>(relaxed = true)
            org.bukkit.Bukkit.getServicesManager().register(ru.arc.dungeons.api.ArcDungeonsService::class.java,
                service, plugin, org.bukkit.plugin.ServicePriority.Normal)
            val gateway = BukkitHelpCenterGateway()
            every { service.nativeReady() } returns false
            gateway.openDungeons(player) shouldBe false
            io.mockk.verify(exactly = 0) { service.dungeonAction(any(), any(), any()) }
            every { service.nativeReady() } returns true
            gateway.openDungeons(player) shouldBe true
            io.mockk.verify(exactly = 1) { service.dungeonAction(player, "menu", emptyList()) }
        }
    }

    "keeps provider visibility in each personal home snapshot" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("HuskHomes")
            val player = paper.addPlayer("HomeOwner")
            val api = mockk<HuskHomesAPI>()
            val user = mockk<OnlineUser>()
            val homes = listOf(false, true).map { public ->
                mockk<Home> {
                    every { name } returns if (public) "public-home" else "private-home"
                    every { server } returns "spawn"
                    every { world } returns World.from("world")
                    every { x } returns 1.5
                    every { y } returns 70.0
                    every { z } returns -2.5
                    every { isPublic } returns public
                }
            }
            mockkStatic(HuskHomesAPI::class)
            try {
                every { HuskHomesAPI.getInstance() } returns api
                every { api.adaptUser(player) } returns user
                every { api.getMaxHomeSlots(user) } returns 5
                every { api.getUserHomes(user) } returns CompletableFuture.completedFuture(homes)

                val snapshots = BukkitHelpCenterGateway().loadHomes(player, 3).join().homes
                snapshots.map { it.isPublic }.shouldContainExactly(false, true)
                snapshots.map { it.z }.shouldContainExactly(-3, -3)
            } finally {
                unmockkStatic(HuskHomesAPI::class)
            }
        }
    }

    "reads proxy-wide players with their server from the ARC player manager" {
        val survivalPlayer = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val spawnPlayer = UUID.fromString("00000000-0000-0000-0000-000000000022")
        PlayerManager.readMessage(
            """[
                {"username":"Limonka","server":"survival","uuid":"$survivalPlayer","joinTime":1},
                {"username":"Steve","server":"spawn","uuid":"$spawnPlayer","joinTime":2}
            ]""".trimIndent(),
        )

        BukkitHelpCenterGateway().onlinePlayers()
            .sortedBy { it.name }
            .shouldContainExactly(
                HelpCenterPlayer(survivalPlayer, "Limonka", "survival"),
                HelpCenterPlayer(spawnPlayer, "Steve", "spawn"),
            )
    }

    "maps nullable, blank and populated PlayerWarps descriptions without failing" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("WarpViewer")
            val api = mockk<PlayerWarpsAPI>()
            val viewer = cachedWarpPlayer(player.uniqueId, "WarpViewer")
            val price = mockk<WCurrency.CurrencySection>()
            val warps = listOf(
                testWarp(1L, "Null description", null, price),
                testWarp(2L, "Blank description", "   ", price),
                testWarp(3L, "Filled description", "  A useful place  ", price),
            )
            mockkStatic(PlayerWarpsAPI::class)
            try {
                readyPlayerWarpsApi(api)
                every { api.getWarpPlayers() } returns listOf(viewer)
                every { api.getPlayerWarps(false, player) } returns warps
                every { api.getConsoleName() } returns "Console"
                every { price.price } returns 0.0
                every { price.currencyNames } returns emptyMap()
                every { price.getPrefix(any()) } returns "0"

                BukkitHelpCenterGateway().loadWarps(player).associate { it.name to it.description } shouldBe mapOf(
                    "Blank description" to null,
                    "Filled description" to "A useful place",
                    "Null description" to null,
                )
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }

    "preserves the native viewer quote, currencies and cached owner's name without hydrating players" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("WarpViewer")
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000077")
            val viewer = cachedWarpPlayer(player.uniqueId, "WarpViewer")
            val owner = cachedWarpPlayer(ownerId, "MarketOwner")
            val api = mockk<PlayerWarpsAPI>()
            val price = mockk<WCurrency.CurrencySection>()
            val warp = testWarp(7L, "Market", "Goods", price, ownerId)
            mockkStatic(PlayerWarpsAPI::class)
            try {
                readyPlayerWarpsApi(api)
                every { api.getWarpPlayers() } returns listOf(owner, viewer)
                every { api.getPlayerWarps(false, player) } returns listOf(warp)
                every { warp.getTeleportPrice(any(), any()) } answers {
                    firstArg<WPlayer>() shouldBe viewer
                    secondArg<Function<Double, Double>>().apply(37.5) shouldBe 37.5
                    Bukkit.isPrimaryThread() shouldBe true
                    price
                }
                every { price.price } returns 37.5
                every { price.currencyNames } returns mapOf("Vault" to "37.50")
                every { price.getPrefix(any()) } returns "37.50 coins"

                val snapshot = BukkitHelpCenterGateway().loadWarps(player).single()
                snapshot.owner shouldBe "MarketOwner"
                snapshot.price shouldBe 37.5
                snapshot.currencies shouldBe mapOf("Vault" to "37.50")
                snapshot.priceLabel shouldBe "37.50 coins"
                verify(exactly = 0) { api.getWarpPlayer(any()) }
                verify(exactly = 0) { warp.getWarpPlayer() }
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }

    "reports uncached viewers and warp owners instead of hydrating them or inventing a quote" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("WarpViewer")
            val viewer = cachedWarpPlayer(player.uniqueId, "WarpViewer")
            val api = mockk<PlayerWarpsAPI>()
            val price = mockk<WCurrency.CurrencySection>()
            val warp = testWarp(7L, "Market", null, price, UUID.randomUUID())
            mockkStatic(PlayerWarpsAPI::class)
            try {
                readyPlayerWarpsApi(api)
                every { api.getWarpPlayers() } returns emptyList()
                val gateway = BukkitHelpCenterGateway()
                shouldThrow<IllegalStateException> { gateway.loadWarps(player) }
                    .message shouldBe "PlayerWarps viewer is not ready"
                verify(exactly = 0) { api.getPlayerWarps(any(), any()) }

                every { api.getWarpPlayers() } returns listOf(viewer)
                every { api.getPlayerWarps(false, player) } returns listOf(warp)
                shouldThrow<IllegalStateException> { gateway.loadWarps(player) }
                    .message shouldBe "PlayerWarps warp owner is not ready"
                verify(exactly = 0) { api.getWarpPlayer(any()) }
                verify(exactly = 0) { warp.getWarpPlayer() }
                verify(exactly = 0) { warp.getTeleportPrice(any(), any()) }
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }

    "keeps server and configured console-owned warps without a cached console player" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("WarpViewer")
            val viewer = cachedWarpPlayer(player.uniqueId, "WarpViewer")
            val api = mockk<PlayerWarpsAPI>()
            val price = mockk<WCurrency.CurrencySection>()
            val consoleId = UUID.fromString("00000000-0000-0000-0000-000000000088")
            val serverWarp = testWarp(1L, "Server", null, price)
            val consoleWarp = testWarp(2L, "Console", null, price, consoleId)
            mockkStatic(PlayerWarpsAPI::class)
            try {
                readyPlayerWarpsApi(api)
                every { api.consoleUUID } returns consoleId
                every { api.consoleName } returns "Server console"
                every { api.getWarpPlayers() } returns listOf(viewer)
                every { api.getPlayerWarps(false, player) } returns listOf(serverWarp, consoleWarp)
                every { price.price } returns 0.0
                every { price.currencyNames } returns emptyMap()
                every { price.getPrefix(any()) } returns "0"

                BukkitHelpCenterGateway().loadWarps(player).map { it.owner }
                    .shouldContainExactly("Server console", "Server console")
                verify(exactly = 0) { api.getWarpPlayer(any()) }
                verify(exactly = 0) { serverWarp.getWarpPlayer() }
                verify(exactly = 0) { consoleWarp.getWarpPlayer() }
            } finally {
                unmockkStatic(PlayerWarpsAPI::class)
            }
        }
    }

    "keeps HuskHomes available when optional PlayerWarps data is not ready or uncached" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("HuskHomes")
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("HomeOwner")
            val husk = mockk<HuskHomesAPI>()
            val user = mockk<OnlineUser>()
            val warps = mockk<PlayerWarpsAPI>()
            var ready: Consumer<PlayerWarpsAPI>? = null
            mockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            try {
                every { HuskHomesAPI.getInstance() } returns husk
                every { husk.adaptUser(player) } returns user
                every { husk.getMaxHomeSlots(user) } returns 5
                every { husk.getUserHomes(user) } answers { CompletableFuture.completedFuture(emptyList()) }
                every { PlayerWarpsAPI.getInstance() } returns warps
                every { PlayerWarpsAPI.getInstance(any<Consumer<PlayerWarpsAPI>>()) } answers {
                    ready = firstArg<Consumer<PlayerWarpsAPI>>()
                }
                every { warps.getWarpPlayer(any()) } throws AssertionError("The help center must not load a WPlayer")
                every { warps.getWarpPlayers() } returns emptyList()
                val gateway = BukkitHelpCenterGateway()

                gateway.loadHomes(player, 3).join() shouldBe HelpCenterHomes(emptyList(), 0, 5)
                verify(exactly = 0) { warps.getWarpPlayers() }
                requireNotNull(ready).accept(warps)
                gateway.loadHomes(player, 3).join() shouldBe HelpCenterHomes(emptyList(), 0, 5)
                verify(exactly = 1) { warps.getWarpPlayers() }
                verify(exactly = 0) { warps.getWarpPlayer(any()) }
            } finally {
                unmockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            }
        }
    }

    "captures native warp limits on main before the homes future completes on a worker" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("HuskHomes")
            paper.createSimplePlugin("PlayerWarps")
            val player = paper.addPlayer("HomeOwner")
            val husk = mockk<HuskHomesAPI>()
            val user = mockk<OnlineUser>()
            val warps = mockk<PlayerWarpsAPI>()
            val viewer = cachedWarpPlayer(player.uniqueId, "HomeOwner")
            val pendingHomes = CompletableFuture<List<Home>>()
            mockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            try {
                every { HuskHomesAPI.getInstance() } returns husk
                every { husk.adaptUser(player) } returns user
                every { husk.getMaxHomeSlots(user) } returns 5
                every { husk.getUserHomes(user) } returns pendingHomes
                readyPlayerWarpsApi(warps)
                every { warps.getWarpPlayers() } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    listOf(viewer)
                }
                every { viewer.getUsedWarps(player) } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    2L
                }
                every { viewer.maximumWarps } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    7L
                }

                val loaded = BukkitHelpCenterGateway().loadHomes(player, 30)
                loaded.isDone shouldBe false
                verify(exactly = 1) { warps.getWarpPlayers() }
                verify(exactly = 1) { viewer.getUsedWarps(player) }
                verify(exactly = 1) { viewer.maximumWarps }

                CompletableFuture.runAsync { pendingHomes.complete(emptyList()) }.join()
                val snapshot = loaded.join()
                snapshot.usedWarps shouldBe 2L
                snapshot.maxWarps shouldBe 7L
                verify(exactly = 1) { viewer.getUsedWarps(player) }
                verify(exactly = 1) { viewer.maximumWarps }
                verify(exactly = 0) { warps.getWarpPlayer(any()) }
            } finally {
                unmockkStatic(HuskHomesAPI::class, PlayerWarpsAPI::class)
            }
        }
    }
})

private fun readyPlayerWarpsApi(api: PlayerWarpsAPI) {
    every { PlayerWarpsAPI.getInstance() } returns api
    every { PlayerWarpsAPI.getInstance(any<Consumer<PlayerWarpsAPI>>()) } answers {
        firstArg<Consumer<PlayerWarpsAPI>>().accept(api)
    }
    every { api.getWarpPlayer(any()) } throws AssertionError("The help center must not load a WPlayer")
    every { api.consoleUUID } returns UUID.fromString("f78a4d8d-d51b-4b39-98a3-230f2de0c670")
}

private fun cachedWarpPlayer(id: UUID, name: String): WPlayer = mockk {
    every { getUUID() } returns id
    every { getName() } returns name
}

private fun testWarp(
    id: Long,
    name: String,
    description: String?,
    price: WCurrency.CurrencySection,
    ownerId: UUID? = null,
): Warp {
    val location = mockk<WLocation>()
    every { location.getWarpServer() } returns "survival"
    every { location.getWorld() } returns "world"
    every { location.getX() } returns 1.0
    every { location.getY() } returns 70.0
    every { location.getZ() } returns 2.0
    val warp = mockk<Warp>()
    every { warp.getID() } returns id
    every { warp.getWarpName() } returns name
    every { warp.getUUID() } returns ownerId
    every { warp.getWarpPlayer() } throws AssertionError("The help center must not hydrate a warp owner")
    every { warp.getWarpLocation() } returns location
    every { warp.getWarpDescription(false) } returns description
    every { warp.getTeleportPrice(any(), any()) } returns price
    return warp
}
