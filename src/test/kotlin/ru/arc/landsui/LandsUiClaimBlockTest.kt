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
    "claim block grant uses the native owner item and rejects duplicates or a full inventory" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            val integration = mockk<LandsIntegration>()
            val landPlayer = mockk<LandPlayer>()
            val factory = mockk<LandsIntegrationFactory>()
            every { integration.getLandPlayer(player.uniqueId) } returns landPlayer
            val block = ItemStack(Material.MAGENTA_GLAZED_TERRACOTTA).apply {
                editMeta {
                    it.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    it.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0)
                    it.persistentDataContainer.set(NamespacedKey("lands", "o"), PersistentDataType.STRING, player.uniqueId.toString())
                }
            }
            mockkStatic(APIHandler::class)
            try {
                every { APIHandler.getLandsIntegrationFactory() } returns factory
                every { factory.buildItemStack(ItemType.CLAIM_BLOCK, landPlayer) } returns block
                val gateway = BukkitLandsUiGateway(integration)
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.GIVEN
                player.inventory.getItem(0) shouldBe block
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.ALREADY_PRESENT
                player.inventory.clear()
                player.inventory.setItemInOffHand(block)
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.ALREADY_PRESENT
                player.inventory.clear()
                for (slot in 0..35) player.inventory.setItem(slot, ItemStack(Material.STONE, 64))
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.INVENTORY_FULL
                player.inventory.clear()
                // Reopening the menu or recreating its gateway does not reset the grant cooldown.
                BukkitLandsUiGateway(integration).giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.COOLDOWN
                every { integration.getLandPlayer(player.uniqueId) } returns null
                gateway.giveClaimBlock(player) shouldBe LandsUiClaimBlockResult.UNAVAILABLE
                verify(exactly = 1) { factory.buildItemStack(ItemType.CLAIM_BLOCK, landPlayer) }
            } finally {
                unmockkStatic(APIHandler::class)
            }
        }
    }
})
