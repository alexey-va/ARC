package ru.arc.dungeons

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.eliteloot.EliteLootManager
import ru.arc.eliteloot.presentEliteItem
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent

class ElitePickupListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun prepareLootSpawn(event: org.bukkit.event.entity.ItemSpawnEvent) {
        if (ArcDungeonsPlugin.instance.emHook == null) return
        try {
            // Assign the prepared stack even when the processor changes metadata in place.
            event.entity.itemStack = ru.arc.eliteloot.prepareEliteDrop(event.entity.itemStack)
        } catch (failure: Exception) {
            ru.arc.logging.ArcLogging.warn("EliteLoot drop preparation failed; native reward retained", failure)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLootSpawn(event: org.bukkit.event.entity.ItemSpawnEvent) {
        if (ArcDungeonsPlugin.instance.emHook != null) ru.arc.eliteloot.EliteLootEffects.drop(event.entity)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        (event.player as? Player)?.let(::refreshLater)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSlotChange(event: PlayerInventorySlotChangeEvent) = refreshLater(event.player)

    private val pending = mutableMapOf<java.util.UUID, ru.arc.core.LifecycleTaskScope.Token>()

    private fun refreshLater(player: Player) {
        if (ArcDungeonsPlugin.instance.emHook == null) return
        val tasks = ArcDungeonsPlugin.instance.taskScope
        val token = tasks.token()
        val playerId = player.uniqueId
        val previous = pending[playerId]
        if (previous != null && tasks.isCurrent(previous)) return
        pending[playerId] = token
        if (tasks.runLater(token, 1) {
            if (pending[playerId] == token) pending.remove(playerId)
            if (!player.isOnline) return@runLater
            val eliteReady = ArcDungeonsPlugin.instance.emHook != null &&
                com.magmaguy.elitemobs.playerdata.database.PlayerData.isInMemory(player.uniqueId)
            for (slot in 0 until player.inventory.size) {
                val original = player.inventory.getItem(slot) ?: continue
                if (!eliteReady) continue
                val copy = original.clone()
                if (copy.itemMeta?.persistentDataContainer?.has(org.bukkit.NamespacedKey("arc", "dungeon_case_reward")) == true) {
                    EliteLootManager.eliteLootProcessor?.processEliteLoot(copy, caseReward = true)
                }
                val updated = presentEliteItem(copy, player)
                if (!updated.isSimilar(original)) player.inventory.setItem(slot, updated)
            }
        } == null && pending[playerId] == token) pending.remove(playerId)
    }
}
