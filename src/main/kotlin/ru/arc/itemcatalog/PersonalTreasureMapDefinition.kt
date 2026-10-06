package ru.arc.itemcatalog

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.onetime.OneTimeUseFingerprint
import java.util.Collections
import java.util.UUID

/** A fixed, source-authored cache destination. Coordinates are never discovered from live terrain. */
data class PersonalTreasureMapDestination(
    val server: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val hint: String,
) {
    init {
        require(server.length in 1..48 && server.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid destination server" }
        require(world.length in 1..128 && world.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid destination world" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Destination coordinates must be finite" }
        require(kotlin.math.abs(x) <= 30_000_000 && kotlin.math.abs(z) <= 30_000_000) { "Destination is outside world bounds" }
        require(y in -2048.0..4096.0) { "Destination Y is outside supported bounds" }
        require(hint.isNotBlank() && hint.length <= 128) { "Destination hint must be short and non-empty" }
    }
}

/**
 * Frozen reward recipe payload. Callers resolve archived definitions by voucher source and fingerprint,
 * so an issued map keeps its original route after the active reward configuration changes.
 */
class PersonalTreasureMapDefinition(
    val id: String,
    val prizeSourceRef: String,
    destinations: List<PersonalTreasureMapDestination>,
) {
    val destinations: List<PersonalTreasureMapDestination> = Collections.unmodifiableList(destinations.toList())
    val fingerprint: OneTimeUseFingerprint

    init {
        require(id.length in 1..128 && id.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid map definition id" }
        require(PhysicalRewardVoucher.isValidKey(prizeSourceRef)) { "Invalid prize source reference" }
        require(this.destinations.isNotEmpty() && this.destinations.size <= MAX_DESTINATIONS) {
            "A treasure map must have 1..$MAX_DESTINATIONS destinations"
        }
        fingerprint = OneTimeUseFingerprint.sha256Fields(
            "personal-treasure-map-v1",
            id,
            prizeSourceRef,
            this.destinations.mapIndexed { index, destination ->
                listOf(
                    index.toString(), destination.server, destination.world,
                    java.lang.Double.toHexString(destination.x), java.lang.Double.toHexString(destination.y),
                    java.lang.Double.toHexString(destination.z), destination.hint,
                ).joinToString("\u001f")
            }.joinToString("\n"),
        )
    }

    fun destinationIndex(voucherId: UUID): Int = Math.floorMod(voucherId.hashCode(), destinations.size)

    fun destinationFor(voucherId: UUID): PersonalTreasureMapDestination = destinations[destinationIndex(voucherId)]

    companion object {
        const val MAX_DESTINATIONS = 64
    }
}

data class PersonalTreasureMapIdentity(
    val voucherId: UUID,
    val ownerId: UUID,
    val definitionId: String,
    val definitionFingerprint: OneTimeUseFingerprint,
    val destinationIndex: Int,
    val mapViewServer: String?,
    val mapViewId: Int?,
) {
    companion object {
        const val VERSION = "1"
        val versionKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_version")
        val ownerKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_owner")
        val definitionKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_definition")
        val fingerprintKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_fingerprint")
        val destinationIndexKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_destination")
        val mapViewServerKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_view_server")
        val mapViewIdKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_view_id")

        /** Reads a complete marker from a single, plugin-issued filled map. */
        fun read(stack: ItemStack?): PersonalTreasureMapIdentity? {
            if (stack?.type != Material.FILLED_MAP || stack.amount != 1) return null
            val data = stack.itemMeta?.persistentDataContainer ?: return null
            if (data.get(versionKey, PersistentDataType.STRING) != VERSION) return null
            val voucherId = PhysicalRewardVoucher.identity(stack)?.id ?: return null
            val owner = data.get(ownerKey, PersistentDataType.STRING)?.let(::parseUuid) ?: return null
            val definitionId = data.get(definitionKey, PersistentDataType.STRING)
                ?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_.:-]+")) } ?: return null
            val fingerprint = data.get(fingerprintKey, PersistentDataType.STRING)
                ?.let { runCatching { OneTimeUseFingerprint.parse(it) }.getOrNull() } ?: return null
            val destinationIndex = data.get(destinationIndexKey, PersistentDataType.INTEGER)
                ?.takeIf { it in 0 until PersonalTreasureMapDefinition.MAX_DESTINATIONS } ?: return null
            val viewServer = data.get(mapViewServerKey, PersistentDataType.STRING)
                ?.takeIf { it.length in 1..48 && it.matches(Regex("[A-Za-z0-9_.-]+")) }
            val viewId = data.get(mapViewIdKey, PersistentDataType.INTEGER)?.takeIf { it >= 0 }
            if ((viewServer == null) != (viewId == null)) return null
            return PersonalTreasureMapIdentity(
                voucherId, owner, definitionId, fingerprint, destinationIndex, viewServer, viewId,
            )
        }

        private fun parseUuid(value: String): UUID? =
            runCatching { UUID.fromString(value).takeIf { it.toString() == value } }.getOrNull()
    }
}

enum class PersonalTreasureMapFailure {
    INVALID_OR_STALE,
    WRONG_OWNER,
    WRONG_SERVER,
    WRONG_WORLD,
    TOO_FAR,
}

enum class PersonalTreasureMapUseDecision {
    NOT_A_PERSONAL_MAP,
    OPEN_MAP,
    CLAIM,
    REJECT,
}

data class PersonalTreasureMapGuidance(
    val hint: String,
    val distance: Double?,
    val bearingDegrees: Double?,
    val onDestinationServer: Boolean,
    val onDestinationWorld: Boolean,
    val withinClaimRadius: Boolean,
)
