package ru.arc.commands

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseOutcome
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.hooks.economyshop.ShopMaterialQuote

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

    @Test
    fun `localized item name and legacy economy price render in success response`() {
        HookRegistry.shopPurchaseService =
            object : ShopPurchaseService {
                override fun itemQueries(player: Player) = listOf("Blocks.CALCITE")

                override fun quotePlainMaterial(player: Player, material: Material, amount: Int): ShopMaterialQuote? = null

                override fun vaultBalance(player: Player): Double? = null

                override fun formatVaultPrice(amount: Double): String? = null

                override fun purchase(player: Player, itemPath: String, amount: Int) =
                    ShopPurchaseOutcome(
                        ShopPurchaseStatus.SUCCESS,
                        "Blocks.page1.items.1",
                        amount,
                        formattedPrice = "4,00§f💰",
                        itemName = "Голубой краситель",
                    )
            }
        val player = server.addPlayer()

        assertDoesNotThrow { server.dispatchCommand(player, "buy Blocks CALCITE 1") }

        val message = requireNotNull(player.nextComponentMessage())
        assertEquals(
            "Куплено 1 шт. товара Голубой краситель за 4,00💰.",
            PlainTextComponentSerializer.plainText().serialize(message),
        )
    }

    private fun fakeService() =
        object : ShopPurchaseService {
            override fun itemQueries(player: Player) = listOf("Blocks.TNT", "Redstone.RAIL")

            override fun quotePlainMaterial(player: Player, material: Material, amount: Int): ShopMaterialQuote? = null

            override fun vaultBalance(player: Player): Double? = null

            override fun formatVaultPrice(amount: Double): String? = null

            override fun purchase(player: Player, itemPath: String, amount: Int): ShopPurchaseOutcome {
                purchases += itemPath to amount
                return ShopPurchaseOutcome(ShopPurchaseStatus.SUCCESS, itemPath, amount, "$amount монет")
            }
        }
}
