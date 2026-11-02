package ru.arc.graves

import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.data.Data
import net.william278.husksync.data.Identifier
import net.william278.husksync.data.Serializer
import net.william278.husksync.data.UserDataHolder
import net.william278.husksync.data.BukkitData
import net.william278.husksync.event.BukkitDataSaveEvent
import net.william278.husksync.HuskSync
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import java.util.concurrent.CompletableFuture
import java.time.OffsetDateTime
import java.util.UUID

/** Capture on Paper. Completion means authoritative player data was written and read back. */
internal interface NativeGravePlayerPersistence : AutoCloseable {
    val sharedInventory: Boolean get() = true
    fun isSynchronized(player: Player): Boolean
    fun sequence(player: Player): Long
    fun reconcile(player: Player, watermark: NativeGraveWatermark?): CompletableFuture<Unit>
    fun persist(player: Player, sequence: Long): CompletableFuture<Unit>
    override fun close() {}
}

internal object LocalGravePersistence : NativeGravePlayerPersistence {
    override val sharedInventory = false
    override fun isSynchronized(player: Player) = true
    private val key = NamespacedKey("arc", "grave_receipt")
    override fun sequence(player: Player) = player.persistentDataContainer.get(key, PersistentDataType.LONG) ?: 0L
    override fun reconcile(player: Player, watermark: NativeGraveWatermark?): CompletableFuture<Unit> {
        // This server's native playerdata is independent of the HuskSync profile.
        // A network checkpoint must never replace a separate Parkour inventory.
        return CompletableFuture.completedFuture(Unit)
    }
    override fun persist(player: Player, sequence: Long): CompletableFuture<Unit> = try {
        player.persistentDataContainer.set(key, PersistentDataType.LONG, sequence)
        NativePaperPlayerDataPersistence.persist(player)
        CompletableFuture.completedFuture(Unit)
    } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
}

/**
 * HuskSync 3.8.7 API / exact 3.8.8-c3a6afd runtime: capture is OnlineUser.createSnapshot,
 * not API.createSnapshot (the latter builds an empty snapshot). MySqlDatabase can log
 * and swallow a failed insert, so its void save or API callback is not an ACK.
 * The unique snapshot must be present on authoritative SQL before releasing the intent.
 */
