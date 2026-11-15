package ru.arc.invsnapshots

import com.google.gson.JsonParser
import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.BukkitData
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.data.Identifier
import net.william278.husksync.user.User
import org.bukkit.entity.Player
import ru.arc.core.Tasks
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Loaded only after the optional HuskSync plugin has been checked. Only the inspecting player may receive a new backup. */
internal class HuskSyncInventorySnapshotSource(
    private val api: BukkitHuskSyncAPI = BukkitHuskSyncAPI.getInstance(),
) : InventorySnapshotSource {
    override fun find(playerName: String): CompletionStage<SnapshotHistory?> = api.getUser(playerName).thenCompose { found ->
        val user = found.orElse(null)
        if (user == null) CompletableFuture.completedFuture(null)
        else api.getSnapshots(user).thenApply { snapshots ->
            val currentVersion = api.plugin.minecraftVersion
            SnapshotHistory(user.uuid, user.username, snapshots.map { snapshot ->
                val inventory = snapshot.inventory.orElse(null) as? BukkitData.Items.Inventory
                InventorySnapshot(
                    id = snapshot.id,
                    timestamp = snapshot.timestamp.toInstant(),
                    server = snapshot.serverName,
                    cause = snapshot.saveCause.name(),
                    pinned = snapshot.isPinned,
                    // 3.8.7 getContents() returns the already deserialized, detached snapshot array.
                    // No player/world API is called here; InventorySnapshot defensively clones each stack.
                    contents = inventory?.contents?.toList(),
                    heldItemSlot = inventory?.heldItemSlot ?: 0,
                    // HuskSync 3.8.8 upgrades sparse items by their list index, losing the original NBT Slot.
                    exactSlotOrder = snapshot.minecraftVersion >= currentVersion,
                )
            }.sortedByDescending { it.timestamp })
        }
    }

    fun ready(viewer: Player): Boolean = !api.getUser(viewer).isLocked

    /** Capture on Paper. SQL writes can fail silently in HuskSync, so acknowledge only an exact, pinned readback. */
    fun backup(viewer: Player): CompletionStage<UUID> {
        val online = api.getUser(viewer)
        check(!online.isLocked && viewer.isOnline && !viewer.isDead)
        val snapshot = online.createSnapshot(DataSnapshot.SaveCause.API).apply { isPinned = true }
        val owner = User(viewer.uniqueId, viewer.name)
        val plugin = api.plugin
        val database = plugin.database
        return CompletableFuture.supplyAsync({
            fun inventoryData(value: DataSnapshot.Packed): String =
                JsonParser.parseString(value.asJson(plugin)).asJsonObject.getAsJsonObject("data")
                    .get(Identifier.INVENTORY.toString())?.asString?.takeIf(String::isNotBlank)
                    ?: error("HuskSync backup contains no inventory")

            val expected = inventoryData(snapshot)
            database.addSnapshot(owner, snapshot)
            val saved = database.getSnapshot(owner, snapshot.id).orElseThrow()
            check(saved.id == snapshot.id && saved.isPinned && !saved.isInvalid && inventoryData(saved) == expected) {
                "HuskSync inventory backup readback did not match"
            }
            saved.id
        }, { task -> Tasks.scheduler.runAsync(task) })
    }
}
