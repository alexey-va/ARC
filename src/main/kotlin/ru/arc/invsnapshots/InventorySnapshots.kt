package ru.arc.invsnapshots

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

internal const val SNAPSHOT_VIEW_PERMISSION = "arc.invlist"
internal const val SNAPSHOT_COPY_PERMISSION = "arc.invlist.copy"

/** Detached copies only: this feature has no target-player or persistence write port. */
internal class InventorySnapshot(
    val id: UUID,
    val timestamp: Instant,
    val server: String,
    val cause: String,
    val pinned: Boolean,
    contents: List<ItemStack?>?,
) {
    private val slots = contents?.map { it?.clone() }
    val hasInventory: Boolean get() = slots != null
    fun item(slot: Int): ItemStack? = slots?.getOrNull(slot)?.takeUnless { it.type.isAir }?.clone()
}

internal data class SnapshotHistory(
    val playerId: UUID,
    val playerName: String,
    val snapshots: List<InventorySnapshot>,
)

internal fun interface InventorySnapshotSource {
    fun find(playerName: String): CompletionStage<SnapshotHistory?>
}

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
        if (!viewer.hasPermission(SNAPSHOT_VIEW_PERMISSION) || !viewer.hasPermission(SNAPSHOT_COPY_PERMISSION)) {
            return SnapshotCopyResult.DENIED
        }
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
