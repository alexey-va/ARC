package ru.arc.helpcenter

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import com.olziedev.playerwarps.api.expansion.WCurrency
import com.olziedev.playerwarps.api.warp.WLocation
import com.olziedev.playerwarps.api.warp.Warp
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID

class BukkitHelpCenterGatewayTest : StringSpec({
    afterTest { PlayerManager.readMessage("[]") }

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
            val viewer = mockk<com.olziedev.playerwarps.api.player.WPlayer>()
            val price = mockk<WCurrency.CurrencySection>()
            val warps = listOf(
                testWarp(1L, "Null description", null, price),
                testWarp(2L, "Blank description", "   ", price),
                testWarp(3L, "Filled description", "  A useful place  ", price),
            )
            mockkStatic(PlayerWarpsAPI::class)
            try {
                every { PlayerWarpsAPI.getInstance() } returns api
                every { api.getWarpPlayer(player.uniqueId) } returns viewer
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
})

private fun testWarp(
    id: Long,
    name: String,
    description: String?,
    price: WCurrency.CurrencySection,
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
    every { warp.getWarpPlayer() } returns null
    every { warp.getWarpLocation() } returns location
    every { warp.getWarpDescription(false) } returns description
    every { warp.getTeleportPrice(any(), any()) } returns price
    return warp
}
