package ru.arc.barrierremover

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.paper.protection.PaperBlockProtection
import ru.arc.util.Logging.error
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Native owner of the existing CMI kit's custom_data marker; no permission or item-name gate. */
object BarrierRemoverModule : PluginModule {
    override val name = "BarrierRemover"
    override val priority = 85
    private var runtime: BarrierRemover? = null

    override fun init() {
        shutdown()
        runtime = BarrierRemover(ARC.instance.nativeWork)
        runtime?.start(ARC.instance)
    }

    override fun reload() = init()

    override fun shutdown() {
        runtime?.close()
        runtime = null
    }
}

/**
 * Paper-thread only. The host queue shares its count/time budget across holders and features.
 * Each step reads at most one block or emits one marker; scans retain only the nearest 64 hits.
 * Cached hits refresh every ten ticks while an unfinished scan is retained instead of duplicated.
 */
internal class BarrierRemover(
    private val work: BudgetedTaskQueue,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val marked: (ItemStack?) -> Boolean = Companion::hasMarker,
) : Listener, AutoCloseable {
    private class Holder {
        var barriers: List<Location> = emptyList()
        var scan: CompletableFuture<Unit>? = null
        var emission: CompletableFuture<Unit>? = null

        fun cancel() {
            scan?.cancel(false)
            emission?.cancel(false)
        }
    }

    private val holders = mutableMapOf<UUID, Holder>()
    private val cooldown = mutableSetOf<UUID>()
    private var polling: CompletableFuture<Unit>? = null
    private var active = false

    fun start(plugin: Plugin) {
        check(!active) { "Barrier remover already started" }
        active = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(10L, 10L, ::refresh)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun remove(event: PlayerInteractEvent) {
        if (!active || event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        // Paper can pre-deny block use for a vanilla no-op. Item-use DENY is the explicit
        // interaction veto; BlockBreakEvent below remains the authority for breaking.
        if (event.useItemInHand() == Event.Result.DENY) return
        val block = event.clickedBlock?.takeIf { it.type == Material.BARRIER } ?: return
        val player = event.player
        if (!marked(event.item) || !marked(player.inventory.itemInMainHand)) return
        event.isCancelled = true
        if (!cooldown.add(player.uniqueId)) return
        tasks.runLater(2L) { cooldown.remove(player.uniqueId) }

        if (PaperBlockProtection.isProtected(block)) {
            deny(player)
            return
        }
        val breaking = BlockBreakEvent(block, player).apply { isDropItems = false; expToDrop = 0 }
        Bukkit.getPluginManager().callEvent(breaking)
        if (breaking.isCancelled || block.type != Material.BARRIER || PaperBlockProtection.isProtected(block)) {
            deny(player)
            return
        }
        block.setType(Material.AIR, false)
        val center = block.location.add(0.5, 0.5, 0.5)
        player.spawnParticle(Particle.DUST, center, 18, 0.4, 0.4, 0.4, BREAK_DUST)
        player.playSound(center, "minecraft:block.glass.break", 0.8f, 1.2f)
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        holders.remove(event.player.uniqueId)?.cancel()
        cooldown.remove(event.player.uniqueId)
    }

    private fun refresh() {
        if (polling?.isDone == false) return
        val players = Bukkit.getOnlinePlayers().toList().iterator()
        polling = observe(work.submit({ active }) {
            if (players.hasNext()) {
                val player = players.next()
                if (!player.isOnline || !marked(player.inventory.itemInMainHand)) {
                    holders.remove(player.uniqueId)?.cancel()
                } else {
                    val holder = holders.getOrPut(player.uniqueId, ::Holder)
                    emit(player, holder)
                    if (holder.scan?.isDone != false) scan(player, holder)
                }
            }
            !players.hasNext()
        })
    }

    private fun scan(player: Player, holder: Holder) {
        val origin = player.location
        val world = origin.world ?: return
        val nearest = PriorityQueue<Location>(compareByDescending { it.distanceSquared(origin) })
        var index = 0
        holder.scan = observe(work.submit({ current(player, holder) && player.world == world }) {
            val x = origin.blockX + index % WIDTH - RADIUS
            val y = origin.blockY + index / (WIDTH * WIDTH) - RADIUS
            val z = origin.blockZ + index / WIDTH % WIDTH - RADIUS
            index++
            val center = Location(world, x + 0.5, y + 0.5, z + 0.5)
            if (y in world.minHeight until world.maxHeight && center.distanceSquared(origin) <= RADIUS_SQUARED &&
                world.isChunkLoaded(x shr 4, z shr 4) && world.getBlockAt(x, y, z).type == Material.BARRIER) {
                nearest.add(center.subtract(0.5, 0.5, 0.5))
                if (nearest.size > MAX_MARKERS) nearest.poll()
            }
            if (index == WIDTH * WIDTH * WIDTH) {
                val first = holder.barriers.isEmpty()
                holder.barriers = nearest.toList()
                if (first) emit(player, holder)
                true
            } else false
        })
    }

    private fun emit(player: Player, holder: Holder) {
        if (holder.barriers.isEmpty() || holder.emission?.isDone == false) return
        val barriers = holder.barriers.iterator()
        holder.emission = observe(work.submit({ current(player, holder) }) {
            if (!marked(player.inventory.itemInMainHand)) return@submit true
            val location = barriers.next()
            val world = location.world ?: return@submit !barriers.hasNext()
            val center = location.clone().add(0.5, 0.5, 0.5)
            if (world == player.world && center.distanceSquared(player.location) <= RADIUS_SQUARED &&
                world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4) && location.block.type == Material.BARRIER) {
                player.spawnParticle(Particle.DUST, center, 6, 0.45, 0.45, 0.45, HIGHLIGHT_DUST)
            }
            !barriers.hasNext()
        })
    }

    private fun current(player: Player, holder: Holder): Boolean =
        active && player.isOnline && holders[player.uniqueId] === holder

    private fun observe(future: CompletableFuture<Unit>): CompletableFuture<Unit> = future.also {
        it.whenComplete { _, failure ->
            if (failure != null && failure !is CancellationException) error("Barrier remover native refresh failed", failure)
        }
    }

    override fun close() {
        active = false
        HandlerList.unregisterAll(this)
        tasks.close()
        polling?.cancel(false)
        holders.values.forEach(Holder::cancel)
        holders.clear()
        cooldown.clear()
    }

    companion object {
        internal const val MARKER = "arc:barrier_remover"
        private const val RADIUS = 8
        private const val RADIUS_SQUARED = 64.0
        private const val WIDTH = RADIUS * 2 + 1
        private const val MAX_MARKERS = 64
        private val HIGHLIGHT_DUST = Particle.DustOptions(Color.RED, 0.8f)
        private val BREAK_DUST = Particle.DustOptions(Color.RED, 1.0f)

        internal fun hasMarker(item: ItemStack?): Boolean =
            item != null && !item.type.isAir && NBT.get<Boolean>(item) { hasMarker(it) }

        internal fun hasMarker(data: ReadableNBT): Boolean = data.hasTag(MARKER)

        private fun deny(player: Player) {
            player.sendActionBar(Component.text("Здесь нельзя ломать барьеры.", NamedTextColor.RED))
            player.playSound(player.location, "minecraft:block.note_block.bass", 0.6f, 0.7f)
        }
    }
}
