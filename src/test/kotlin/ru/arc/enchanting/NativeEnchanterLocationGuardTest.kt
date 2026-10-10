package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class NativeEnchanterLocationGuardTest : StringSpec({
    "native seller cannot open outside spawn, including the same world name on another backend" {
        for ((server, world) in listOf("survival" to "rc_origin_spawn", "spawn" to "world")) {
            val (config, player, inventory) = sellerFixture(world)
            val guard = NativeEnchanterLocationGuard(config, setOf(SellerHolder::class.java)) { server }
            val event = mockk<InventoryOpenEvent>(relaxed = true)
            every { event.player } returns player
            every { event.inventory } returns inventory
            guard.onOpen(event)
            verify { event.isCancelled = true }
        }
    }

    "native confirmation rechecks the world at the purchase click" {
        val (config, player, inventory) = sellerFixture("world")
        val guard = NativeEnchanterLocationGuard(config, setOf(SellerHolder::class.java)) { "spawn" }
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.whoClicked } returns player
        every { event.view.topInventory } returns inventory
        guard.onClick(event)
        verify { event.isCancelled = true }
    }

    "spawn purchases and unrelated inventories retain their handlers" {
        for ((world, guarded) in listOf("rc_origin_spawn" to true, "world" to false)) {
            val (config, player, inventory) = sellerFixture(world)
            val guard = NativeEnchanterLocationGuard(config, if (guarded) setOf(SellerHolder::class.java) else emptySet()) { "spawn" }
            val event = mockk<InventoryOpenEvent>(relaxed = true)
            every { event.player } returns player
            every { event.inventory } returns inventory
            guard.onOpen(event)
            verify(exactly = 0) { event.isCancelled = any() }
        }
    }
})

private class SellerHolder : InventoryHolder {
    override fun getInventory(): Inventory = error("No holder inventory access expected")
}

private fun sellerFixture(world: String): Triple<EnchantingConfig, Player, Inventory> {
    val config = mockk<EnchantingConfig>()
    every { config.shopServerName } returns "spawn"
    every { config.shopWorldName } returns "rc_origin_spawn"
    every { config.text("shop.messages.spawn-only") } returns Component.text("Only at spawn")
    val player = mockk<Player>(relaxed = true)
    every { player.world.name } returns world
    val inventory = mockk<Inventory>()
    every { inventory.getHolder(false) } returns SellerHolder()
    return Triple(config, player, inventory)
}
