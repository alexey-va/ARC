package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.ServicePriority
import ru.arc.dungeons.api.ArcDungeonsService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.util.UUID

private const val TICKET_ID = "elite_lucky_ticket.yml"
private const val TICKET_COMMAND = "elitemobs:elitemobs loot give %player% elite_lucky_ticket.yml"
private const val UNAVAILABLE = "<red>Награда сейчас недоступна. Предмет сохранён."

class EliteMobsLuckyTicketIssuanceTest : StringSpec({
    "weekly lucky ticket is issued through ArcDungeonsService and retains native PDC" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            val eliteMobs = paper.createSimplePlugin("EliteMobs")
            if (!eliteMobs.isEnabled) paper.server.pluginManager.enablePlugin(eliteMobs)
            val player = paper.addPlayer("lucky-ticket-owner")
            val poolId = "lucky-ticket-${System.nanoTime()}"
            val commandReward = Treasure.Command(listOf(TICKET_COMMAND), id = "ticket")
            val pool = TreasurePool(poolId, treasures = listOf(commandReward))
            val customItemIdKey = NamespacedKey("elitemobs", "custom_item_id")
            val preservedMarker = NamespacedKey("test", "native_ticket_marker")
            val ticketFromProvider = ItemStack(Material.PAPER).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(customItemIdKey, PersistentDataType.STRING, TICKET_ID)
                    meta.persistentDataContainer.set(preservedMarker, PersistentDataType.STRING, "native-ticket-data")
                }
            }
            val dungeonService = mockk<ArcDungeonsService>()
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
            val rewards = CatalogPhysicalRewards(settings)
            paper.server.servicesManager.register(
                ArcDungeonsService::class.java,
                dungeonService,
                host,
                ServicePriority.Normal,
            )
            try {
                mockkObject(Treasures)
                every { Treasures.getPool(poolId) } returns pool
                every { dungeonService.nativeReady() } returns true
                every { dungeonService.createLuckyTicket(player) } returns ticketFromProvider.clone()

                val spec = requireNotNull(rewards.resolve(rewards.key(entry)))
                spec.preview.type shouldBe Material.TOTEM_OF_UNDYING
                rewards.canRedeem(player, spec) shouldBe null
                rewards.redeem(player, spec, UUID.randomUUID()).join() shouldBe PhysicalRewardOutcome.Applied

                val issued = player.inventory.storageContents.filterNotNull().single()
                issued.type shouldBe Material.PAPER
                issued.amount shouldBe 1
                issued.itemMeta?.persistentDataContainer?.get(customItemIdKey, PersistentDataType.STRING) shouldBe TICKET_ID
                issued.itemMeta?.persistentDataContainer?.get(preservedMarker, PersistentDataType.STRING) shouldBe "native-ticket-data"
                verify(exactly = 1) { dungeonService.createLuckyTicket(player) }

                val inventoryBeforeUnavailable = player.inventory.storageContents.map { it?.clone() }
                every { dungeonService.createLuckyTicket(player) } returns null
                rewards.redeem(player, spec, UUID.randomUUID()).join() shouldBe PhysicalRewardOutcome.Rejected(UNAVAILABLE)
                player.inventory.storageContents.map { it?.clone() } shouldBe inventoryBeforeUnavailable
                verify(exactly = 2) { dungeonService.createLuckyTicket(player) }
            } finally {
                paper.server.servicesManager.unregister(ArcDungeonsService::class.java, dungeonService)
                unmockkObject(Treasures)
            }
        }
    }
})
