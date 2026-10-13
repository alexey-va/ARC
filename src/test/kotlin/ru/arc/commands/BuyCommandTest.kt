package ru.arc.commands

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.chat.ChatGlyphDefinition
import ru.arc.chat.ChatGlyphPolicy
import ru.arc.chat.ChatModeConfig
import ru.arc.chat.ItemsAdderChatGuard
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.LegacySubCommandExecutor
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseOutcome
import ru.arc.hooks.economyshop.ShopPurchaseService
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.hooks.economyshop.ShopMaterialQuote

class BuyCommandTest : TestBase() {
    private val purchases = mutableListOf<Pair<String, Int>>()
    private val purchasePlayers = mutableListOf<Player>()
    private var fixtureGlyphGuard: ItemsAdderChatGuard? = null

    @BeforeEach
    fun prepareGlyphCatalog() {
        // TestBase has neither ItemsAdder nor Redis. Keep command validation
        // active with a ready test catalog instead of its unavailable state.
        HandlerList.unregisterAll(requireNotNull(HookRegistry.chatGlyphGuard))
        val policy = ChatGlyphPolicy(listOf(ChatGlyphDefinition("test:public", "\uE123", null, false)))
        fixtureGlyphGuard = ItemsAdderChatGuard({ policy }, ChatModeConfig.load(dataPath), runSync = { it() })
            .also { server.pluginManager.registerEvents(it, plugin) }
    }

    @AfterEach
    fun clearShopService() {
        fixtureGlyphGuard?.close()
        fixtureGlyphGuard?.let(HandlerList::unregisterAll)
        fixtureGlyphGuard = null
        HookRegistry.shopPurchaseService = null
        purchases.clear()
        purchasePlayers.clear()
    }

    @Test
    fun `top-level and arc entry points share exact purchase logic`() {
        HookRegistry.shopPurchaseService = fakeService()
        val player = server.addPlayer()
        assertRegisteredCommand("buy", LegacySubCommandExecutor::class.java)
        assertRegisteredCommand("arc", ArcCommand::class.java)
        val preprocess = observeCommandPreprocess()

        assertTrue(server.dispatchCommand(player, "buy Blocks.12 64"))
        assertTrue(server.dispatchCommand(player, "arc buy Redstone pages.page1.items.1 128"))

        assertEquals(2, preprocess.events.size, "Both commands should pass through Bukkit's preprocess event")
        assertTrue(preprocess.events.all { it.player === player }, "Both preprocess events must belong to the invoking player")
        assertTrue(preprocess.events.none { it.isCancelled }, "A preprocess listener cancelled a command")

        assertEquals(
            listOf("Blocks.12" to 64, "Redstone.pages.page1.items.1" to 128),
            purchases,
        )
        assertEquals(2, purchasePlayers.size)
        purchasePlayers.forEach { assertSame(player, it, "The registered executors must forward the invoking player") }
    }

    @Test
    fun `invalid amount never reaches the shop service`() {
        HookRegistry.shopPurchaseService = fakeService()
        val player = server.addPlayer()
        assertRegisteredCommand("buy", LegacySubCommandExecutor::class.java)

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
        assertRegisteredCommand("buy", LegacySubCommandExecutor::class.java)

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
                purchasePlayers += player
                return ShopPurchaseOutcome(ShopPurchaseStatus.SUCCESS, itemPath, amount, "$amount монет")
            }
        }

    private fun assertRegisteredCommand(
        name: String,
        expectedExecutor: Class<out CommandExecutor>,
    ) {
        val declared = requireNotNull(plugin.getCommand(name)) { "plugin.yml must declare /$name" }
        assertSame(declared, server.commandMap.getCommand(name), "/$name must resolve to ARC's declared PluginCommand")
        assertTrue(
            expectedExecutor.isInstance(declared.executor),
            "/$name must use ${expectedExecutor.simpleName}, got ${declared.executor?.javaClass?.name}",
        )
    }

    private fun observeCommandPreprocess() = CommandPreprocessProbe().also {
        server.pluginManager.registerEvents(it, plugin)
    }

    private class CommandPreprocessProbe : Listener {
        val events = mutableListOf<PlayerCommandPreprocessEvent>()

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        fun observe(event: PlayerCommandPreprocessEvent) {
            events += event
        }
    }
}
