package ru.arc.backwings

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.LongSupplier
import java.util.logging.Level

object BackWingsModule : PluginModule {
    override val name = "BackWings"
    override val priority = 88
    private var runtime: BackWingsRuntime? = null

    override fun init() {
        check(runtime == null) { "Back wings runtime already started" }
        val plugin = ARC.instance
        if (!plugin.server.pluginManager.isPluginEnabled("ItemsAdder") || !CosmeticsCoreBackWings.available(plugin)) return
        val next = BackWingsRuntime(plugin, BackWingsConfig.load(plugin.dataPath).snapshot(),
            CosmeticsCoreBackWings(plugin), plugin.nativeWork)
        runtime = next
        try { next.start() } catch (failure: Throwable) { shutdown(); throw failure }
    }

    override fun reload() {
        runtime?.reconfigure(BackWingsConfig.load(ARC.instance.dataPath).snapshot()) ?: init()
    }

    override fun shutdown() { runtime?.close(); runtime = null }

    // Kept for ARC's module cleanup contract; CosmeticsCore owns all visual lifecycle work.
    fun drained(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
}

/** Item entitlement/F shortcut only. CosmeticsCore owns rendering, equipped state and persistence. */
internal class BackWingsRuntime(
    private val plugin: Plugin,
    private var settings: BackWingsSettings,
    private val cosmetics: CosmeticsCoreBackWings,
    private val work: BudgetedTaskQueue,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(Tasks.scheduler, true),
    private val itemId: (ItemStack) -> String? = { CustomStack.byItemStack(it)?.namespacedID },
) : Listener, AutoCloseable {
    private class Holder(val player: Player) {
        var owned: Set<BackWing> = emptySet()
        var attachment: PermissionAttachment? = null
        var failed = false
    }

    private val holders = mutableMapOf<UUID, Holder>()
    private val roundRobin = ArrayDeque<Holder>()
    private val waitingForSync = mutableSetOf<UUID>()
    private val huskSync = plugin.server.pluginManager.isPluginEnabled("HuskSync")
    private var syncListener: Listener? = null
    private var pulse: CompletableFuture<Unit>? = null
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
        // One startup enumeration; each subsequent inventory check is one bounded native step.
        plugin.server.onlinePlayers.forEach(::track)
        tasks.runTimer(1L, 10L, ::refresh)
    }

