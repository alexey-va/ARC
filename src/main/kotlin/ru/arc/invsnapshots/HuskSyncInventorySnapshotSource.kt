package ru.arc.invsnapshots

import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.BukkitData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Loaded only after the optional HuskSync plugin has been checked. Native read API only. */
internal class HuskSyncInventorySnapshotSource(
    private val api: BukkitHuskSyncAPI = BukkitHuskSyncAPI.getInstance(),
) : InventorySnapshotSource {
    override fun find(playerName: String): CompletionStage<SnapshotHistory?> = api.getUser(playerName).thenCompose { found ->
        val user = found.orElse(null)
        if (user == null) CompletableFuture.completedFuture(null)
        else api.getSnapshots(user).thenApply { snapshots ->
            SnapshotHistory(user.uuid, user.username, snapshots.map { snapshot ->
                InventorySnapshot(
                    id = snapshot.id,
                    timestamp = snapshot.timestamp.toInstant(),
                    server = snapshot.serverName,
                    cause = snapshot.saveCause.name(),
                    pinned = snapshot.isPinned,
                    // 3.8.7 getContents() returns the already deserialized, detached snapshot array.
                    // No player/world API is called here; InventorySnapshot defensively clones each stack.
                    contents = (snapshot.inventory.orElse(null) as? BukkitData.Items.Inventory)?.contents?.toList(),
                )
            }.sortedByDescending { it.timestamp })
        }
    }
}
