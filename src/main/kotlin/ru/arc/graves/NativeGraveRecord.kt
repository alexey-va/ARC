package ru.arc.graves

import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import java.util.Base64
import java.util.Collections
import java.util.UUID

internal enum class NativeGravePhase { IMPORTED, PREPARED, ACTIVE, CLAIMING, CLAIMED, REVIEW }

internal enum class NativeGraveOperationKind { CREATE, CLAIM }

/** Immutable native inventory/XP receipt. Slot order is Bukkit's 0..40 player inventory order. */
internal class NativeGraveInventory(
    slots: List<String?>,
    val level: Int,
    val progress: Float,
    val totalExperience: Int,
) {
    val slots: List<String?> = Collections.unmodifiableList(ArrayList(slots))

    fun copy(
        slots: List<String?> = this.slots,
        level: Int = this.level,
        progress: Float = this.progress,
        totalExperience: Int = this.totalExperience,
    ) = NativeGraveInventory(slots, level, progress, totalExperience)

    fun validate() {
        validateMetadata()
        require(validateStacks(slots) <= NativeGraveRecord.MAX_TOTAL_ITEM_BYTES) { "Grave inventory items are too large" }
    }

    internal fun validateMetadata() {
        require(slots.size == SLOT_COUNT) { "Grave inventory must contain exactly 41 slots" }
        require(level >= 0 && totalExperience >= 0) { "Grave inventory experience is invalid" }
        require(progress.isFinite() && progress in 0f..1f) { "Grave inventory experience progress is invalid" }
    }

    override fun equals(other: Any?): Boolean = other is NativeGraveInventory &&
        slots == other.slots && level == other.level && progress.toBits() == other.progress.toBits() &&
        totalExperience == other.totalExperience

    override fun hashCode(): Int = arrayOf(slots, level, progress, totalExperience).contentHashCode()

    override fun toString(): String = "NativeGraveInventory(occupiedSlots=${slots.count { it != null }}, level=$level)"

    companion object {
        const val SLOT_COUNT = 41
    }
}

internal data class NativeGraveOperation(
    val id: UUID,
    val server: String,
    val kind: NativeGraveOperationKind,
    val before: NativeGraveInventory,
    val after: NativeGraveInventory,
    val sequence: Long = 0,
    val inventoryScope: String = "network",
) {
    internal fun immutableCopy(): NativeGraveOperation = copy(before = before.copy(), after = after.copy())
}

/** Latest acknowledged exact inventory/XP state, independent of grave visibility or retention. */
internal data class NativeGraveWatermark(
    val ownerId: UUID,
    val sequence: Long,
    val inventory: NativeGraveInventory,
) {
    fun validate() {
        requireNotNull(ownerId) { "Grave watermark owner is missing" }
        require(sequence > 0) { "Grave watermark sequence is invalid" }
        inventory.validate()
    }
}

/**
 * Shared canonical grave and optional exact player-state recovery receipt.
 * Collections are defensive immutable copies. [validate] is pure but decodes up
 * to [MAX_TOTAL_ITEM_BYTES]; call it on a storage worker, never a gameplay thread.
 * Native ItemStack decoding and all Bukkit access belong to the event engine.
 */
