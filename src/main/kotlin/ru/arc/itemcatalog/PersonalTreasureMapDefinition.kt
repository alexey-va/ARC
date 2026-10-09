package ru.arc.itemcatalog

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.onetime.OneTimeUseFingerprint
import java.util.Collections
import java.util.UUID

/** Continuous min-inclusive/max-exclusive rectangle sampled at block centers. */
data class PersonalTreasureMapBounds(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    init {
        require(minX in -MAX_COORDINATE..MAX_COORDINATE && maxX in -MAX_COORDINATE..MAX_COORDINATE) {
            "Search X bounds are outside supported world coordinates"
        }
        require(minZ in -MAX_COORDINATE..MAX_COORDINATE && maxZ in -MAX_COORDINATE..MAX_COORDINATE) {
            "Search Z bounds are outside supported world coordinates"
        }
        require(minX < maxX && minZ < maxZ) { "Search bounds must have positive width and height" }
    }

    fun contains(x: Double, z: Double): Boolean =
        x >= minX && x < maxX && z >= minZ && z < maxZ

    companion object {
        private const val MAX_COORDINATE = 30_000_000
    }
}

/** Bounded area used to pick a personal cache from already generated Survival terrain. */
data class PersonalTreasureMapSearchPolicy(
    val server: String,
    val world: String,
    val radius: Int? = null,
    val bounds: PersonalTreasureMapBounds? = null,
    val minDistance: Int = if (bounds == null) LEGACY_MIN_TARGET_DISTANCE else EXPEDITION_MIN_TARGET_DISTANCE,
) {
    init {
        require(server.length in 1..48 && server.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid search server" }
        require(world.length in 1..128 && world.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid search world" }
        require((radius == null) xor (bounds == null)) { "Search requires exactly one of radius or bounds" }
        if (radius != null) {
            require(radius in MIN_RADIUS..MAX_RADIUS) { "Search radius must be in $MIN_RADIUS..$MAX_RADIUS" }
            require(minDistance in 1..radius) { "Search minimum distance must be in 1..radius" }
        } else {
            val searchBounds = requireNotNull(bounds)
            val maxDistance = kotlin.math.hypot(
                (searchBounds.maxX.toLong() - searchBounds.minX).toDouble(),
                (searchBounds.maxZ.toLong() - searchBounds.minZ).toDouble(),
            )
            require(minDistance in 1..MAX_MIN_DISTANCE && minDistance.toDouble() <= maxDistance) {
                "Search minimum distance must fit inside configured bounds"
            }
        }
    }

    val targetPolicyFingerprint: OneTimeUseFingerprint = OneTimeUseFingerprint.sha256Fields(
        "personal-treasure-map-target-policy-v1",
        server,
        world,
        radius?.toString() ?: "bounds",
        bounds?.minX?.toString() ?: "",
        bounds?.maxX?.toString() ?: "",
        bounds?.minZ?.toString() ?: "",
        bounds?.maxZ?.toString() ?: "",
        minDistance.toString(),
    )

    fun containsTarget(x: Double, z: Double): Boolean = bounds?.contains(x, z) ?: true

    companion object {
        const val MIN_RADIUS = 16
        const val MAX_RADIUS = 256
        const val LEGACY_MIN_TARGET_DISTANCE = 16
        const val EXPEDITION_MIN_TARGET_DISTANCE = 3_000
        const val MAX_MIN_DISTANCE = 30_000_000
        const val CANDIDATE_LIMIT = 64
        const val TARGET_HINT = "Отметка тайника"
    }
}

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
    val searchPolicy: PersonalTreasureMapSearchPolicy? = null,
    val prizeRolls: Int = 1,
    identityFingerprintOverride: OneTimeUseFingerprint? = null,
    /** One previously issued route whose target may be discarded by an explicit route migration. */
    val legacyTargetPolicy: PersonalTreasureMapSearchPolicy? = null,
) {
    val destinations: List<PersonalTreasureMapDestination> = Collections.unmodifiableList(destinations.toList())
    val fingerprint: OneTimeUseFingerprint

    init {
        require(id.length in 1..128 && id.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid map definition id" }
        require(PhysicalRewardVoucher.isValidKey(prizeSourceRef)) { "Invalid prize source reference" }
        require(prizeRolls in 1..MAX_PRIZE_ROLLS) { "Personal map prize rolls must be in 1..$MAX_PRIZE_ROLLS" }
        require(this.destinations.size <= MAX_DESTINATIONS && (this.destinations.isNotEmpty() || searchPolicy != null)) {
            "A treasure map requires a search policy or 1..$MAX_DESTINATIONS legacy destinations"
        }
        require(legacyTargetPolicy == null || searchPolicy != null) {
            "A legacy target policy requires a current search policy"
        }
        fingerprint = identityFingerprintOverride ?: fingerprintFor(id, prizeSourceRef, this.destinations, searchPolicy, prizeRolls)
    }

    fun destinationIndex(voucherId: UUID): Int =
        if (destinations.isEmpty()) 0 else Math.floorMod(voucherId.hashCode(), destinations.size)

    fun destinationFor(voucherId: UUID): PersonalTreasureMapDestination =
        destinations.getOrNull(destinationIndex(voucherId))
            ?: error("Dynamic personal maps do not have an authored destination")

    companion object {
        const val MAX_DESTINATIONS = 64
        const val MAX_PRIZE_ROLLS = 8

        private fun fingerprintFor(
            id: String,
            prizeSourceRef: String,
            destinations: List<PersonalTreasureMapDestination>,
            searchPolicy: PersonalTreasureMapSearchPolicy?,
            prizeRolls: Int,
        ): OneTimeUseFingerprint = when {
            searchPolicy == null && prizeRolls == 1 -> legacyFingerprint(id, prizeSourceRef, destinations)
            searchPolicy == null -> OneTimeUseFingerprint.sha256Fields(
                "personal-treasure-map-v2",
                legacyFingerprint(id, prizeSourceRef, destinations).sha256,
                prizeRolls.toString(),
            )
            searchPolicy.bounds == null && prizeRolls == 1 -> OneTimeUseFingerprint.sha256Fields(
                "personal-treasure-map-v2",
                id,
                prizeSourceRef,
                searchPolicy.server,
                searchPolicy.world,
                requireNotNull(searchPolicy.radius).toString(),
            )
            else -> OneTimeUseFingerprint.sha256Fields(
                "personal-treasure-map-v3",
                id,
                prizeSourceRef,
                prizeRolls.toString(),
                searchPolicy.targetPolicyFingerprint.sha256,
            )
        }

        fun legacyFingerprint(
            id: String,
            prizeSourceRef: String,
            destinations: List<PersonalTreasureMapDestination>,
        ): OneTimeUseFingerprint = OneTimeUseFingerprint.sha256Fields(
            "personal-treasure-map-v1",
            id,
            prizeSourceRef,
            destinations.mapIndexed { index, destination ->
                listOf(
                    index.toString(), destination.server, destination.world,
                    java.lang.Double.toHexString(destination.x), java.lang.Double.toHexString(destination.y),
                    java.lang.Double.toHexString(destination.z), destination.hint,
                ).joinToString("\u001f")
            }.joinToString("\n"),
        )
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
    val mapViewWorld: String? = null,
    val searchGeneration: Int = 0,
    val target: PersonalTreasureMapDestination? = null,
    val targetPolicyFingerprint: String? = null,
) {
    companion object {
        const val VERSION = "4"
        private val READABLE_VERSIONS = setOf("1", "2", "3", VERSION)
        val versionKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_version")
        val ownerKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_owner")
        val definitionKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_definition")
        val fingerprintKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_fingerprint")
        val destinationIndexKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_destination")
        val searchGenerationKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_search_generation")
        val targetServerKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_server")
        val targetWorldKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_world")
        val targetXKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_x")
        val targetYKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_y")
        val targetZKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_z")
        val targetPolicyFingerprintKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_target_policy")
        val mapViewServerKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_view_server")
        val mapViewIdKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_view_id")
        val mapViewWorldKey = org.bukkit.NamespacedKey("arc", "personal_treasure_map_view_world")

        /** Reads a complete marker from a single, plugin-issued filled map. */
        fun read(stack: ItemStack?): PersonalTreasureMapIdentity? {
            if (stack?.type != Material.FILLED_MAP || stack.amount != 1) return null
            val data = stack.itemMeta?.persistentDataContainer ?: return null
            val version = data.get(versionKey, PersistentDataType.STRING)
            if (version !in READABLE_VERSIONS) return null
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
            val rawViewWorld = data.get(mapViewWorldKey, PersistentDataType.STRING)
            val versionHasViewWorld = version == "3" || version == VERSION
            val viewWorld = if (versionHasViewWorld && viewServer != null) {
                rawViewWorld?.let { raw ->
                    runCatching { UUID.fromString(raw).takeIf { it.toString() == raw }?.toString() }.getOrNull()
                } ?: return null
            } else null
            if (versionHasViewWorld && (viewServer == null) != (rawViewWorld == null)) return null
            val searchGeneration = if (version != "1") {
                data.get(searchGenerationKey, PersistentDataType.INTEGER)?.takeIf { it >= 0 } ?: return null
            } else 0
            val targetPolicyFingerprint = if (data.has(targetPolicyFingerprintKey)) {
                data.get(targetPolicyFingerprintKey, PersistentDataType.STRING)
                    ?.let { runCatching { OneTimeUseFingerprint.parse(it).sha256 }.getOrNull() } ?: return null
            } else null
            val targetKeys = listOf(targetServerKey, targetWorldKey, targetXKey, targetYKey, targetZKey)
            val hasTarget = targetKeys.any(data::has)
            val target = if (hasTarget) {
                if (!targetKeys.all(data::has)) return null
                runCatching {
                    PersonalTreasureMapDestination(
                        requireNotNull(data.get(targetServerKey, PersistentDataType.STRING)),
                        requireNotNull(data.get(targetWorldKey, PersistentDataType.STRING)),
                        requireNotNull(data.get(targetXKey, PersistentDataType.DOUBLE)),
                        requireNotNull(data.get(targetYKey, PersistentDataType.DOUBLE)),
                        requireNotNull(data.get(targetZKey, PersistentDataType.DOUBLE)),
                        PersonalTreasureMapSearchPolicy.TARGET_HINT,
                    )
                }.getOrNull() ?: return null
            } else null
            return PersonalTreasureMapIdentity(
                voucherId, owner, definitionId, fingerprint, destinationIndex, viewServer, viewId, viewWorld,
                searchGeneration, target, targetPolicyFingerprint,
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
    TARGET_CHANGED,
    NO_SAFE_TARGET,
    SAFETY_UNAVAILABLE,
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
    val targetSelected: Boolean,
    val ownerBound: Boolean = false,
    val safetyUnavailable: Boolean = false,
    val searching: Boolean = false,
)
