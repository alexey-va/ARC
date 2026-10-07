package ru.arc.contracts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
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
                    ContractFunding.SERVER_ENVELOPE, 1000, Long.MAX_VALUE, 100, 500_000, 1536, 1536,
                    maxSubmissionQuantity = 1536, group = group)
                val view = ResourceContractPlayerView(ResourceContractView(definition.id, definition.displayName,
                    definition.itemKey, "server-envelope", "open", definition.windowStartsAt, definition.windowEndsAt,
                    100, 500_000, 0, 0, 1536, 0, 0, 1536, 0, group), 1, 1536, 1536, 0, 0, 1536, 100,
                    10_000, 10_000, definition, 0, 1500)
                every { ContractOriginGate.canSubmit(player, group) } returns true
                every { ContractsManager.currentPlayerViews(player.uniqueId, group, any(), any()) } returns if (group == "food_orders") (1..23).map { index ->
                    view.copy(contract = view.contract.copy(id = "order_${group}_$index", displayName = "Треска $index"))
                } else listOf(view)
                every { ContractsManager.quote(player, any(), any()) } answers {
                    ContractSubmissionQuote(secondArg(), 1000, player.uniqueId.toString(), thirdArg(),
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
                top.size shouldBe 54
                PlainTextComponentSerializer.plainText().serialize(inventoryView.title()) shouldBe when (group) {
                    "food_orders" -> "Матео · продукты для бара"
                    "forge_orders" -> "Заказы кузницы"
                    "bank_orders" -> "Закупки палаты сделок"
                    else -> "Снабжение гильдии"
                }
                val layout = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(6))
                val deposits = layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).map { it.index }
                deposits shouldBe (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
                deposits.forEachIndexed { index, slot ->
                    val offered = ItemStack(Material.COD, index + 1)
                    inventoryView.setCursor(offered)
                    server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                        slot, ClickType.LEFT, InventoryAction.PLACE_ALL))
                    top.getItem(slot) shouldBe offered
                    inventoryView.cursor.type.isAir shouldBe true
                }
                ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                saleCalls shouldBe 0
                fun assertArrows() {
                    top.getItem(45)!!.itemMeta.customModelData shouldBe 11009
                    top.getItem(48)!!.itemMeta.customModelData shouldBe 11008
                }
                fun clickPage(slot: Int) {
                    server.pluginManager.callEvent(InventoryClickEvent(inventoryView,
                        InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    server.scheduler.performTicks(1)
                }
                assertArrows()
                val firstOrder = top.getItem(0)!!.clone()
                clickPage(45)
                top.getItem(0) shouldBe firstOrder
                assertArrows()
                if (group == "food_orders") {
                    for ((slot, index) in listOf(46 to 21, 47 to 22)) {
                        top.getItem(slot)!!.type shouldBe Material.COD
                        PlainTextComponentSerializer.plainText().serialize(top.getItem(slot)!!.itemMeta.displayName()!!)
                            .contains("Треска $index") shouldBe true
                    }
                    clickPage(48)
                    PlainTextComponentSerializer.plainText().serialize(top.getItem(0)!!.itemMeta.displayName()!!)
                        .contains("Треска 23") shouldBe true
                    top.getItem(19)!!.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
                    assertArrows()
                    ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                    saleCalls shouldBe 0
                }
                val lastPageOrder = top.getItem(0)!!.clone()
                clickPage(48)
                top.getItem(0) shouldBe lastPageOrder
                assertArrows()
                ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                saleCalls shouldBe 0
                top.getItem(46)!!.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
                top.getItem(46)!!.itemMeta.hasCustomModelData() shouldBe false
                fun sell() = server.pluginManager.callEvent(InventoryClickEvent(inventoryView,
                    InventoryType.SlotType.CONTAINER, layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                sell()
                server.scheduler.performTicks(5)
                saleCalls shouldBe 1
                deposits.forEach { slot -> top.getItem(slot) shouldBe null }
                player.inventory.getItem(0) shouldBe ItemStack(Material.COD, 64)
                (player.openInventory.topInventory === top) shouldBe true
                val soldButton = top.getItem(layout.slot("sell").index)!!
                val soldTitle = soldButton.plainDisplayName()
                soldTitle.contains("Сдано") shouldBe true
                soldTitle.contains("+300") shouldBe true
                soldButton.itemMeta.lore().orEmpty().any { line ->
                    line.containsTranslation(Material.COD.translationKey()) &&
                        PlainTextComponentSerializer.plainText().serialize(line).contains("300")
                } shouldBe true
                val completedName = soldButton.itemMeta.displayName()
                val completedLore = soldButton.itemMeta.lore()
                server.scheduler.performTicks(100)
                top.getItem(layout.slot("sell").index)!!.itemMeta.displayName() shouldBe completedName
                top.getItem(layout.slot("sell").index)!!.itemMeta.lore() shouldBe completedLore
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

    @Test
    fun `staged mushrooms show the exhausted player limit without submitting`() {
        val player = server.addPlayer()
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start()
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } answers {
                listOf(mushroomOrder(playerRemaining = 0))
            }
            every { ContractsManager.quote(player, any(), any()) } returns null
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Unavailable("unexpected"))
            }
            player.inventory.setItem(0, ItemStack(Material.BROWN_MUSHROOM, 64))
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(6))
            val depositSlot = layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).first().index

            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 16))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                depositSlot, ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(depositSlot) shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)

            val button = top.getItem(layout.slot("sell").index)!!
            button.plainDisplayName().contains("Лимит исчерпан") shouldBe true
            button.itemMeta.lore().orEmpty().any { line -> line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) } shouldBe true
            button.plainLore().any { it.contains("16") } shouldBe true
            button.plainLore().any { it.contains("Ваш лимит по заказу исчерпан") } shouldBe true

            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(5)
            submitCalls shouldBe 0
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[0] shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)
            player.inventory.getItem(0) shouldBe ItemStack(Material.BROWN_MUSHROOM, 64)
            top.getItem(layout.slot("sell").index)!!.plainDisplayName().contains("Лимит исчерпан") shouldBe true
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ArcMenus.close()
        }
    }

    @Test
    fun `partial mushroom sale reports accepted payout and capped remainder then resets on new input`() {
        val player = server.addPlayer()
        var playerRemaining = 10L
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start()
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } answers {
                listOf(mushroomOrder(playerRemaining = playerRemaining))
            }
            every { ContractsManager.quote(player, any(), any()) } answers {
                val quantity = minOf(thirdArg<Int>().toLong(), playerRemaining).toInt()
                if (quantity == 0) null else ContractSubmissionQuote(
                    secondArg(), 1_000, player.uniqueId.toString(), quantity, quantity * 100L, 0, 1_500,
                )
            }
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                val quote = secondArg<ContractSubmissionQuote>()
                val prepared = arg<PreparedContractInventory>(3)
                runBlocking { prepared.removeExact() } shouldBe ContractInventoryMutation.Confirmed
                playerRemaining -= quote.quantity
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Committed(
                    ContractSubmissionReceipt("mushroom-sale", player.uniqueId.toString(), quote.quantity.toLong(),
                        quote.payoutMinor, 1_600),
                ))
            }
            player.inventory.setItem(0, ItemStack(Material.BROWN_MUSHROOM, 64))
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACT_DESKS.getValue(6))
            val deposits = layout.region(ArcMenuSchema.CONTRACT_DEPOSIT).map { it.index }
            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 16))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                deposits[0], ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(deposits[0]) shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)

            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(5)
            submitCalls shouldBe 1
            val button = top.getItem(layout.slot("sell").index)!!
            button.plainDisplayName().contains("Сдано частично") shouldBe true
            button.plainDisplayName().contains("+10") shouldBe true
            button.itemMeta.lore().orEmpty().any { line ->
                line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) &&
                    PlainTextComponentSerializer.plainText().serialize(line).contains("10")
            } shouldBe true
            button.itemMeta.lore().orEmpty().any { line ->
                line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) &&
                    PlainTextComponentSerializer.plainText().serialize(line).contains("6")
            } shouldBe true
            button.plainLore().any { it.contains("Ваш лимит по заказу исчерпан") } shouldBe true
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[0] shouldBe ItemStack(Material.BROWN_MUSHROOM, 6)
            player.inventory.getItem(0) shouldBe ItemStack(Material.BROWN_MUSHROOM, 64)

            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 2))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                deposits[1], ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(deposits[1]) shouldBe ItemStack(Material.BROWN_MUSHROOM, 2)
            top.getItem(layout.slot("sell").index)!!.plainDisplayName().contains("Сдано частично") shouldBe false
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[1] shouldBe ItemStack(Material.BROWN_MUSHROOM, 2)
            submitCalls shouldBe 1
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ArcMenus.close()
        }
    }
}