internal class NativeGraveRecord(
    val id: UUID,
    val ownerId: UUID,
    val ownerName: String,
    val server: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val experience: Int,
    items: List<String>,
    val revision: Long = 0,
    val phase: NativeGravePhase = NativeGravePhase.ACTIVE,
    val operation: NativeGraveOperation? = null,
    val completedSequence: Long = 0,
    val checkpoint: NativeGraveInventory? = null,
    @Transient val appearance: GraveAppearanceChoice? = null,
) {
    val items: List<String> = Collections.unmodifiableList(ArrayList(items))

    fun copy(
        id: UUID = this.id,
        ownerId: UUID = this.ownerId,
        ownerName: String = this.ownerName,
        server: String = this.server,
        world: String = this.world,
        x: Int = this.x,
        y: Int = this.y,
        z: Int = this.z,
        createdAt: Long = this.createdAt,
        expiresAt: Long = this.expiresAt,
        experience: Int = this.experience,
        items: List<String> = this.items,
        revision: Long = this.revision,
        phase: NativeGravePhase = this.phase,
        operation: NativeGraveOperation? = this.operation,
        completedSequence: Long = this.completedSequence,
        checkpoint: NativeGraveInventory? = this.checkpoint,
        appearance: GraveAppearanceChoice? = this.appearance,
    ) = NativeGraveRecord(
        id, ownerId, ownerName, server, world, x, y, z, createdAt, expiresAt,
        experience, items, revision, phase, operation, completedSequence, checkpoint, appearance,
    )

    fun validate() {
        requireNotNull(id) { "Grave id is missing" }
        requireNotNull(ownerId) { "Grave owner id is missing" }
        require(NetworkPlayerName.parseOrNull(ownerName) != null) { "Grave owner name is invalid" }
        require(BackendServerId.parseOrNull(server) != null) { "Grave server is invalid" }
        require(world.length in 1..128 && world.none(Char::isISOControl)) { "Grave world is invalid" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000 && y in -2048..2048) {
            "Grave coordinates are invalid"
        }
        require(createdAt >= 0 && (expiresAt == -1L || expiresAt >= createdAt)) { "Grave timestamps are invalid" }
        require(experience >= 0 && revision >= 0) { "Grave experience or revision is invalid" }
        require(completedSequence >= 0) { "Grave completed sequence is invalid" }
        require(completedSequence == 0L || checkpoint != null) { "Completed grave is missing its inventory checkpoint" }
        require(items.size <= MAX_ITEMS) { "Grave contains too many items" }
        requireNotNull(phase) { "Grave phase is missing" }
        appearance?.validate()
        var totalBytes = validateStacks(items, allowEmptySlots = false)
        operation?.let { pending ->
            requireNotNull(pending.id) { "Grave operation id is missing" }
            requireNotNull(pending.kind) { "Grave operation kind is missing" }
            require(pending.sequence >= 0) { "Grave operation sequence is invalid" }
            require(pending.inventoryScope.length in 1..128 && pending.inventoryScope.all { character ->
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in ":_-."
            }) { "Grave operation inventory scope is invalid" }
            require(BackendServerId.parseOrNull(pending.server) != null) { "Grave operation server is invalid" }
            pending.before.validateMetadata()
            pending.after.validateMetadata()
            totalBytes = validateStacks(pending.before.slots, initialTotal = totalBytes)
            totalBytes = validateStacks(pending.after.slots, initialTotal = totalBytes)
        }
        checkpoint?.let {
            it.validateMetadata()
            totalBytes = validateStacks(it.slots, initialTotal = totalBytes)
        }
        require(totalBytes <= MAX_TOTAL_ITEM_BYTES) { "Grave and recovery items are too large" }
        when (phase) {
            NativeGravePhase.IMPORTED -> require(operation == null) { "Imported grave has an inventory operation" }
            NativeGravePhase.PREPARED -> require(operation?.kind == NativeGraveOperationKind.CREATE) {
                "Prepared grave is missing its creation receipt"
            }
            NativeGravePhase.CLAIMING -> require(operation?.kind == NativeGraveOperationKind.CLAIM) {
                "Claiming grave is missing its claim receipt"
            }
            NativeGravePhase.CLAIMED -> require(items.isEmpty() && experience == 0) { "Claimed grave is not empty" }
            NativeGravePhase.REVIEW -> require(operation != null) { "Review grave is missing its recovery receipt" }
            NativeGravePhase.ACTIVE -> Unit
        }
    }

    override fun equals(other: Any?): Boolean = other is NativeGraveRecord &&
        id == other.id && ownerId == other.ownerId && ownerName == other.ownerName && server == other.server &&
        world == other.world && x == other.x && y == other.y && z == other.z && createdAt == other.createdAt &&
        expiresAt == other.expiresAt && experience == other.experience && items == other.items &&
        revision == other.revision && phase == other.phase && operation == other.operation &&
        completedSequence == other.completedSequence && checkpoint == other.checkpoint && appearance == other.appearance

    override fun hashCode(): Int = arrayOf(
        id, ownerId, ownerName, server, world, x, y, z, createdAt, expiresAt,
        experience, items, revision, phase, operation, completedSequence, checkpoint, appearance,
    ).contentHashCode()

    override fun toString(): String =
        "NativeGraveRecord(id=$id, ownerId=$ownerId, server=$server, phase=$phase, revision=$revision, itemCount=${items.size})"

    companion object {
        const val MAX_ITEMS = 256
        const val MAX_ITEM_BYTES = 512 * 1024
        const val MAX_TOTAL_ITEM_BYTES = 8L * 1024L * 1024L
        internal const val MAX_ENCODED_ITEM_CHARACTERS = ((MAX_ITEM_BYTES + 2) / 3) * 4
    }
}

/** REVIEW retains its receipt without blocking unrelated inventory transitions. */
internal fun NativeGraveRecord.pendingOwnerId(): String? =
    operation?.takeIf { phase == NativeGravePhase.PREPARED || phase == NativeGravePhase.CLAIMING }
        ?.let { "$ownerId:${it.inventoryScope}" }

private fun validateStacks(stacks: List<String?>, allowEmptySlots: Boolean = true, initialTotal: Long = 0L): Long {
    var total = initialTotal
    for (encoded in stacks) {
        if (encoded == null) {
            require(allowEmptySlots) { "Grave contains a null item" }
            continue
        }
        require(encoded.length in 4..NativeGraveRecord.MAX_ENCODED_ITEM_CHARACTERS) { "Grave item encoding is too large or empty" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size <= NativeGraveRecord.MAX_ITEM_BYTES) { "Grave item bytes are invalid" }
        require(Base64.getEncoder().encodeToString(bytes) == encoded) { "Grave item encoding is not canonical base64" }
        total += bytes.size
        require(total <= NativeGraveRecord.MAX_TOTAL_ITEM_BYTES) { "Grave items are too large" }
    }
    return total
}
