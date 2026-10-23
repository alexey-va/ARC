package ru.arc.dungeons

import com.magmaguy.elitemobs.playerdata.database.PlayerData
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.eliteloot.EliteLootManager
import ru.arc.eliteloot.presentEliteItem
import ru.arc.hooks.elitemobs.EMHook
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.logging.Level

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
    fun onSlotChange(event: PlayerInventorySlotChangeEvent) = refreshLater(event.player, event.slot, event.newItemStack)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (pending[event.player.uniqueId]?.player === event.player) pending.remove(event.player.uniqueId)
    }

    private class Refresh(
        val player: Player,
        val tasks: LifecycleTaskScope,
        val token: LifecycleTaskScope.Token,
        val hook: EMHook,
    ) {
        val slots = EliteInventoryRefreshSlots(player.inventory.size)
        // One matching event acknowledges our native write; full reconciliations always bypass it.
        val writes = mutableMapOf<Int, ItemStack>()
        var queued = false
        var failures = 0
    }

    private val plugin = ArcDungeonsPlugin.instance
    private val pending = mutableMapOf<UUID, Refresh>()
    private val caseRewardKey = NamespacedKey("arc", "dungeon_case_reward")

    private fun refreshLater(player: Player, slot: Int? = null, changed: ItemStack? = null) {
        val hook = plugin.emHook ?: return
        val playerId = player.uniqueId
        val refresh = pending[playerId]?.takeIf {
            it.player === player && it.hook === hook && it.tasks.isCurrent(it.token)
        } ?: plugin.taskScope.let { tasks ->
            Refresh(player, tasks, tasks.token(), hook).also { pending[playerId] = it }
        }
        if (slot == null) {
            refresh.slots.all()
            refresh.writes.clear()
        } else {
            if (refresh.writes.remove(slot)?.let { it == changed } == true) return
            refresh.slots.dirty(slot)
        }
        enqueue(refresh)
    }

    private fun current(refresh: Refresh): Boolean =
        refresh.tasks.isCurrent(refresh.token) && pending[refresh.player.uniqueId] === refresh &&
            plugin.emHook === refresh.hook && refresh.player.isOnline &&
            plugin.server.getPlayer(refresh.player.uniqueId) === refresh.player

    private fun enqueue(refresh: Refresh) {
        if (refresh.queued || !refresh.slots.hasWork) return
        refresh.queued = true
        var awaitPlayerData = false
        plugin.nativeWork.submit({ current(refresh) }) {
            if (!PlayerData.isInMemory(refresh.player.uniqueId)) {
                awaitPlayerData = true
                true
            } else {
                val slot = refresh.slots.next() ?: return@submit true
                try {
                    refreshSlot(refresh, slot)
                } catch (failure: Throwable) {
                    refresh.slots.dirty(slot)
                    throw failure
                }
                !refresh.slots.hasWork
            }
        }.whenComplete { _, failure ->
            refresh.queued = false
            if (!current(refresh)) {
                pending.remove(refresh.player.uniqueId, refresh)
            } else if (failure != null) {
                if (failure !is CancellationException) reportFailure(refresh, failure)
            } else if (awaitPlayerData) {
                // Keep the dirty set while native player data loads; never spin on readiness in the queue.
                try {
                    refresh.queued = refresh.tasks.runLater(refresh.token, 20L) {
                        refresh.queued = false
                        if (current(refresh)) enqueue(refresh)
                    } != null
                } catch (problem: RuntimeException) {
                    reportFailure(refresh, problem)
                }
            } else if (refresh.failures != 0) {
                plugin.logger.info("EliteLoot inventory refresh recovered; player=${refresh.player.uniqueId}; failedAttempts=${refresh.failures}")
                refresh.failures = 0
            }
        }
    }

    /** All item preparation and mutation stays on Paper's owning thread, one slot per shared-budget step. */
    private fun refreshSlot(refresh: Refresh, slot: Int) {
        val player = refresh.player
        val original = player.inventory.getItem(slot)?.clone() ?: return
        val copy = original.clone()
        if (copy.itemMeta?.persistentDataContainer?.has(caseRewardKey) == true) {
            EliteLootManager.eliteLootProcessor?.processEliteLoot(copy, caseReward = true)
        }
        val updated = presentEliteItem(copy, player)
        if (!current(refresh)) return
        if (player.inventory.getItem(slot) != original) {
            refresh.slots.dirty(slot)
        } else if (!updated.isSimilar(original)) {
            refresh.writes[slot] = updated.clone()
            try {
                player.inventory.setItem(slot, updated)
            } catch (failure: Throwable) {
                refresh.writes.remove(slot)
                throw failure
            }
        }
    }

    private fun reportFailure(refresh: Refresh, failure: Throwable) {
        if (++refresh.failures == 1) plugin.logger.log(Level.WARNING,
            "EliteLoot inventory refresh failed; player=${refresh.player.uniqueId}; dirty slots retained for next inventory event", failure)
    }
}

/** Feature-local dirty slots; a full pass absorbs unvisited slots while retaining changes behind its cursor. */
internal class EliteInventoryRefreshSlots(private val size: Int) {
    private val dirty = linkedSetOf<Int>()
    private var fullCursor = size
    val hasWork get() = fullCursor < size || dirty.isNotEmpty()

    fun all() {
        fullCursor = 0
        dirty.clear()
    }

    fun dirty(slot: Int) {
        if (slot in 0 until size && slot < fullCursor) dirty += slot
    }

    fun next(): Int? = if (fullCursor < size) fullCursor++ else dirty.firstOrNull()?.also { dirty.remove(it) }
}
