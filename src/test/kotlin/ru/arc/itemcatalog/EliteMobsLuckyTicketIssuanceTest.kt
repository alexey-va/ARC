package ru.arc.itemcatalog

import com.magmaguy.elitemobs.items.ItemConsumables
import com.magmaguy.elitemobs.items.customitems.CustomItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures

private const val TICKET_ID = "elite_lucky_ticket.yml"
private const val TICKET_COMMAND = "elitemobs:elitemobs loot give %player% elite_lucky_ticket.yml"

class EliteMobsLuckyTicketIssuanceTest : StringSpec({
    "weekly lucky ticket keeps EliteMobs identity while using the totem model" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("EliteMobs")
            val player = paper.addPlayer("lucky-ticket-owner")
            val expectedModel = Key.key("minecraft:totem_of_undying")
            val poolId = "lucky-ticket-${System.nanoTime()}"
            val commandReward = Treasure.Command(listOf(TICKET_COMMAND), id = "ticket")
            val pool = TreasurePool(poolId, treasures = listOf(commandReward))
            val customItemIdKey = NamespacedKey("elitemobs", "custom_item_id")
            val preservedMarker = NamespacedKey("test", "native_ticket_marker")
            val originalTicket = ItemStack(Material.PAPER).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(customItemIdKey, PersistentDataType.STRING, TICKET_ID)
                    meta.persistentDataContainer.set(preservedMarker, PersistentDataType.STRING, "native-ticket-data")
                }
            }
            val nativeDefinition = mockk<CustomItem>()
            val entry = RewardCatalogEntry(
                id = "ticket",
                name = "Билет удачи подземелий",
                description = emptyList(),
                rarity = null,
                requires = listOf("EliteMobs"),
                source = RewardCatalogSource.Treasure(poolId, commandReward.id),
                icon = CatalogIconStyle(Material.TOTEM_OF_UNDYING.name),
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Каталог",
                categories = listOf(
                    RewardCatalogCategory(
                        id = "elite",
                        name = "EliteMobs",
                        description = emptyList(),
                        icon = CatalogIconStyle(Material.CHEST.name),
                        entries = listOf(entry),
                    ),
                ),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val service = CatalogPhysicalRewards(settings)
            mockkObject(Treasures)
            mockkStatic(CustomItem::class)
            mockkStatic(ItemConsumables::class)
            every { Treasures.getPool(poolId) } returns pool
            every { CustomItem.getCustomItem(TICKET_ID) } returns nativeDefinition
            every { nativeDefinition.generateDefaultsItemStack(player, false, null) } returns originalTicket
            every { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) } answers {
                val stack = firstArg<ItemStack>()
                stack.type == Material.PAPER && stack.itemMeta?.persistentDataContainer
                    ?.get(customItemIdKey, PersistentDataType.STRING) == TICKET_ID
            }

            try {
                val spec = requireNotNull(service.resolve(service.key(entry)))
                spec.preview.type shouldBe Material.TOTEM_OF_UNDYING
                val factory = service.javaClass.getDeclaredMethod("eliteLuckyTicket", Player::class.java).apply {
                    isAccessible = true
                }
                val preparedTicket = factory.invoke(service, player) as ItemStack
                preparedTicket.getData(DataComponentTypes.ITEM_MODEL) shouldBe expectedModel
                preparedTicket.itemMeta?.persistentDataContainer?.get(customItemIdKey, PersistentDataType.STRING) shouldBe TICKET_ID
                service.canRedeem(player, spec) shouldBe null
                service.redeem(player, spec, java.util.UUID.randomUUID()).join() shouldBe PhysicalRewardOutcome.Applied

                val issued = player.inventory.storageContents.filterNotNull().single()
                issued.type shouldBe Material.PAPER
                // MockBukkit InventoryMock clones inserted stacks and drops ITEM_MODEL; the native factory output is checked above.
                issued.amount shouldBe 1
                issued.itemMeta?.persistentDataContainer?.get(customItemIdKey, PersistentDataType.STRING) shouldBe TICKET_ID
                issued.itemMeta?.persistentDataContainer?.get(preservedMarker, PersistentDataType.STRING) shouldBe "native-ticket-data"
                originalTicket.getData(DataComponentTypes.ITEM_MODEL) shouldBe null
                verify(exactly = 2) { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) }
                verify(exactly = 2) { nativeDefinition.generateDefaultsItemStack(player, false, null) }

                // If the native config drifts away from the old PAPER consumable, fail closed.
                val inventoryBefore = player.inventory.storageContents.map { it?.clone() }
                every { nativeDefinition.generateDefaultsItemStack(player, false, null) } returns ItemStack(Material.STICK)
                service.redeem(player, spec, java.util.UUID.randomUUID()).join() shouldBe
                    PhysicalRewardOutcome.Rejected("<red>Награда сейчас недоступна. Предмет сохранён.")
                player.inventory.storageContents.map { it?.clone() } shouldBe inventoryBefore
                player.inventory.storageContents.filterNotNull() shouldHaveSize 1
                verify(exactly = 3) { nativeDefinition.generateDefaultsItemStack(player, false, null) }
            } finally {
                unmockkStatic(ItemConsumables::class, CustomItem::class)
                unmockkObject(Treasures)
            }
        }
    }
})
