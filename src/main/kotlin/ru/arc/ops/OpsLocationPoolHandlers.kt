package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.common.ServerLocation
import ru.arc.common.WeightedRandom
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager

/**
 * Structured content-management boundary for persistent location pools.
 *
 * The API exposes a stable list of weighted coordinates instead of the
 * internal WeightedRandom/Gson persistence shape.
 */
object OpsLocationPoolHandlers {
    fun list(id: String? = null): Map<String, Any?> {
        val normalizedId = id?.takeIf { it.isNotBlank() }?.let(LocationPool::normalizePersistentId)
        return OpsBukkitSync.call {
            val selected =
                if (normalizedId == null) {
                    LocationPoolManager
                        .getAll()
                        .filterNot { LocationPoolManager.isEphemeralPool(it.id) }
                        .sortedBy { it.id }
                } else {
                    listOfNotNull(LocationPoolManager.getPool(normalizedId))
                        .filterNot { LocationPoolManager.isEphemeralPool(it.id) }
                }
            if (normalizedId != null && selected.isEmpty()) {
                throw NoSuchElementException("Location pool not found: $normalizedId")
            }
            mapOf(
                "source" to "arc-location-pools",
                "server" to (ARC.serverName ?: "unknown"),
                "count" to selected.size,
                "pools" to selected.map(::poolToMap),
            )
        }
    }

    fun preview(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val definition = parseDefinition(id, body)
        return OpsBukkitSync.call {
            validateForCurrentNode(definition)
            mapOf(
                "source" to "arc-location-pools",
                "preview" to true,
                "persisted" to false,
                "exists" to (LocationPoolManager.getPool(definition.id) != null),
                "pool" to definitionToMap(definition),
            )
        }
    }

    fun upsert(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val definition = parseDefinition(id, body)
        return OpsBukkitSync.call {
            validateForCurrentNode(definition)
            val existed = LocationPoolManager.getPool(definition.id) != null
            val pool = definition.toPool()
            LocationPoolManager.replacePersistent(pool)
            mapOf(
                "source" to "arc-location-pools",
                "created" to !existed,
                "saved" to true,
                "pool" to poolToMap(pool),
            )
        }
    }

    fun delete(id: String): Map<String, Any?> {
        val normalizedId = LocationPool.normalizePersistentId(id)
        return OpsBukkitSync.call {
            if (!LocationPoolManager.delete(normalizedId)) {
                throw NoSuchElementException("Location pool not found: $normalizedId")
            }
            mapOf(
                "source" to "arc-location-pools",
                "deleted" to true,
                "id" to normalizedId,
            )
        }
    }

