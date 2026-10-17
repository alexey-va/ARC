package ru.arc.eliteloot

import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal val lostLootManualKey = NamespacedKey("arc", "lost_loot_manual")
internal val lostLootCaptureKey = NamespacedKey("arc", "lost_loot_capture")
internal val lostLootSoulbindKey = NamespacedKey("elitemobs", "soulbind")
internal val lostLootSourceKey = NamespacedKey("elitemobs", "itemsource")
internal val lostLootCaseKey = NamespacedKey("arc", "dungeon_case_reward")

/**
 * Registered to the existing ArcCore host, not the disabling consumer. Its only retained state
 * is the last UUID index. Startup/failure/disable additionally holds bound native EliteMobs
 * drops; unrelated vanilla loot and deliberate player drops remain usable.
 */
internal class LostLootProtection(private var index: LostLootIndex = LostLootIndex()) : Listener {
    private var unknown = true

    fun starting() { unknown = true }
    fun attach(ready: LostLootIndex) { index = ready; unknown = false }
    fun handoff(): LostLootProtection = LostLootProtection(index)

    fun locked(item: Item): Boolean = index.get(item.uniqueId.toString()) != null ||
        item.persistentDataContainer.has(lostLootCaptureKey) || (unknown && nativeBoundOwner(item) != null)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: EntityPickupItemEvent) { if (locked(event.item)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun hopper(event: InventoryPickupItemEvent) { if (locked(event.item)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun merge(event: ItemMergeEvent) { if (locked(event.entity) || locked(event.target)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun despawn(event: ItemDespawnEvent) { if (locked(event.entity)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun manual(event: PlayerDropItemEvent) {
        event.itemDrop.persistentDataContainer.set(lostLootManualKey, PersistentDataType.BYTE, 1)
    }
}

/** Native namespace and canonical binding identify this feature even before EliteMobs is ready. */
internal fun nativeBoundOwner(item: Item): UUID? {
    if (item.thrower != null || item.persistentDataContainer.has(lostLootManualKey)) return null
    val data = item.itemStack.itemMeta?.persistentDataContainer ?: return null
    if (data.has(lostLootCaseKey) || data.get(lostLootSourceKey, PersistentDataType.STRING).isNullOrBlank()) return null
    return data.get(lostLootSoulbindKey, PersistentDataType.STRING)?.let { raw ->
        runCatching { UUID.fromString(raw).takeIf { it.toString() == raw } }.getOrNull()
    }
}
