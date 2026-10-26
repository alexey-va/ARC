package ru.arc.hooks.slimefun

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.event.player.PlayerTradeEvent
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.MerchantRecipe

class SlimefunTradeListenerTest : StringSpec({
    "Slimefun payment in either slot cancels villager and wandering trader trades with one warning" {
        for (slot in 0..1) {
            val player = mockk<Player>()
            val merchant = mockk<AbstractVillager>()
            val inventory = mockk<MerchantInventory>()
            val hunterTalisman = mockk<ItemStack>(name = "HUNTER_TALISMAN")
            every { player.openInventory.topInventory } returns inventory
            every { inventory.getItem(any()) } answers { if (firstArg<Int>() == slot) hunterTalisman else null }
            val event = PlayerTradeEvent(player, merchant, mockk<MerchantRecipe>(), true, true)
            var warnings = 0
            val listener = SlimefunTradeListener({ it === hunterTalisman }, { warnings++ })

            listener.onTrade(event)

            event.isCancelled shouldBe true
            warnings shouldBe 1
        }
    }

    "normal emeralds remain usable and result slot is not treated as payment" {
        val player = mockk<Player>()
        val inventory = mockk<MerchantInventory>()
        val emerald = mockk<ItemStack>()
        every { player.openInventory.topInventory } returns inventory
        every { inventory.getItem(0) } returns emerald
        every { inventory.getItem(1) } returns null
        val event = PlayerTradeEvent(player, mockk(), mockk(), true, true)
        val listener = SlimefunTradeListener({ false }, { error("Normal trade must not warn") })

        listener.onTrade(event)

        event.isCancelled shouldBe false
    }

    "two protected inputs produce only one warning" {
        val player = mockk<Player>()
        val inventory = mockk<MerchantInventory>()
        every { player.openInventory.topInventory } returns inventory
        every { inventory.getItem(any()) } returns mockk()
        val event = PlayerTradeEvent(player, mockk(), mockk(), true, true)
        var warnings = 0
        val listener = SlimefunTradeListener({ true }, { warnings++ })

        listener.onTrade(event)

        event.isCancelled shouldBe true
        warnings shouldBe 1
    }
})
