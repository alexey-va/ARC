package ru.arc.backwings

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.LongSupplier
import java.util.logging.Level

object BackWingsModule : PluginModule {
    override val name = "BackWings"
    override val priority = 88
    private var runtime: BackWingsRuntime? = null
    private var retirement = CompletableFuture.completedFuture<Void>(null)

    override fun init() {
        check(runtime == null) { "Back wings runtime already started" }
        val plugin = ARC.instance
        if (!plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) return
        val settings = BackWingsConfig.load(plugin.dataPath).snapshot(plugin.logger::warning)
        val displays = PaperPacketDisplays(plugin, "back-wings", plugin.nativeWork)
        val next = BackWingsRuntime(plugin, settings, displays, plugin.nativeWork)
        runtime = next
        try { next.start() } catch (failure: Throwable) { shutdown(); throw failure }
    }

    override fun reload() {
        val plugin = ARC.instance
        runtime?.reconfigure(BackWingsConfig.load(plugin.dataPath).snapshot(plugin.logger::warning)) ?: init()
    }

    override fun shutdown() {
        val previous = runtime ?: return
        runtime = null
        retirement = previous.closeAsync()
    }

    fun drained(): CompletableFuture<Void> = retirement.copy()
}

/**
 * Main-thread cosmetic state only. Inventory is read, never written. A selected ID lives in PDC;
 * the original item remains in storage. Core owns viewer, world and passenger replay.
 */