private fun mushroomOrder(playerRemaining: Long): ResourceContractPlayerView {
    val definition = ResourceContractDefinition(
        id = "mushroom-order",
        displayName = "Коричневые грибы",
        itemKey = "minecraft:brown_mushroom",
        funding = ContractFunding.SERVER_ENVELOPE,
        windowStartsAt = 1_000,
        windowEndsAt = Long.MAX_VALUE,
        payoutMinorPerUnit = 100,
        budgetMinor = 500_000,
        targetQuantity = 1_536,
        perPlayerQuantityCap = 10,
        maxSubmissionQuantity = 1_536,
        group = "food_orders",
    )
    return ResourceContractPlayerView(
        contract = ResourceContractView(
            id = definition.id,
            displayName = definition.displayName,
            itemKey = definition.itemKey,
            funding = definition.funding.label,
            status = ContractStatus.OPEN.label,
            windowStartsAt = definition.windowStartsAt,
            windowEndsAt = definition.windowEndsAt,
            payoutMinorPerUnit = definition.payoutMinorPerUnit,
            budgetMinor = definition.budgetMinor,
            spentMinor = 0,
            reservedMinor = 0,
            targetQuantity = definition.targetQuantity,
            acceptedQuantity = 0,
            reservedQuantity = 0,
            remainingQuantity = definition.targetQuantity,
            contributors = 0,
            group = definition.group,
        ),
        minSubmissionQuantity = 1,
        maxSubmissionQuantity = definition.maxSubmissionQuantity,
        perPlayerQuantityCap = definition.perPlayerQuantityCap,
        playerAcceptedQuantity = definition.perPlayerQuantityCap - playerRemaining,
        playerReservedQuantity = 0,
        playerRemainingQuantity = playerRemaining,
        playerPayoutMinorPerUnit = definition.payoutMinorPerUnit,
        capBasisPoints = 10_000,
        payoutBasisPoints = 10_000,
        pricingDefinition = definition,
        pricingSupply = 0,
        pricingAt = 1_500,
    )
}

private fun ItemStack.plainDisplayName(): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(itemMeta.displayName()))

private fun ItemStack.plainLore(): List<String> =
    itemMeta.lore().orEmpty().map(PlainTextComponentSerializer.plainText()::serialize)

private fun Component.containsTranslation(key: String): Boolean =
    (this as? TranslatableComponent)?.key() == key || children().any { it.containsTranslation(key) }