internal class HuskSyncGravePersistence(
    private val worker: BoundedAsyncExecutor,
    private val api: BukkitHuskSyncAPI = BukkitHuskSyncAPI.getInstance(),
    scheduler: TaskScheduler = Tasks.scheduler,
) : NativeGravePlayerPersistence {
    private val tasks = LifecycleTaskScope(scheduler)
    private val pending = mutableSetOf<CompletableFuture<Unit>>()
    private var closed = false

    init {
        // HuskSync owns persisted custom data beyond a Graves module generation. The
        // stateless serializer remains registered while ARC is absent, and is replaced
        // on the next generation. It captures no runtime, tasks, player or database.
        api.registerDataSerializer(RECEIPT, ReceiptSerializer)
    }
    override fun isSynchronized(player: Player) = !api.getUser(player).isLocked
    override fun sequence(player: Player): Long = receipt(api.getUser(player).customDataStore[RECEIPT])

    override fun reconcile(player: Player, watermark: NativeGraveWatermark?): CompletableFuture<Unit> {
        if (watermark == null || sequence(player) >= watermark.sequence) return CompletableFuture.completedFuture(Unit)
        val user = api.getUser(player)
        val result = CompletableFuture<Unit>()
        pending += result
        api.getLatestSnapshot(user).whenCompleteSync(tasks) { latest, failure ->
            if (closed || !player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player || user.isLocked) {
                result.completeExceptionally(IllegalStateException("Grave recovery session retired"))
                pending -= result
                return@whenCompleteSync
            }
            if (sequence(player) >= watermark.sequence) {
                result.complete(Unit)
                pending -= result
                return@whenCompleteSync
            }
            try {
                val snapshot = latest?.orElse(null)
                if (failure == null && snapshot != null && receipt(snapshot.data[RECEIPT]) >= watermark.sequence) {
                    val inventory = snapshot.inventory.orElseThrow { IllegalStateException("HuskSync recovery checkpoint has no inventory") }
                    val experience = snapshot.experience.orElseThrow { IllegalStateException("HuskSync recovery checkpoint has no experience") }
                    // UserDataHolder.setInventory/setExperience enqueue vendor runSync;
                    // bind the official Bukkit snapshot here and apply/verify on this turn.
                    val stacks = (inventory as BukkitData.Items.Inventory).contents
                    NativeGraveItems.apply(player, NativeGraveInventory(stacks.map {
                        it?.takeUnless(org.bukkit.inventory.ItemStack::isEmpty)?.let(NativeGraveItems::encode)
                    }, experience.expLevel, experience.expProgress, experience.totalExperience))
                    user.customDataStore[RECEIPT] = ReceiptData(receipt(snapshot.data[RECEIPT]))
                } else {
                    // A late world-save may have persisted already-stale Redis data as a
                    // newer SQL row. The independent acknowledged native checkpoint survives
                    // that row and HuskSync history rotation.
                    NativeGraveItems.apply(player, watermark.inventory)
                    user.customDataStore[RECEIPT] = ReceiptData(watermark.sequence)
                }
                persist(player, sequence(player)).whenComplete { _, saveFailure ->
                    if (saveFailure == null) result.complete(Unit) else result.completeExceptionally(saveFailure)
                }
            } catch (problem: Throwable) { result.completeExceptionally(problem) }
            result.whenComplete { _, _ -> tasks.runSync { pending -= result } }
        }
        return result
    }

    override fun persist(player: Player, sequence: Long): CompletableFuture<Unit> {
        val user = api.getUser(player)
        check(!user.isLocked && player.isOnline && !player.isDead) { "Cannot save an unsynchronized grave inventory" }
        require(sequence > 0) { "A grave snapshot requires an allocated operation receipt" }
        // Caller applied and verified AFTER. Receipt and snapshot capture are in this
        // same Paper turn; no snapshot can advertise the receipt with BEFORE items.
        saveFences[player.uniqueId] = OffsetDateTime.now()
        user.customDataStore[RECEIPT] = ReceiptData(sequence)
        val snapshot = user.createSnapshot(DataSnapshot.SaveCause.API)
        val database = api.plugin.database
        return worker.submit {
            val expected = payload(snapshot)
            database.addSnapshot(user, snapshot)
            val durable = database.getSnapshot(user, snapshot.id)
            check(durable.isPresent && durable.get().id == snapshot.id && payload(durable.get()) == expected) {
                "HuskSync grave inventory save has no exact durable payload readback"
            }
            Unit
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        pending.forEach { it.completeExceptionally(IllegalStateException("Grave recovery closed")) }
        pending.clear()
    }

    /**
     * Exact HuskSync WORLD_SAVE captures live fields asynchronously, then fires this
     * event on Paper before SQL/Redis publication. Discard a snapshot begun before
     * our verified AFTER capture; it may otherwise mix BEFORE items with the new
     * receipt. Later normal saves and the disconnect checkout path remain native.
     */
    fun beforeSave(event: BukkitDataSaveEvent) {
        if (event.saveCause != DataSnapshot.SaveCause.WORLD_SAVE) return
        val fence = saveFences[event.user.uuid] ?: return
        if (event.data.timestamp.isBefore(fence)) event.isCancelled = true
    }

    private fun payload(value: DataSnapshot.Packed): JsonObject {
        val root = JsonParser.parseString(value.asJson(api.plugin)).asJsonObject
        check(root.has("data") && root["data"].isJsonObject) { "HuskSync snapshot has no serialized data" }
        return root.getAsJsonObject("data")
    }

    private class ReceiptData(private val value: Long) : Data {
        override fun apply(holder: UserDataHolder, plugin: HuskSync) { holder.customDataStore[RECEIPT] = this }
        override fun toString() = PREFIX + value
    }
    private object ReceiptSerializer : Serializer<Data> {
        override fun deserialize(value: String): Data = ReceiptData(parse(value))
        override fun serialize(value: Data): String = parse(value.toString().removePrefix(PREFIX)).toString()
    }
    companion object {
        private val RECEIPT = Identifier.from("arc", "grave_receipt")
        private const val PREFIX = "arc-grave-receipt:"
        // ponytail: one scalar fence per grave owner for this JVM; expire after drained
        // vendor snapshots if that footprint ever matters. No native handle is retained.
        private val saveFences = mutableMapOf<UUID, OffsetDateTime>()
        private fun parse(value: String): Long = value.toLong().also { require(it >= 0 && it.toString() == value) { "Invalid grave receipt" } }
        private fun receipt(value: Data?): Long = value?.let {
            val raw = it.toString()
            require(raw.startsWith(PREFIX)) { "Unexpected grave receipt type" }
            parse(raw.removePrefix(PREFIX))
        } ?: 0
    }
}