    fun reconfigure(next: BackWingsSettings) { settings = next; refresh() }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (closed || event.isCancelled || player.isSneaking) return
        // Event items are the prospective swapped contents; neither event hand is modified.
        val wing = identify(player.inventory.itemInMainHand) ?: return
        event.isCancelled = true
        if (player.isDead || player.uniqueId in waitingForSync) return
        val holder = track(player)
        try {
            updatePermissions(holder, if (cosmetics.isAvailable()) owned(player) else emptySet())
            val current = cosmetics.equipped(player)
            if (wing !in holder.owned || current == null) {
                player.sendActionBar(settings.unavailableText)
                return
            }
            val equip = wing !in current
            val removedOthers = !equip || (current - wing).all { cosmetics.setEquipped(player, it, false) }
            if (removedOthers && cosmetics.setEquipped(player, wing, equip)) {
                player.persistentDataContainer.remove(SELECTION_KEY)
                player.sendActionBar(if (equip) settings.equippedText else settings.removedText)
                holder.failed = false
            } else player.sendActionBar(settings.unavailableText)
        } catch (failure: Exception) {
            failure(holder, failure)
            player.sendActionBar(settings.unavailableText)
        }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        track(event.player)
        if (huskSync) waitingForSync.add(event.player.uniqueId)
        else tasks.runLater(1L) { if (event.player.isOnline) restore(event.player) }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        waitingForSync.remove(event.player.uniqueId)
        holders.remove(event.player.uniqueId)?.let(::revokePermissions)
    }

    @EventHandler fun onDeath(event: PlayerDeathEvent) {
        holders[event.entity.uniqueId]?.let(::revokePermissions)
    }

    @EventHandler fun onRespawn(event: PlayerPostRespawnEvent) = restore(event.player)

    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = restore(event.player)

    internal fun restore(player: Player) {
        if (!closed && player.isOnline) reconcile(track(player))
    }

    private fun track(player: Player): Holder {
        holders[player.uniqueId]?.let { previous ->
            if (previous.player === player) return previous
            revokePermissions(previous)
        }
        return Holder(player).also { holders[player.uniqueId] = it; roundRobin.addLast(it) }
    }

    /** No motion ticker or per-tick online-player snapshots. Also discovers newly acquired items. */
    internal fun refresh() {
        if (closed || pulse?.isDone == false || roundRobin.isEmpty()) return
        var remaining = roundRobin.size
        pulse = work.submit({ !closed }) {
            val holder = roundRobin.pollFirst()
            if (holder != null && holders[holder.player.uniqueId] === holder) {
                reconcile(holder)
                if (holders[holder.player.uniqueId] === holder) roundRobin.addLast(holder)
            }
            --remaining <= 0
        }
        work.drainAvailable(LongSupplier { Bukkit.getCurrentTick().toLong() })
    }

    private fun reconcile(holder: Holder) {
        val player = holder.player
        if (!player.isOnline) {
            holders.remove(player.uniqueId)
            revokePermissions(holder)
            return
        }
        if (player.isDead || player.uniqueId in waitingForSync || !cosmetics.isAvailable()) {
            revokePermissions(holder)
            return
        }
        try {
            updatePermissions(holder, owned(player))
            val current = cosmetics.equipped(player) ?: return
            var removed = false
            for (wing in current - holder.owned) {
                check(cosmetics.setEquipped(player, wing, false)) { "CosmeticsCore did not remove ${wing.cosmeticId}" }
                removed = true
            }
            if (removed) player.sendActionBar(settings.missingText)
            migratePreference(player, holder.owned, current.intersect(holder.owned))
            holder.failed = false
        } catch (failure: Exception) { failure(holder, failure) }
    }

    private fun migratePreference(player: Player, owned: Set<BackWing>, current: Set<BackWing>) {
        val raw = player.persistentDataContainer.get(SELECTION_KEY, PersistentDataType.STRING) ?: return
        val legacy = BackWing.fromItemId(raw)
        // An existing vendor choice wins. Once migrated, native GUI unequip must stay unequipped.
        if (legacy == null || legacy !in owned || current.isNotEmpty() || cosmetics.setEquipped(player, legacy, true)) {
            player.persistentDataContainer.remove(SELECTION_KEY)
        }
    }

    private fun owned(player: Player): Set<BackWing> =
        player.inventory.storageContents.mapNotNullTo(mutableSetOf(), ::identify)

    private fun identify(item: ItemStack?): BackWing? =
        item?.takeUnless { it.type.isAir || it.amount <= 0 }?.let { BackWing.fromItemId(itemId(it)) }

    private fun updatePermissions(holder: Holder, owned: Set<BackWing>) {
        if (holder.owned == owned) return
        if (owned.isEmpty()) { revokePermissions(holder); return }
        val attachment = holder.attachment ?: holder.player.addAttachment(plugin).also { holder.attachment = it }
        for (wing in holder.owned - owned) wing.permissions.forEach(attachment::unsetPermission)
        for (wing in owned - holder.owned) wing.permissions.forEach { attachment.setPermission(it, true) }
        holder.owned = owned
    }

    private fun revokePermissions(holder: Holder) {
        holder.attachment?.let(holder.player::removeAttachment)
        holder.attachment = null
        holder.owned = emptySet()
    }

    private fun failure(holder: Holder, failure: Exception) {
        if (!holder.failed) plugin.logger.log(Level.WARNING, "Back wings integration failed for ${holder.player.uniqueId}; retrying", failure)
        holder.failed = true
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        pulse?.cancel(false)
        HandlerList.unregisterAll(this)
        syncListener?.let(HandlerList::unregisterAll)
        syncListener = null
        holders.values.forEach(::revokePermissions)
        holders.clear()
        roundRobin.clear()
        waitingForSync.clear()
        // Vendor selections persist; ARC owns neither their renderer nor their saved wardrobe.
    }

    companion object {
        internal val SELECTION_KEY = NamespacedKey("arc", "back-wings")
    }
}