internal class BackWingsRuntime(
    private val plugin: Plugin,
    private var settings: BackWingsSettings,
    private val displays: PaperPacketDisplays,
    private val work: BudgetedTaskQueue,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(Tasks.scheduler, true),
    private val itemId: (ItemStack) -> String? = { CustomStack.byItemStack(it)?.namespacedID },
) : Listener, AutoCloseable {
    private class Worn(val player: Player, var wing: BackWing) {
        var display: PacketItemDisplay? = null
        var item: ItemStack? = null
        var nextOwnershipCheck = 0L
        var failed = false
    }

    private val worn = mutableMapOf<UUID, Worn>()
    private val bodyYaws = mutableMapOf<UUID, BackWingBodyYaw>()
    private val roundRobin = ArrayDeque<Worn>()
    private val waitingForSync = mutableSetOf<UUID>()
    private val huskSync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private var syncListener: Listener? = null
    private var pulse: CompletableFuture<Unit>? = null
    private var completion: CompletableFuture<Void>? = null
    private var tick = 0L
    private var started = false
    private var closed = false

    fun start() {
        check(!started && !closed) { "Back wings runtime is not fresh" }
        started = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (huskSync) syncListener = BackWingsSyncListener { id ->
            tasks.runSync {
                waitingForSync.remove(id)
                plugin.server.getPlayer(id)?.let(::restore)
            }
        }.also { plugin.server.pluginManager.registerEvents(it, plugin) }
        // Startup/reload maintenance; ordinary ticks visit one wearer per shared budget step.
        plugin.server.onlinePlayers.forEach { resetBody(it); restore(it) }
        tasks.runTimer(1L, 1L, ::refresh)
    }

    /** Update poses in place: config reload does not change the display's identity or selection. */
    fun reconfigure(next: BackWingsSettings) {
        settings = next
        worn.values.toList().forEach { reconcile(it, true) }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (closed || event.isCancelled || player.isSneaking) return
        // Event items are the prospective swapped contents. Read the actual main hand instead.
        val wing = identify(player.inventory.itemInMainHand) ?: return
        event.isCancelled = true
        if (player.isDead || player.uniqueId in waitingForSync) return
        if (selected(player) == wing) {
            player.persistentDataContainer.remove(SELECTION_KEY)
            remove(player.uniqueId)
            player.sendActionBar(settings.removedText)
        } else {
            player.persistentDataContainer.set(SELECTION_KEY, PersistentDataType.STRING, wing.itemId)
            val state = select(player, wing)
            reconcile(state, true)
            if (worn[player.uniqueId] === state && state.display != null) player.sendActionBar(settings.equippedText)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = invalidateInventory(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) { (event.whoClicked as? Player)?.let(::invalidateInventory) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) { (event.whoClicked as? Player)?.let(::invalidateInventory) }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        resetBody(event.player)
        if (huskSync) waitingForSync.add(event.player.uniqueId)
        else tasks.runLater(1L) { if (event.player.isOnline) restore(event.player) }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        waitingForSync.remove(event.player.uniqueId)
        bodyYaws.remove(event.player.uniqueId)
        remove(event.player.uniqueId)
    }

    @EventHandler fun onDeath(event: PlayerDeathEvent) = remove(event.entity.uniqueId)

    @EventHandler fun onRespawn(event: PlayerPostRespawnEvent) {
        resetBody(event.player)
        restore(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (closed || event.isCancelled || event.player.uniqueId in worn) return
        observeBody(event.player, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (!closed && !event.isCancelled) resetBody(event.player, event.to)
    }

    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) {
        resetBody(event.player)
        // Core follows the new carrier world and replays the same display. Never add a Bukkit passenger.
        worn[event.player.uniqueId]?.let { reconcile(it, true) } ?: restore(event.player)
    }

    private fun invalidateInventory(player: Player) {
        // Inventory events describe a not-yet-committed mutation; the next pulse sees final storage.
        worn[player.uniqueId]?.nextOwnershipCheck = 0L
    }

    internal fun restore(player: Player) {
        if (closed || !player.isOnline || player.isDead || player.uniqueId in waitingForSync) return
        val wing = selected(player)
        if (wing == null) remove(player.uniqueId)
        else reconcile(select(player, wing), true)
    }

    private fun select(player: Player, wing: BackWing): Worn {
        val state = worn[player.uniqueId]
        if (state != null && state.player === player) {
            if (state.wing != wing) state.nextOwnershipCheck = 0L
            state.wing = wing
            return state
        }
        remove(player.uniqueId)
        return Worn(player, wing).also { worn[player.uniqueId] = it; roundRobin.addLast(it) }
    }

    /** O(1) enqueue, one wearer per bounded native step, no per-tick online-player snapshot. */
    internal fun refresh() {
        tick++
        if (closed || pulse?.isDone == false || roundRobin.isEmpty()) return
        var remaining = roundRobin.size
        pulse = work.submit({ !closed }) {
            val state = roundRobin.pollFirst()
            if (state != null && worn[state.player.uniqueId] === state) {
                reconcile(state, tick >= state.nextOwnershipCheck)
                if (worn[state.player.uniqueId] === state) roundRobin.addLast(state)
            }
            --remaining <= 0
        }
        work.drainAvailable(LongSupplier { Bukkit.getCurrentTick().toLong() })
    }

    private fun reconcile(state: Worn, checkOwnership: Boolean) {
        val player = state.player
        if (closed || !player.isOnline || player.isDead) { remove(player.uniqueId); return }
        try {
            if (checkOwnership) {
                // Exactly 36 native storage slots; excludes nested containers, cursor, armor and offhand.
                val owned = player.inventory.storageContents.firstOrNull { identify(it) == state.wing }
                if (owned == null) {
                    player.persistentDataContainer.remove(SELECTION_KEY)
                    remove(player.uniqueId)
                    player.sendActionBar(settings.missingText)
                    return
                }
                val visual = owned.clone().also { it.amount = 1 }
                if (state.item?.isSimilar(visual) != true) {
                    state.item = visual
                    state.display?.itemStack = visual
                }
                state.nextOwnershipCheck = tick + 10L
            }
            val location = player.location
            val pose = backWingPose(
                settings.styles.getValue(state.wing), observeBody(player, location), player.pose == Pose.SNEAKING,
                player.height.toFloat(), settings.crouchOffset, settings.crouchPitchDegrees,
            )
            // FIXED DisplayRenderer applies the entity's Euler rotation before its transformation.
            // The pose already uses world axes; retaining the equip-time gaze would rotate it twice.
            val display = state.display ?: displays.spawnItem(location.apply {
                yaw = 0f
                pitch = 0f
            }, checkNotNull(state.item)).also {
                state.display = it
                it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                it.pauseWhenNotObserved = false
                // Interpolate each 20 Hz body pose at render-frame rate; passenger XYZ stays native.
                it.interpolationDelay = 0
                it.interpolationDuration = 1
                it.teleportDuration = 0
                // Zero bounds disables client frustum culling of the wide transformed mesh.
                it.displayWidth = 0f
                it.displayHeight = 0f
                it.transformation = pose
                it.attachTo(player)
            }
            display.transformation = pose
            state.failed = false
        } catch (failure: Exception) {
            state.display?.remove()
            state.display = null
            if (!state.failed) plugin.logger.log(Level.WARNING, "Back wings failed for ${player.uniqueId}; retrying", failure)
            state.failed = true
        }
    }

    private fun identify(item: ItemStack?): BackWing? =
        item?.takeUnless { it.type.isAir || it.amount <= 0 }?.let { BackWing.fromItemId(itemId(it)) }

    private fun selected(player: Player): BackWing? =
        BackWing.fromItemId(player.persistentDataContainer.get(SELECTION_KEY, PersistentDataType.STRING))

    private fun remove(playerId: UUID) { worn.remove(playerId)?.display?.remove() }

    private fun resetBody(player: Player, location: Location = player.location) {
        bodyYaws[player.uniqueId] = BackWingBodyYaw(player.bodyYaw, location.x, location.z, tick)
    }

    private fun observeBody(player: Player, location: Location): Float =
        bodyYaws.getOrPut(player.uniqueId) {
            BackWingBodyYaw(player.bodyYaw, location.x, location.z, tick)
        }.observe(tick, location.x, location.z, location.yaw, player.isBlocking)

    fun closeAsync(): CompletableFuture<Void> {
        completion?.let { return it.copy() }
        closed = true
        tasks.close()
        pulse?.cancel(false)
        HandlerList.unregisterAll(this)
        syncListener?.let(HandlerList::unregisterAll)
        syncListener = null
        waitingForSync.clear()
        bodyYaws.clear()
        worn.clear()
        roundRobin.clear()
        // Core's host-owned cleanup outlives the consumer queue and reports admitted packet cleanup.
        return displays.closeAsync().also { completion = it }.copy()
    }

    override fun close() { closeAsync() }

    companion object {
        internal val SELECTION_KEY = NamespacedKey("arc", "back-wings")
    }
}
