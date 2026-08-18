package ru.arc.worldcontent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class SceneObjectKind(val wireName: String) {
    MINECRAFT_BLOCK("minecraft_block"),
    ITEMSADDER_FURNITURE("itemsadder_furniture"),
}

enum class FurniturePlacement(val wireName: String) {
    BLOCK("block"),
    PRECISE_NON_SOLID("precise_non_solid"),
}

data class SceneObjectSpec(
    val id: String,
    val kind: SceneObjectKind,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val blockData: String? = null,
    val namespacedId: String? = null,
    val placement: FurniturePlacement? = null,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun canonical(): String =
        listOf(
            id,
            kind.wireName,
            world,
            x.toString(),
            y.toString(),
            z.toString(),
            blockData.orEmpty(),
            namespacedId.orEmpty(),
            placement?.wireName.orEmpty(),
            yaw.toString(),
            pitch.toString(),
        ).joinToString("|")

    companion object {
        fun block(
            id: String,
            world: String,
            x: Int,
            y: Int,
            z: Int,
            blockData: String,
        ): SceneObjectSpec =
            SceneObjectSpec(
                id = id,
                kind = SceneObjectKind.MINECRAFT_BLOCK,
                world = world,
                x = x.toDouble(),
                y = y.toDouble(),
                z = z.toDouble(),
                blockData = blockData,
            )
    }
}

data class WorldSceneSpec(
    val id: String,
    val objects: List<SceneObjectSpec>,
) {
    fun canonical(): String =
        buildString {
            append(id).append('\n')
            objects.sortedBy { it.id }.forEach { append(it.canonical()).append('\n') }
        }
}

object WorldSceneSpecParser {
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val worldPattern = Regex("[A-Za-z0-9_.-]{1,128}")
    private val namespacedPattern = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    private val topFields = setOf("id", "objects", "reviewDigest")
    private val commonFields = setOf("id", "kind", "world", "x", "y", "z")
    private val blockFields = commonFields + "blockData"
    private val furnitureFields = commonFields + setOf("namespacedId", "placement", "yaw", "pitch")

    fun parse(
        routeId: String,
        body: JsonObject,
    ): WorldSceneSpec {
        val id = normalizeId(routeId, "scene id")
        rejectUnknown(body, topFields, "scene")
        body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "id must be a string" }
            require(normalizeId(it.asString, "scene id") == id) { "body id must match route id" }
        }
        val array = body.get("objects")?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("objects is required")
        require(array.isJsonArray) { "objects must be an array" }
        require(array.asJsonArray.size() <= MAX_OBJECTS) { "objects must contain at most $MAX_OBJECTS entries" }
        val seen = mutableSetOf<String>()
        val objects =
            array.asJsonArray.mapIndexed { index, element ->
                require(element.isJsonObject) { "objects[$index] must be an object" }
                parseObject(index, element.asJsonObject).also {
                    require(seen.add(it.id)) { "duplicate object id: ${it.id}" }
                }
            }
        return WorldSceneSpec(id, objects)
    }

    private fun parseObject(
        index: Int,
        body: JsonObject,
    ): SceneObjectSpec {
        val kindText = string(body, "kind", "objects[$index]")
        val kind = SceneObjectKind.entries.firstOrNull { it.wireName == kindText }
            ?: throw IllegalArgumentException("objects[$index].kind is unsupported: $kindText")
        rejectUnknown(body, if (kind == SceneObjectKind.MINECRAFT_BLOCK) blockFields else furnitureFields, "objects[$index]")
        val id = normalizeId(string(body, "id", "objects[$index]"), "object id")
        val world = string(body, "world", "objects[$index]")
        require(worldPattern.matches(world)) { "objects[$index].world is invalid" }
        val x = number(body, "x", "objects[$index]")
        val y = number(body, "y", "objects[$index]")
        val z = number(body, "z", "objects[$index]")
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0) {
            "objects[$index] is outside the world border"
        }
        require(y in -2048.0..2048.0) { "objects[$index].y is outside supported bounds" }

        return when (kind) {
            SceneObjectKind.MINECRAFT_BLOCK -> {
                require(x % 1.0 == 0.0 && y % 1.0 == 0.0 && z % 1.0 == 0.0) {
                    "minecraft_block coordinates must be integers"
                }
                val blockData = string(body, "blockData", "objects[$index]").trim()
                require(blockData.length in 1..512) { "objects[$index].blockData is invalid" }
                SceneObjectSpec.block(id, world, x.toInt(), y.toInt(), z.toInt(), blockData)
            }

            SceneObjectKind.ITEMSADDER_FURNITURE -> {
                val namespacedId = string(body, "namespacedId", "objects[$index]").lowercase()
                require(namespacedPattern.matches(namespacedId)) { "objects[$index].namespacedId is invalid" }
                val placementText = body.get("placement")?.takeUnless(JsonElement::isJsonNull)?.asString ?: "block"
                val placement = FurniturePlacement.entries.firstOrNull { it.wireName == placementText }
                    ?: throw IllegalArgumentException("objects[$index].placement is unsupported: $placementText")
                val yaw = optionalNumber(body, "yaw", 0.0).toFloat()
                val pitch = optionalNumber(body, "pitch", 0.0).toFloat()
                require(yaw in -360f..360f) { "objects[$index].yaw must be in -360..360" }
                require(pitch in -90f..90f) { "objects[$index].pitch must be in -90..90" }
                if (placement == FurniturePlacement.BLOCK) {
                    require(x % 1.0 == 0.0 && y % 1.0 == 0.0 && z % 1.0 == 0.0) {
                        "block furniture coordinates must be integers"
                    }
                    require(yaw == 0f && pitch == 0f) {
                        "block furniture requires yaw=0 and pitch=0 with the public ItemsAdder API"
                    }
                }
                SceneObjectSpec(
                    id = id,
                    kind = kind,
                    world = world,
                    x = x,
                    y = y,
                    z = z,
                    namespacedId = namespacedId,
                    placement = placement,
                    yaw = yaw,
                    pitch = pitch,
                )
            }
        }
    }

    private fun normalizeId(
        raw: String,
        label: String,
    ): String {
        val normalized = raw.trim().lowercase()
        require(idPattern.matches(normalized)) { "$label must match ${idPattern.pattern}" }
        return normalized
    }

    private fun string(
        body: JsonObject,
        field: String,
        path: String,
    ): String {
        val value = body.get(field)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path.$field is required")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path.$field must be a string" }
        return value.asString
    }

    private fun number(
        body: JsonObject,
        field: String,
        path: String,
    ): Double {
        val value = body.get(field)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path.$field is required")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path.$field must be a number" }
        return value.asDouble.also { require(it.isFinite()) { "$path.$field must be finite" } }
    }

    private fun optionalNumber(
        body: JsonObject,
        field: String,
        default: Double,
    ): Double {
        val value = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$field must be a number" }
        return value.asDouble.also { require(it.isFinite()) { "$field must be finite" } }
    }

    private fun rejectUnknown(
        body: JsonObject,
        allowed: Set<String>,
        path: String,
    ) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    const val MAX_OBJECTS = 500
}

object WorldSceneReview {
    fun digest(
        spec: WorldSceneSpec,
        revision: Long,
        liveFingerprint: String,
    ): String {
        val canonical = "${spec.canonical()}revision=$revision\nlive=$liveFingerprint\n"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
