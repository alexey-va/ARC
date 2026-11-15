package ru.arc.invsnapshots

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

internal const val SNAPSHOT_VIEW_PERMISSION = "arc.invlist"
internal const val SNAPSHOT_COPY_PERMISSION = "arc.invlist.copy"

/** Snapshots are detached. All mutations are scoped to the inspecting player. */
internal class InventorySnapshot(
    val id: UUID,
    val timestamp: Instant,
    val server: String,
    val cause: String,
    val pinned: Boolean,
    contents: List<ItemStack?>?,
    heldItemSlot: Int = 0,
    val exactSlotOrder: Boolean = true,
) {
    val inventory = contents?.let { SnapshotInventory(it, heldItemSlot) }
    val hasInventory: Boolean get() = inventory != null
    fun item(slot: Int): ItemStack? = inventory?.item(slot)
}

internal class SnapshotInventory(contents: List<ItemStack?>, private val heldItemSlot: Int) {
    private val slots = contents.map { it?.takeUnless(ItemStack::isEmpty)?.clone() }
    // HuskSync 3.8.x supplies 41-slot historical inventories. Modern Paper also exposes BODY and SADDLE.
    fun fits(player: Player): Boolean =
        (slots.size == 41 || slots.size == 43) && slots.size <= player.inventory.size && heldItemSlot in 0..8
    fun item(slot: Int): ItemStack? = slots.getOrNull(slot)?.clone()

    fun matches(player: Player): Boolean {
        val current = player.inventory
        return current.heldItemSlot == heldItemSlot && current.contents.let { actual ->
            actual.size >= slots.size && slots.indices.all { slots[it] == actual[it]?.takeUnless(ItemStack::isEmpty) }
        }
    }

    fun apply(player: Player) {
        for (slot in slots.indices) player.inventory.setItem(slot, item(slot))
        player.inventory.heldItemSlot = heldItemSlot
        player.updateInventory()
        check(matches(player)) { "Inventory load did not restore all slots" }
    }

    companion object {
        fun capture(player: Player) = SnapshotInventory(player.inventory.contents.asList(), player.inventory.heldItemSlot)
    }
}

internal data class SnapshotHistory(
    val playerId: UUID,
    val playerName: String,
    val snapshots: List<InventorySnapshot>,
)

internal fun interface InventorySnapshotSource {
    fun find(playerName: String): CompletionStage<SnapshotHistory?>
}

internal fun Player.canCopySnapshot(): Boolean =
    hasPermission(SNAPSHOT_VIEW_PERMISSION) && hasPermission(SNAPSHOT_COPY_PERMISSION)

internal enum class SnapshotCopyResult { COPIED, DENIED, STALE, UNSUPPORTED_CLICK, EMPTY, FULL }

/** Runs on Paper's primary thread, after the menu has cancelled the native event. */
internal object SnapshotCopy {
    fun copy(
        viewer: Player,
        actor: Player,
        currentView: Boolean,
        click: ClickType,
        snapshot: InventorySnapshot,
        slot: Int,
        audit: (ItemStack) -> Unit,
    ): SnapshotCopyResult {
        if (actor !== viewer || !viewer.isOnline || !currentView) return SnapshotCopyResult.STALE
        if (!viewer.canCopySnapshot()) return SnapshotCopyResult.DENIED
        if (click != ClickType.LEFT) return SnapshotCopyResult.UNSUPPORTED_CLICK
        val item = snapshot.item(slot)?.takeIf { it.amount > 0 } ?: return SnapshotCopyResult.EMPTY
        // An empty storage slot is deliberate: no partial merge, overflow or dropped remainder.
        val destination = (0 until 36).firstOrNull {
            viewer.inventory.getItem(it)?.type?.isAir != false
        } ?: return SnapshotCopyResult.FULL
        viewer.inventory.setItem(destination, item.clone())
        audit(item)
        return SnapshotCopyResult.COPIED
    }
}
