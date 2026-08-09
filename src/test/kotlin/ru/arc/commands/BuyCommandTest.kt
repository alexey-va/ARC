package ru.arc.commands

import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseOutcome
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus

class BuyCommandTest : TestBase() {
    private val purchases = mutableListOf<Pair<String, Int>>()

    @AfterEach
    fun clearShopService() {
        HookRegistry.shopPurchaseService = null
        purchases.clear()
    }

    @Test
    fun `top-level and arc entry points share exact purchase logic`() {
        HookRegistry.shopPurchaseService = fakeService()
        val player = server.addPlayer()

        assertTrue(server.dispatchCommand(player, "buy Blocks.12 64"))
        assertTrue(server.dispatchCommand(player, "arc buy Redstone pages.page1.items.1 128"))

        assertEquals(
            listOf("Blocks.12" to 64, "Redstone.pages.page1.items.1" to 128),
            purchases,
        )
    }

    @Test
    fun `invalid amount never reaches the shop service`() {
        HookRegistry.shopPurchaseService = fakeService()
        val player = server.addPlayer()

        assertTrue(server.dispatchCommand(player, "buy Blocks.12 0"))

        assertTrue(purchases.isEmpty())
    }

    private fun fakeService() =
        object : ShopPurchaseService {
            override fun itemQueries(player: Player) = listOf("Blocks.TNT", "Redstone.RAIL")

            override fun purchase(player: Player, itemPath: String, amount: Int): ShopPurchaseOutcome {
                purchases += itemPath to amount
                return ShopPurchaseOutcome(ShopPurchaseStatus.SUCCESS, itemPath, amount, "$amount монет")
            }
        }
}
