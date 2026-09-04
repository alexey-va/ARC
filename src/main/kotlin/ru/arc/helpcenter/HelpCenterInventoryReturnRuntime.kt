package ru.arc.helpcenter

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Restores the originating help-center screen after a command-owned inventory flow ends.
 *
 * A request is inert until the command actually opens an inventory. Once an
 * inventory is observed, replacements remain part of the same flow and only
 * closing the latest screen consumes the one-shot return action.
 */
internal class HelpCenterInventoryReturnRuntime(
    private val plugin: Plugin,
    private val scheduler: TaskScheduler = BukkitTaskScheduler(plugin),
    private val openGraceTicks: Long = DEFAULT_OPEN_GRACE_TICKS,
) : Listener, AutoCloseable {
    private val store = HelpCenterInventoryReturnStore()
    private val tasks = LifecycleTaskScope(scheduler)
    private var closed = false

    init {
        require(openGraceTicks >= 1L) { "Inventory return open grace must be positive" }
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun arm(player: Player, returnAction: () -> Unit) {
        requirePrimaryThread()
        check(!closed) { "Help-center inventory return runtime is closed" }
        val nonce = store.arm(player.uniqueId, returnAction)
        tasks.runLater(openGraceTicks) {
            store.expireAwaitingOpen(player.uniqueId, nonce)
        }
    }

    fun cancel(player: Player) {
        requirePrimaryThread()
        store.cancel(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        requirePrimaryThread()
        if (closed) return
        val player = event.player as? Player ?: return
        store.observeOpen(player.uniqueId, event.view.topInventory)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        requirePrimaryThread()
        if (closed) return
        val player = event.player as? Player ?: return
        val inventory = event.view.topInventory
        val nonce = store.beginClose(player.uniqueId, inventory) ?: return

        // Other plugins may replace one inventory with another from their own
        // close handler. Waiting one tick lets the replacement open event win.
        tasks.runLater(1L) {
            val action = store.consumeClose(player.uniqueId, nonce, inventory) ?: return@runLater
            if (player.isOnline) action()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        store.cancel(event.player.uniqueId)
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        store.clear()
        tasks.close()
        scheduler.close()
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Help-center inventory return runtime must run on the primary server thread" }
    }

    private companion object {
        const val DEFAULT_OPEN_GRACE_TICKS = 40L
    }
}

internal class HelpCenterInventoryReturnStore {
    private data class Request(
        val nonce: Long,
        val returnAction: () -> Unit,
        var inventory: Inventory? = null,
    )

    private val serial = AtomicLong()
    private val requests = linkedMapOf<UUID, Request>()

    fun arm(playerId: UUID, returnAction: () -> Unit): Long {
        val nonce = serial.incrementAndGet()
        requests[playerId] = Request(nonce, returnAction)
        return nonce
    }

    fun observeOpen(playerId: UUID, inventory: Inventory): Boolean {
        val request = requests[playerId] ?: return false
        request.inventory = inventory
        return true
    }

    fun beginClose(playerId: UUID, inventory: Inventory): Long? =
        requests[playerId]?.takeIf { it.inventory === inventory }?.nonce

    fun consumeClose(playerId: UUID, nonce: Long, inventory: Inventory): (() -> Unit)? {
        val request = requests[playerId]
            ?.takeIf { it.nonce == nonce && it.inventory === inventory }
            ?: return null
        requests.remove(playerId)
        return request.returnAction
    }

    fun expireAwaitingOpen(playerId: UUID, nonce: Long): Boolean {
        val request = requests[playerId]
            ?.takeIf { it.nonce == nonce && it.inventory == null }
            ?: return false
        requests.remove(playerId)
        return true
    }

    fun cancel(playerId: UUID) {
        requests.remove(playerId)
    }

    fun clear() {
        requests.clear()
    }
}
