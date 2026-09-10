package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.ItemSettingsConfig
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecoveryWorkflow
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

/**
 * A backend-local recovery mailbox, not a reward generator. Only native, soulbound mob items qualify.
 * Despawn freezes one entity; an owned queue commits at most one bounded record per tick before removal.
 * Claims hold the main-thread inventory barrier through journal -> native player save -> tombstone.
 * Ambiguous claims never reissue an item: a persisted receipt proves delivery, otherwise operator review is required.
 */
internal class LostEliteLoot(
    root: Path,
    private val text: (String, String) -> Component,
    private val playerData: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    private val isElite: (ItemStack) -> Boolean = EliteItemManager::isEliteMobsItem,
    private val mobSource: () -> String = ItemSettingsConfig::getMobItemSource,
) : Listener, AutoCloseable {
    // Bootstrap occurs before this listener accepts items. The journal is the sole source of truth.
    internal val store = LostLootStore(root)
    private val tasks = LifecycleTaskScope()
    private val queued = linkedMapOf<UUID, Item>()
    private val incidents = mutableSetOf<String>()
    private var closed = false
    private val manualKey = NamespacedKey("arc", "lost_loot_manual")
    private val captureKey = NamespacedKey("arc", "lost_loot_capture")
    private val receiptKey = NamespacedKey("arc", "lost_loot_receipt")
    private val soulbindKey = NamespacedKey("elitemobs", "soulbind")
    private val sourceKey = NamespacedKey("elitemobs", "itemsource")
    private val caseKey = NamespacedKey("arc", "dungeon_case_reward")

    fun start() {
        Bukkit.getWorlds().forEach { world -> world.getEntitiesByClass(Item::class.java).forEach(::reconcileEntity) }
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
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun despawn(event: ItemDespawnEvent) {
        if (closed || (owner(event.entity) == null && !locked(event.entity))) return
        event.isCancelled = true
        if (store.get(event.entity.uniqueId.toString())?.let(store::available) == true) event.entity.remove()
        else enqueue(event.entity)
    }

    private fun enqueue(item: Item) {
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
        if (closed || !item.isValid) return
        val id = item.uniqueId.toString()
        val existing = store.get(id)
        if (store.certain(id)) { item.remove(); return }
        val owner = existing?.owner?.let(UUID::fromString) ?: owner(item) ?: return
        try {
            val record = existing ?: LostLootRecord(id, owner.toString(), encode(item.itemStack), System.currentTimeMillis())
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun worldUnloading(event: WorldUnloadEvent) {
        if (closed) return
        // Instance deletion may precede vanilla despawn. Commit while entities still exist;
        // reject unload on a storage failure rather than leaving the only copy in a deleted world.
        event.world.getEntitiesByClass(Item::class.java).filter { owner(it) != null || locked(it) }.forEach { item ->
            enqueue(item)
            queued.remove(item.uniqueId)
            capture(item)
            if (item.isValid) event.isCancelled = true
        }
    }

    private fun reconcileEntity(item: Item) {
        if (store.certain(item.uniqueId.toString())) item.remove()
        else if (item.persistentDataContainer.has(captureKey)) enqueue(item)
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

    @EventHandler(priority = EventPriority.MONITOR)
    fun joined(event: PlayerJoinEvent) {
        tasks.runLater(40) { if (event.player.isOnline) recoverClaims(event.player) }
    }

    internal fun recoverClaims(player: Player) {
        store.forOwner(player.uniqueId).filter { it.state == LostLootState.CLAIMING }.forEach { record ->
            if (player.persistentDataContainer.get(receiptKey, PersistentDataType.STRING) != record.claim) {
                incident("claim:${record.id}", "ambiguous-claim", player.uniqueId, record.id,
                    IllegalStateException("No persisted delivery receipt; record retained for operator review, no automatic reissue"))
                return@forEach
            }
            runCatching {
                playerData.persist(player)
                finishClaim(player, record)
            }.onFailure { incident("claim:${record.id}", "recover-claim", player.uniqueId, record.id, it) }
        }
    }

    internal fun claim(player: Player, id: String): Boolean {
        if (closed || !player.isOnline || player.isDead) return false
        val record = store.get(id)?.takeIf { it.owner == player.uniqueId.toString() && store.available(it) } ?: return false
        if (store.forOwner(player.uniqueId).any { it.state == LostLootState.CLAIMING }) return false
        val slot = player.inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
        if (slot < 0) { player.sendMessage(text("full", "<#f4d87a>Освободите один слот в инвентаре, чтобы забрать предмет.")); return false }
        return try {
            val stack = decode(requireNotNull(record.item))
            check(isElite(stack) && stack.itemMeta.persistentDataContainer.get(soulbindKey, PersistentDataType.STRING) == record.owner)
            val pending = record.copy(state = LostLootState.CLAIMING, claim = UUID.randomUUID().toString())
            val workflow = DurableRecoveryWorkflow<LostLootRecord, Unit>(
                commit = { CompletableFuture.completedFuture(store.write(it)) },
                sameContent = { a, b -> a == b },
                acknowledge = { _, _ -> CompletableFuture.completedFuture(DurableAcknowledgementOutcome.ACKNOWLEDGED) },
            )
            workflow.commitThenMutate(pending) {
                // No cursor or stacking writes: one intact item goes into a rechecked empty storage slot.
                check(player.inventory.getItem(slot)?.type?.isAir != false)
                player.inventory.setItem(slot, stack)
                player.persistentDataContainer.set(receiptKey, PersistentDataType.STRING, requireNotNull(pending.claim))
                player.updateInventory()
                playerData.persist(player)
                check(player.inventory.getItem(slot) == stack)
                CompletableFuture.completedFuture(Unit)
            }.join()
            finishClaim(player, pending)
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.2f)
            true
        } catch (failure: Exception) {
            incident("claim:$id", "claim", player.uniqueId, id, failure)
            player.sendMessage(text("pending", "<#f4d87a>Выдача требует проверки. Предмет сохранён в журнале; повторная выдача заблокирована."))
            false
        }
    }

    private fun finishClaim(player: Player, record: LostLootRecord) {
        store.write(record.copy(state = LostLootState.CLAIMED, item = null, claim = null))
        player.persistentDataContainer.remove(receiptKey)
        recovered("claim:${record.id}")
    }

    fun open(player: Player) {
        if (closed) return
        recoverClaims(player)
        val entries = store.forOwner(player.uniqueId)
        ArcMenus.open(player, ArcMenuSchema.LOST_LOOT, text("title", "<#c4a7e7>Потерянная добыча"),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.LOST_LOOT, "back")) {
                    it.closeInventory()
                    ru.arc.ARC.hookRegistry?.dungeonQol?.action(it, "menu")
                },
                "info" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.LOST_LOOT, "info"), enabled = false),
            ),
            regions = mapOf(ArcMenuSchema.LOST_LOOT_ITEMS to entries.mapNotNull { record ->
                val item = runCatching { decode(requireNotNull(record.item)) }.getOrElse { failure ->
                    incident("decode:${record.id}", "render", player.uniqueId, record.id, failure)
                    return@mapNotNull null
                }
                if (!store.available(record)) item.editMeta { meta ->
                    meta.lore(meta.lore().orEmpty() + text("pending-item", "<#f4d87a>⌛ Выдача на проверке"))
                }
                ArcMenus.entry(item, enabled = store.available(record)) { viewer ->
                    claim(viewer, record.id)
                    open(viewer)
                }
            }),
        )
        if (entries.isEmpty()) player.sendMessage(text("empty", "<#f2eee8>Потерянной добычи на этом сервере пока нет."))
    }

    private fun incident(key: String, operation: String, owner: UUID, id: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("LostEliteLoot operation={} player={} record={} retry=retained", operation, owner, id, failure)
    }
    private fun recovered(key: String) { if (incidents.remove(key)) Logging.info("LostEliteLoot recovered operation={}", key) }

    override fun close() { closed = true; tasks.close(); queued.clear() }

    private fun encode(item: ItemStack) = Base64.getEncoder().encodeToString(item.serializeAsBytes())
    private fun decode(value: String) = ItemStack.deserializeBytes(Base64.getDecoder().decode(value))
}
