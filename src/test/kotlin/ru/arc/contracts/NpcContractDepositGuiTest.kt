package ru.arc.contracts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.TestBase
import ru.arc.gui.ArcMenus
import ru.arc.gui.ArcMenuSchema
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import java.util.concurrent.CompletableFuture

class NpcContractDepositGuiTest : TestBase() {
    @Test
    fun `all NPC groups stage input then sell only offered items and keep the same chest open`() {
        val player = server.addPlayer()
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit // The platform port is verified separately.
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin) // Isolate the desk from unrelated boot-time NPC tasks.
            NpcContractDepositGui.start()
            for (group in listOf("food_orders", "forge_orders", "bank_orders", "guild_orders")) {
                val definition = ResourceContractDefinition("order_$group", "Треска", "minecraft:cod",
                    ContractFunding.SERVER_ENVELOPE, 1000, Long.MAX_VALUE, 100, 10_000, 100, 64,
                    maxSubmissionQuantity = 64, group = group)
                val view = ResourceContractPlayerView(ResourceContractView(definition.id, definition.displayName,
                    definition.itemKey, "server-envelope", "open", definition.windowStartsAt, definition.windowEndsAt,
                    100, 10_000, 0, 0, 100, 0, 0, 100, 0, group), 1, 64, 64, 0, 0, 64, 100,
                    10_000, 10_000, definition, 0, 1500)
                every { ContractOriginGate.canSubmit(player, group) } returns true
                every { ContractsManager.currentPlayerViews(player.uniqueId, group, any(), any()) } returns listOf(view)
                every { ContractsManager.quote(player, definition.id, any()) } answers {
                    ContractSubmissionQuote(definition.id, 1000, player.uniqueId.toString(), thirdArg(),
                        thirdArg<Int>() * 100L, 0, 1500)
                }
                var saleCalls = 0
                every { ContractsManager.submit(player, any(), null, any()) } answers {
                    saleCalls++
                    val quote = secondArg<ContractSubmissionQuote>()
                    val prepared = arg<PreparedContractInventory>(3)
                    runBlocking { prepared.removeExact() } shouldBe ContractInventoryMutation.Confirmed
                    CompletableFuture.completedFuture(ContractSubmissionOutcome.Committed(
                        ContractSubmissionReceipt("test-$saleCalls", player.uniqueId.toString(), quote.quantity.toLong(), quote.payoutMinor, 1600)))
                }
                player.inventory.clear()
                player.inventory.setItem(0, ItemStack(Material.COD, 64)) // Unoffered stock must remain.
                NpcContractDepositGui.open(player, group)
                val inventoryView = player.openInventory
                val top = inventoryView.topInventory
                top.size shouldBe 27
                PlainTextComponentSerializer.plainText().serialize(inventoryView.title()) shouldBe when (group) {
                    "food_orders" -> "Матео · продукты для бара"
                    "forge_orders" -> "Заказы кузницы"
                    "bank_orders" -> "Закупки палаты сделок"
                    else -> "Снабжение гильдии"
                }
                val layout = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(3))
                val deposits = layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).map { it.index }
                deposits shouldBe listOf(6,7,8,15,16,17,24,25,26)
                deposits.forEachIndexed { index, slot ->
                    val offered = ItemStack(Material.COD, index + 1)
                    inventoryView.setCursor(offered)
                    server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                        slot, ClickType.LEFT, InventoryAction.PLACE_ALL))
                    top.getItem(slot) shouldBe offered
                    inventoryView.cursor.type.isAir shouldBe true
                }
                ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..9).toList()
                saleCalls shouldBe 0
                fun sell() = server.pluginManager.callEvent(InventoryClickEvent(inventoryView,
                    InventoryType.SlotType.CONTAINER, layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                sell()
                server.scheduler.performTicks(5)
                saleCalls shouldBe 1
                deposits.forEach { slot -> top.getItem(slot) shouldBe null }
                player.inventory.getItem(0) shouldBe ItemStack(Material.COD, 64)
                (player.openInventory.topInventory === top) shouldBe true
                sell()
                server.scheduler.performTicks(5)
                saleCalls shouldBe 1
                player.closeInventory()
            }
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ArcMenus.close()
        }
    }
}
