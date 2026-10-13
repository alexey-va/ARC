package ru.arc.dungeons

import com.magmaguy.elitemobs.items.ItemConsumables
import com.magmaguy.elitemobs.items.customitems.CustomItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertNotSame
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.paper.testing.MockBukkitTestRuntime

private const val TICKET_ID = "elite_lucky_ticket.yml"

class DungeonsServiceLuckyTicketTest : StringSpec({
    "creates a cloned native lucky ticket with the model and native PDC intact" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("native-ticket-owner")
            val provider = readyProvider(runtime)
            val ticketIdKey = NamespacedKey("elitemobs", "custom_item_id")
            val markerKey = NamespacedKey("test", "native_ticket_marker")
            val original = ItemStack(Material.PAPER).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(ticketIdKey, PersistentDataType.STRING, TICKET_ID)
                    meta.persistentDataContainer.set(markerKey, PersistentDataType.STRING, "native-ticket-data")
                }
            }
            val nativeDefinition = mockk<CustomItem>()
            try {
                mockkStatic(CustomItem::class)
                mockkStatic(ItemConsumables::class)
                every { CustomItem.getCustomItem(TICKET_ID) } returns nativeDefinition
                every { nativeDefinition.generateDefaultsItemStack(player, false, null) } returns original
                every { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) } answers {
                    val item = firstArg<ItemStack>()
                    item.type == Material.PAPER && item.itemMeta?.persistentDataContainer
                        ?.get(ticketIdKey, PersistentDataType.STRING) == TICKET_ID
                }

                val created = provider.createLuckyTicket(player)
                created shouldNotBe null
                val ticket = requireNotNull(created)
                assertNotSame(original, ticket)
                ticket.type shouldBe Material.PAPER
                ticket.getData(DataComponentTypes.ITEM_MODEL) shouldBe Key.key("minecraft:totem_of_undying")
                ticket.itemMeta?.persistentDataContainer?.get(ticketIdKey, PersistentDataType.STRING) shouldBe TICKET_ID
                ticket.itemMeta?.persistentDataContainer?.get(markerKey, PersistentDataType.STRING) shouldBe "native-ticket-data"
                original.getData(DataComponentTypes.ITEM_MODEL) shouldBe null
                original.itemMeta?.persistentDataContainer?.get(markerKey, PersistentDataType.STRING) shouldBe "native-ticket-data"
                verify(exactly = 1) { CustomItem.getCustomItem(TICKET_ID) }
                verify(exactly = 1) { nativeDefinition.generateDefaultsItemStack(player, false, null) }
                verify(exactly = 1) { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) }
            } finally {
                unmockkStatic(ItemConsumables::class, CustomItem::class)
            }
        }
    }

    "rejects a non-paper native item and a paper item that is not the lucky-ticket consumable" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("invalid-native-ticket-owner")
            val provider = readyProvider(runtime)
            val ticketIdKey = NamespacedKey("elitemobs", "custom_item_id")
            val nativeDefinition = mockk<CustomItem>()
            val stick = ItemStack(Material.STICK)
            val invalidPaper = ItemStack(Material.PAPER)
            try {
                mockkStatic(CustomItem::class)
                mockkStatic(ItemConsumables::class)
                every { CustomItem.getCustomItem(TICKET_ID) } returns nativeDefinition
                every { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) } answers {
                    val item = firstArg<ItemStack>()
                    item.type == Material.PAPER && item.itemMeta?.persistentDataContainer
                        ?.get(ticketIdKey, PersistentDataType.STRING) == TICKET_ID
                }

                every { nativeDefinition.generateDefaultsItemStack(player, false, null) } returns stick
                provider.createLuckyTicket(player) shouldBe null
                verify(exactly = 0) { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) }

                every { nativeDefinition.generateDefaultsItemStack(player, false, null) } returns invalidPaper
                provider.createLuckyTicket(player) shouldBe null
                verify(exactly = 1) { ItemConsumables.`is`(any(), ItemConsumables.Type.LUCKY_TICKET) }
                verify(exactly = 2) { nativeDefinition.generateDefaultsItemStack(player, false, null) }
            } finally {
                unmockkStatic(ItemConsumables::class, CustomItem::class)
            }
        }
    }
})

private fun readyProvider(runtime: MockBukkitTestRuntime): DungeonsServiceProvider {
    val eliteMobs = runtime.createSimplePlugin("EliteMobs")
    if (!eliteMobs.isEnabled) runtime.server.pluginManager.enablePlugin(eliteMobs)

    val plugin = mockk<ArcDungeonsPlugin>()
    every { plugin.isEnabled } returns true
    every { plugin.server } returns runtime.server
    every { plugin.emHook } returns mockk<EMHook>()
    return DungeonsServiceProvider(plugin)
}