    internal fun parseDefinition(
        id: String,
        body: JsonObject,
    ): LocationPoolDefinition {
        val normalizedId = LocationPool.normalizePersistentId(id)
        rejectUnknownFields(body, TOP_LEVEL_FIELDS)
        body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "id must be a string" }
            require(it.asString.trim().lowercase() == normalizedId) { "body id must match route id" }
        }

        val locationsElement =
            body.get("locations")?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("locations is required")
        require(locationsElement.isJsonArray) { "locations must be an array" }
        require(locationsElement.asJsonArray.size() in 1..MAX_LOCATIONS) {
            "locations must contain 1-$MAX_LOCATIONS entries; delete the pool instead of saving an empty one"
        }

        val seen = mutableSetOf<String>()
        val locations =
            locationsElement.asJsonArray.mapIndexed { index, element ->
                require(element.isJsonObject) { "locations[$index] must be an object" }
                parseLocation(index, element.asJsonObject).also { location ->
                    val key =
                        listOf(
                            location.location.server,
                            location.location.world,
                            location.location.x.toBits(),
                            location.location.y.toBits(),
                            location.location.z.toBits(),
                        ).joinToString("|")
                    require(seen.add(key)) { "locations[$index] duplicates another coordinate" }
                }
            }
        return LocationPoolDefinition(normalizedId, locations)
    }

    private fun parseLocation(
        index: Int,
        body: JsonObject,
    ): WeightedLocationDefinition {
        rejectUnknownFields(body, LOCATION_FIELDS, "locations[$index]")
        val server = requiredString(body, "server", index, NETWORK_ID_PATTERN)
        val world = requiredString(body, "world", index, WORLD_PATTERN)
        val x = requiredNumber(body, "x", index, -WORLD_BORDER, WORLD_BORDER)
        val y = requiredNumber(body, "y", index, -2048.0, 2048.0)
        val z = requiredNumber(body, "z", index, -WORLD_BORDER, WORLD_BORDER)
        val yaw = optionalNumber(body, "yaw", index, 0.0, -360.0, 360.0).toFloat()
        val pitch = optionalNumber(body, "pitch", index, 0.0, -90.0, 90.0).toFloat()
        val weight = optionalNumber(body, "weight", index, 1.0, 0.000001, 1_000_000.0)
        return WeightedLocationDefinition(
            ServerLocation(
                server = server,
                world = world,
                x = x,
                y = y,
                z = z,
                yaw = yaw,
                pitch = pitch,
            ),
            weight,
        )
    }

    private fun validateForCurrentNode(definition: LocationPoolDefinition) {
        val currentServer =
            ARC.serverName
                ?: throw IllegalStateException("ARC server name is not initialized")
        val local = definition.locations.filter { it.location.server == currentServer }
        require(local.isNotEmpty()) {
            "Location pool ${definition.id} has no locations for current server $currentServer"
        }
        val missingWorlds =
            local
                .mapNotNull { it.location.world }
                .filter { Bukkit.getWorld(it) == null }
                .distinct()
                .sorted()
        require(missingWorlds.isEmpty()) {
            "Location pool ${definition.id} references worlds not loaded on $currentServer: ${missingWorlds.joinToString()}"
        }
    }

    private fun poolToMap(pool: LocationPool): Map<String, Any?> {
        val entries = pool.getWeightedLocations()
        val currentServer = ARC.serverName
        val local = entries.filter { it.value.server == currentServer }
        val localUsable = local.count { Bukkit.getWorld(it.value.world ?: "") != null }
        return mapOf(
            "id" to pool.id,
            "size" to entries.size,
            "localCount" to local.size,
            "localUsable" to localUsable,
            "healthyForCurrentServer" to (localUsable > 0),
            "locations" to entries.map(::weightedLocationToMap),
        )
    }

    private fun definitionToMap(definition: LocationPoolDefinition): Map<String, Any?> {
        val currentServer = ARC.serverName
        val local = definition.locations.filter { it.location.server == currentServer }
        return mapOf(
            "id" to definition.id,
            "size" to definition.locations.size,
            "localCount" to local.size,
            "localUsable" to local.count { Bukkit.getWorld(it.location.world ?: "") != null },
            "locations" to
                definition.locations.map {
                    weightedLocationToMap(WeightedRandom.Pair(it.location, it.weight))
                },
        )
    }

    private fun weightedLocationToMap(entry: WeightedRandom.Pair<ServerLocation>): Map<String, Any?> =
        mapOf(
            "server" to entry.value.server,
            "world" to entry.value.world,
            "x" to entry.value.x,
            "y" to entry.value.y,
            "z" to entry.value.z,
            "yaw" to entry.value.yaw,
            "pitch" to entry.value.pitch,
            "weight" to entry.weight,
        )

    private fun requiredString(
        body: JsonObject,
        field: String,
        index: Int,
        pattern: Regex,
    ): String {
        val element =
            body.get(field)?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("locations[$index].$field is required")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "locations[$index].$field must be a string"
        }
        val value = element.asString.trim()
        require(pattern.matches(value)) { "locations[$index].$field has an invalid value: $value" }
        return value
    }

    private fun requiredNumber(
        body: JsonObject,
        field: String,
        index: Int,
        min: Double,
        max: Double,
    ): Double {
        val element =
            body.get(field)?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("locations[$index].$field is required")
        return parseNumber(element, "locations[$index].$field", min, max)
    }

    private fun optionalNumber(
        body: JsonObject,
        field: String,
        index: Int,
        default: Double,
        min: Double,
        max: Double,
    ): Double {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return default
        return parseNumber(element, "locations[$index].$field", min, max)
    }

    private fun parseNumber(
        element: JsonElement,
        path: String,
        min: Double,
        max: Double,
    ): Double {
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path must be a number" }
        val value = element.asDouble
        require(value.isFinite() && value in min..max) { "$path must be finite and between $min and $max" }
        return value
    }

    private fun rejectUnknownFields(
        body: JsonObject,
        allowed: Set<String>,
        path: String = "body",
    ) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private val TOP_LEVEL_FIELDS = setOf("id", "locations")
    private val LOCATION_FIELDS = setOf("server", "world", "x", "y", "z", "yaw", "pitch", "weight")
    private val NETWORK_ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,47}$")
    private val WORLD_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private const val MAX_LOCATIONS = 5000
    private const val WORLD_BORDER = 30_000_000.0
}

internal data class LocationPoolDefinition(
    val id: String,
    val locations: List<WeightedLocationDefinition>,
) {
    fun toPool(): LocationPool =
        LocationPool(id).also { pool ->
            locations.forEach { pool.addLocation(it.location, it.weight) }
        }
}

internal data class WeightedLocationDefinition(
    val location: ServerLocation,
    val weight: Double,
)
