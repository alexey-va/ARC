package ru.arc.eliteloot

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Logging
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal fun lostLootMobSource(source: String?, template: String): Boolean {
    val parts = template.split("\$mob", limit = 2)
    return source != null && parts.size == 2 && source.startsWith(parts[0]) && source.endsWith(parts[1]) &&
        source.length > parts[0].length + parts[1].length
}

/** Captures physical mob loot durably before removal, then publishes it idempotently to the shared mailbox. */
internal class LostEliteLoot(
    root: Path,
    private val publish: (LostLootRecord) -> CompletableFuture<Unit>,
    private val isElite: (ItemStack) -> Boolean,
    private val mobSource: () -> String,
    private val nativePrice: (ItemStack) -> Double? = { null },
) : Listener, AutoCloseable {
    // Bootstrap occurs before this listener accepts items. The journal is the sole source of truth.
    internal val store = LostLootStore(root)
    private val tasks = LifecycleTaskScope()
    private val queued = linkedMapOf<UUID, Item>()
    private val tracked = linkedMapOf<UUID, Item>()
    private val incidents = mutableSetOf<String>()
    private val publishing = mutableSetOf<String>()
    private val acknowledgements = mutableMapOf<String, LostLootRecord>()
    private var closed = false
    private val manualKey = NamespacedKey("arc", "lost_loot_manual")
    private val captureKey = NamespacedKey("arc", "lost_loot_capture")
    private val soulbindKey = NamespacedKey("elitemobs", "soulbind")
    private val sourceKey = NamespacedKey("elitemobs", "itemsource")
    private val caseKey = NamespacedKey("arc", "dungeon_case_reward")

    fun start() {
        Bukkit.getWorlds().forEach { world -> world.getEntitiesByClass(Item::class.java).forEach(::reconcileEntity) }
        tasks.runTimer(20, 20) { collectAbandoned(); publishPending() }
        tasks.runTimer(1, 1) {
            val next = queued.entries.firstOrNull() ?: return@runTimer
            queued.remove(next.key)
            capture(next.value)
        }
    }

    internal fun owner(item: Item): UUID? {
        if (item.thrower != null || item.persistentDataContainer.has(manualKey)) return null
        val stack = item.itemStack
        if (!isElite(stack)) return null
        val data = stack.itemMeta?.persistentDataContainer ?: return null
        if (data.has(caseKey) || !lostLootMobSource(data.get(sourceKey, PersistentDataType.STRING), mobSource())) return null
        return data.get(soulbindKey, PersistentDataType.STRING)?.let { raw ->
            runCatching { UUID.fromString(raw).takeIf { it.toString() == raw } }.getOrNull()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun manuallyDropped(event: PlayerDropItemEvent) {
        event.itemDrop.persistentDataContainer.set(manualKey, PersistentDataType.BYTE, 1)
        tracked.remove(event.itemDrop.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun spawned(event: ItemSpawnEvent) {
        // EliteMobs finishes the native source/soulbind metadata after spawning the entity.
        if (!closed) tracked[event.entity.uniqueId] = event.entity
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun ownerLeft(event: PlayerQuitEvent) {
        if (closed) return
        tracked.values.toList().filter { !it.isDead && owner(it) == event.player.uniqueId }.forEach(::enqueue)
    }

    private fun collectAbandoned() {
        tracked.values.toList().forEach { item ->
            val owner = if (item.isDead) null else owner(item)
            if (owner == null) {
                tracked.remove(item.uniqueId)
                return@forEach
            }
            val player = Bukkit.getPlayer(owner)
            if (player == null || !player.isOnline || player.world != item.world ||
                player.location.distanceSquared(item.location) > 64.0 * 64.0) enqueue(item)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun despawn(event: ItemDespawnEvent) {
        if (closed || (owner(event.entity) == null && !locked(event.entity))) return
        event.isCancelled = true
        if (store.get(event.entity.uniqueId.toString())?.let(store::available) == true) event.entity.remove()
        else enqueue(event.entity)
    }

    private fun enqueue(item: Item) {
        tracked.remove(item.uniqueId)
        item.persistentDataContainer.set(captureKey, PersistentDataType.BYTE, 1)
        item.setCanPlayerPickup(false)
        item.setCanMobPickup(false)
        item.setWillAge(false)
        item.isInvulnerable = true
        item.setGravity(false)
        item.velocity = org.bukkit.util.Vector()
        queued[item.uniqueId] = item
    }

    internal fun capture(item: Item) {
        queued.remove(item.uniqueId)
        tracked.remove(item.uniqueId)
        // Unloading entities are already untracked (isValid=false), but still alive and serializable.
        if (closed || item.isDead) return
        val id = item.uniqueId.toString()
        val existing = store.get(id)
        if (store.certain(id)) { item.remove(); return }
        val owner = existing?.owner?.let(UUID::fromString) ?: owner(item) ?: return
        try {
            val record = existing ?: LostLootRecord(id, owner.toString(), encode(item.itemStack), System.currentTimeMillis(), nativePrice = nativePrice(item.itemStack.clone()))
            // Frozen pickup/age plus this synchronous durability barrier prevent a world/mailbox double owner.
            store.write(record)
            item.remove()
            recovered("capture:$id")
        } catch (failure: Exception) {
            incident("capture:$id", "capture", owner, id, failure)
            tasks.runLater(600) { if (!closed && item.isValid) enqueue(item) }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun loaded(event: EntitiesLoadEvent) = event.entities.filterIsInstance<Item>().forEach(::reconcileEntity)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun entitiesUnloading(event: EntitiesUnloadEvent) {
        if (closed) return
        // The supplied entities are captured synchronously before Paper serializes this chunk.
        event.entities.filterIsInstance<Item>().forEach { captureBeforeUnload(it) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun worldUnloading(event: WorldUnloadEvent) {
        if (closed) return
        // Instance deletion may precede vanilla despawn. Commit while entities still exist;
        // reject unload on a storage failure rather than leaving the only copy in a deleted world.
        event.world.getEntitiesByClass(Item::class.java).forEach { item ->
            if (!captureBeforeUnload(item)) event.isCancelled = true
        }
    }

    private fun captureBeforeUnload(item: Item): Boolean {
        if (item.isDead || (owner(item) == null && !locked(item))) return true
        enqueue(item)
        capture(item)
        return item.isDead
    }

    private fun reconcileEntity(item: Item) {
        if (closed) return
        if (store.certain(item.uniqueId.toString())) item.remove()
        else if (item.persistentDataContainer.has(captureKey)) enqueue(item)
        else tracked[item.uniqueId] = item
    }

    private fun locked(item: Item) = item.persistentDataContainer.has(captureKey) || store.get(item.uniqueId.toString()) != null

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: EntityPickupItemEvent) { if (locked(event.item)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun hopper(event: InventoryPickupItemEvent) { if (locked(event.item)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun merge(event: ItemMergeEvent) {
        if (locked(event.entity) || locked(event.target) || owner(event.entity) != null || owner(event.target) != null) event.isCancelled = true
    }

    internal fun publishPending() {
        acknowledgements.values.take(4).forEach { record ->
            runCatching { store.write(record.copy(state = LostLootState.EXPORTED, item = null)) }
                .onSuccess { acknowledgements.remove(record.id); recovered("publish:${record.id}") }
                .onFailure { incident("publish:${record.id}", "export-ack", UUID.fromString(record.owner), record.id, it) }
        }
        store.pending(4).filter { publishing.add(it.id) }.forEach { record ->
            runCatching { publish(record) }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, failure ->
                publishing.remove(record.id)
                if (failure != null) {
                    incident("publish:${record.id}", "publish", UUID.fromString(record.owner), record.id, failure)
                } else {
                    acknowledgements[record.id] = record
                    runCatching { store.write(record.copy(state = LostLootState.EXPORTED, item = null)) }
                        .onSuccess { acknowledgements.remove(record.id); recovered("publish:${record.id}") }
                        .onFailure { incident("publish:${record.id}", "export-ack", UUID.fromString(record.owner), record.id, it) }
                }
            }
        }
    }

    private fun incident(key: String, operation: String, owner: UUID, id: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("LostEliteLoot operation={} player={} record={} retry=retained", operation, owner, id, failure)
    }
    private fun recovered(key: String) { if (incidents.remove(key)) Logging.info("LostEliteLoot recovered operation={}", key) }

    override fun close() { closed = true; tasks.close(); queued.clear(); tracked.clear() }

    private fun encode(item: ItemStack) = Base64.getEncoder().encodeToString(item.serializeAsBytes())
}
