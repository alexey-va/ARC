package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.handler.APIHandler
import me.angeschossen.lands.api.handler.LandsIntegrationFactory
import me.angeschossen.lands.api.items.ItemType
import me.angeschossen.lands.api.player.LandPlayer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class LandsUiClaimBlockTest : StringSpec({
    "claim block command cannot issue a tool without its active placement handler" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("Lands")
            LandsUiModule.shutdown()
            LandsUiModule.createClaimBlockItem(runtime.server.addPlayer()) shouldBe null
        }
    }

    "ARC claim block grant ignores old cooldowns and rejects duplicates or a full inventory" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            val integration = mockk<LandsIntegration>()
            val landPlayer = mockk<LandPlayer>()
            val factory = mockk<LandsIntegrationFactory>()
            every { integration.getLandPlayer(player.uniqueId) } returns landPlayer
            mockkStatic(APIHandler::class)
            try {
                every { APIHandler.getLandsIntegrationFactory() } returns factory
                val gateway = BukkitLandsUiGateway(integration)
                val cooldownKey = NamespacedKey("arc", "lands_claim_block_next_at")
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.GIVEN
                val block = checkNotNull(player.inventory.getItem(0))
                block.type shouldBe Material.GRASS_BLOCK
                block.amount shouldBe 1
                val itemData = block.itemMeta.persistentDataContainer
                itemData.get(NamespacedKey("arc", "claim_block"), PersistentDataType.BYTE) shouldBe 1.toByte()
                itemData.get(NamespacedKey("arc", "claim_block_radius"), PersistentDataType.INTEGER) shouldBe 0
                itemData.get(NamespacedKey("arc", "claim_block_owner"), PersistentDataType.STRING) shouldBe player.uniqueId.toString()
                itemData.keys.none { it.namespace == "lands" } shouldBe true
                player.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) shouldBe null
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.ALREADY_PRESENT
                player.inventory.clear()
                player.inventory.setItemInOffHand(block)
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.ALREADY_PRESENT
                player.inventory.clear()
                for (slot in 0..35) player.inventory.setItem(slot, ItemStack(Material.STONE, 64))
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.INVENTORY_FULL
                player.inventory.clear()
                player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, Long.MAX_VALUE)
                BukkitLandsUiGateway(integration).giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.GIVEN
                player.inventory.getItem(0) shouldBe block
                player.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) shouldBe Long.MAX_VALUE
                every { integration.getLandPlayer(player.uniqueId) } returns null
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.UNAVAILABLE
                verify(exactly = 0) { APIHandler.getLandsIntegrationFactory() }
                verify(exactly = 0) { factory.buildItemStack(ItemType.CLAIM_BLOCK, landPlayer) }
            } finally {
                unmockkStatic(APIHandler::class)
            }
        }
    }
})
