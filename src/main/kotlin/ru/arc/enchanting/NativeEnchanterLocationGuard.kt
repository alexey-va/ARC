package ru.arc.enchanting

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import ru.arc.ARC

/** AE 9.24.15's seller and confirmation both honor cancellation before their click handlers. */
internal class NativeEnchanterLocationGuard(
    private val config: EnchantingConfig,
    private val holders: Set<Class<*>>,
    private val serverName: () -> String = { ARC.serverName.orEmpty() },
) : Listener {
    constructor(provider: Plugin, config: EnchantingConfig) : this(config, kotlin.run {
        require(provider.description.version == "9.24.15") { "Unverified AE seller inventory contract" }
        setOf(
            Class.forName("net.advancedplugins.ae.features.enchanter.Enchanter\$Holder", false, provider.javaClass.classLoader),
            Class.forName("net.advancedplugins.ae.features.enchanter.ConfirmInventory\$ConfirmInvHolder", false, provider.javaClass.classLoader),
        )
    })

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (blocked(player, event.inventory)) {
            event.isCancelled = true
            player.sendMessage(config.text("shop.messages.spawn-only"))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (blocked(player, event.view.topInventory)) {
            event.isCancelled = true
            player.sendMessage(config.text("shop.messages.spawn-only"))
        }
    }

    private fun blocked(player: Player, inventory: Inventory): Boolean =
        inventory.getHolder(false)?.let { holders.contains(it.javaClass) } == true &&
            (serverName() != config.shopServerName || player.world.name != config.shopWorldName)
}
