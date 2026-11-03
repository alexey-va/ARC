package ru.arc.graves

import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import java.util.UUID

/** A bounded client mask over rendered tombs. Actual world blocks are never changed or cached. */
internal class GraveBarriers(
    plugin: Plugin,
    private val server: String,
    private val radius: Int,
    private val current: (GraveView) -> Boolean,
    private val ready: (UUID) -> Boolean,
    private val open: (Player, UUID) -> Unit,
    private val feedback: (Player, String) -> Unit,
    scheduler: TaskScheduler = Tasks.scheduler,
    private val nanoTime: () -> Long = System::nanoTime,
) : Listener, AutoCloseable {
    private data class Cell(val x: Int, val y: Int, val z: Int)
    private class Viewer(val player: Player, val world: UUID) {
        val cells = linkedMapOf<Cell, GraveMarker>()
        var queued = false
        var lastOpen: Long? = null
    }
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val tasks = LifecycleTaskScope(scheduler)
    private val barrier = Material.BARRIER.createBlockData()
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        // Paper resends actual block data after use; unrelated block/chunk updates can also replace the mask.
        tasks.runTimer(20, 20) { viewers.values.toList().forEach(::refresh) }
    }

    /** Only the HUD's successfully rendered, ordered scenes may own a mask. */
    fun update(player: Player, rendered: List<GraveMarker>) {
        if (closed) return
        val old = viewers[player.uniqueId]
        if (old != null && (old.player !== player || old.world != player.world.uid)) clear(old.player)
        if (!player.isOnline || player.isDead) { clear(player); return }
        val viewer = viewers.getOrPut(player.uniqueId) { Viewer(player, player.world.uid) }
        val desired = linkedMapOf<Cell, GraveMarker>()
        rendered.take(MAX_MASKS).forEach { marker ->
            if (marker.height !in 1..2) return@forEach
            val grave = marker.grave
            for (y in grave.y until grave.y + marker.height) {
                val cell = Cell(grave.x, y, grave.z)
                if (block(viewer, marker, cell) != null) desired.putIfAbsent(cell, marker)
            }
        }
        (viewer.cells.keys - desired.keys).forEach { restore(viewer, it) }
        val changed = desired.filter { (cell, marker) -> viewer.cells[cell] != marker }
        viewer.cells.clear()
        viewer.cells.putAll(desired)
        changed.keys.forEach { cell ->
            player.sendBlockChange(Location(player.world, cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble()), barrier)
        }
        if (viewer.cells.isEmpty()) viewers.remove(player.uniqueId, viewer)
    }

    private fun block(viewer: Viewer, marker: GraveMarker, cell: Cell): Block? {
        val grave = marker.grave
        val player = viewer.player
        if (closed || !player.isOnline || player.isDead || player.world.uid != viewer.world ||
            grave.server != server || grave.world != player.world.name || !grave.active(System.currentTimeMillis()) || !current(grave) ||
            marker.height !in 1..2 || cell.x != grave.x || cell.z != grave.z || cell.y !in grave.y until grave.y + marker.height) return null
        val world = player.world
        if (grave.y < world.minHeight || grave.y > world.maxHeight - marker.height ||
            cell.y !in world.minHeight until world.maxHeight || !world.isChunkLoaded(grave.x shr 4, grave.z shr 4) ||
            !player.isChunkSent(Chunk.getChunkKey(grave.x shr 4, grave.z shr 4)) ||
            player.location.distanceSquared(Location(world, grave.x + .5, grave.y + .5, grave.z + .5)) > radius.toDouble() * radius) return null
        if ((0 until marker.height).any { !world.getBlockAt(grave.x, grave.y + it, grave.z).isPassable }) return null
        return world.getBlockAt(cell.x, cell.y, cell.z)
    }

    private fun refresh(viewer: Viewer) {
        if (closed || viewers[viewer.player.uniqueId] !== viewer) return
        val cells = viewer.cells.iterator()
        while (cells.hasNext()) {
            val (cell, marker) = cells.next()
            val actual = block(viewer, marker, cell)
            if (actual == null) { restore(viewer, cell); cells.remove() }
            else viewer.player.sendBlockChange(actual.location, barrier)
        }
        if (viewer.cells.isEmpty()) viewers.remove(viewer.player.uniqueId, viewer)
    }

    private fun reassert(viewer: Viewer) {
        if (viewer.queued) return
        viewer.queued = true
        tasks.runLater(1) {
            viewer.queued = false
            if (viewers[viewer.player.uniqueId] === viewer) refresh(viewer)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun interact(event: PlayerInteractEvent) {
        if (closed || event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val player = event.player
        val viewer = viewers[player.uniqueId]?.takeIf { it.player === player && it.world == player.world.uid } ?: return
        if (clicked.world.uid != viewer.world) return
        val cell = Cell(clicked.x, clicked.y, clicked.z)
        val marker = viewer.cells[cell] ?: return
        if (block(viewer, marker, cell) == null) {
            discard(viewer, marker.grave.id)
            if (viewer.cells.isEmpty()) viewers.remove(player.uniqueId, viewer)
            return
        }
        // Paper tracks block and item denial independently. Never lift an explicit item denial.
        val denied = event.useItemInHand() == Event.Result.DENY
        event.isCancelled = true
        reassert(viewer)
        if (event.hand != EquipmentSlot.HAND || denied) return
        requestOpen(viewer, marker.grave, listOf(cell))
    }

    /** Called on the Paper thread only for an exact, viewer-owned Yamipa frame ID. */
    fun interactFrame(player: Player, graveId: UUID) {
        if (closed) return
        val viewer = viewers[player.uniqueId]?.takeIf { it.player === player && it.world == player.world.uid } ?: return
        val cells = viewer.cells.filterValues { it.grave.id == graveId }
        val marker = cells.values.firstOrNull() ?: return
        if (cells.keys.any { block(viewer, marker, it) == null }) {
            discard(viewer, graveId)
            if (viewer.cells.isEmpty()) viewers.remove(player.uniqueId, viewer)
            return
        }
        reassert(viewer)
        requestOpen(viewer, marker.grave, cells.keys)
    }

    private fun discard(viewer: Viewer, graveId: UUID) {
        val cells = viewer.cells.filterValues { it.grave.id == graveId }.keys.toList()
        cells.forEach { viewer.cells.remove(it); restore(viewer, it) }
    }

    private fun requestOpen(viewer: Viewer, grave: GraveView, cells: Collection<Cell>) {
        val player = viewer.player
        if (cells.none { inReach(player, it) } || !ready(player.uniqueId)) {
            feedback(player, "interaction-unavailable")
            return
        }
        if (grave.ownerId != player.uniqueId) { feedback(player, "not-owner"); return }
        val now = nanoTime()
        if (viewer.lastOpen?.let { now - it < 250_000_000L } == true) return
        viewer.lastOpen = now
        open(player, grave.id)
    }

    private fun inReach(player: Player, cell: Cell): Boolean {
        val reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value
            ?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(6.0) ?: 4.5
        val eye = player.eyeLocation
        val dx = eye.x - eye.x.coerceIn(cell.x.toDouble(), cell.x + 1.0)
        val dy = eye.y - eye.y.coerceIn(cell.y.toDouble(), cell.y + 1.0)
        val dz = eye.z - eye.z.coerceIn(cell.z.toDouble(), cell.z + 1.0)
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    /** Always restore the current server block; another plugin/player may have changed it. */
    private fun restore(viewer: Viewer, cell: Cell) {
        val player = viewer.player
        if (!player.isOnline || player.world.uid != viewer.world || !player.world.isChunkLoaded(cell.x shr 4, cell.z shr 4) ||
            !player.isChunkSent(Chunk.getChunkKey(cell.x shr 4, cell.z shr 4))) return
        val actual = player.world.getBlockAt(cell.x, cell.y, cell.z)
        player.sendBlockChange(actual.location, actual.blockData)
    }

    fun clear(player: Player) {
        val viewer = viewers[player.uniqueId]?.takeIf { it.player === player } ?: return
        viewers.remove(player.uniqueId)
        viewer.cells.keys.forEach { restore(viewer, it) }
    }

    @EventHandler(priority = EventPriority.MONITOR) fun quit(event: PlayerQuitEvent) = clear(event.player)
    @EventHandler(priority = EventPriority.MONITOR) fun world(event: PlayerChangedWorldEvent) = clear(event.player)
    @EventHandler(priority = EventPriority.MONITOR) fun death(event: PlayerDeathEvent) = clear(event.entity)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun unload(event: WorldUnloadEvent) {
        viewers.values.filter { it.world == event.world.uid }.map { it.player }.forEach(::clear)
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun chunk(event: PlayerChunkLoadEvent) {
        viewers[event.player.uniqueId]?.takeIf { it.world == event.chunk.world.uid }?.let(::reassert)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        HandlerList.unregisterAll(this)
        viewers.values.toList().forEach { clear(it.player) }
    }

    private companion object { const val MAX_MASKS = 8 }
}
