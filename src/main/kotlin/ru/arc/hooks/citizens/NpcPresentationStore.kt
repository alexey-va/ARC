package ru.arc.hooks.citizens

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.arc.persistence.AtomicFileStore
import ru.arc.util.Common
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/** Values copied from Citizens before ARC removes its native presentation state. */
internal data class NpcPresentation(
    val name: String,
    val nameVisible: Boolean,
    val lines: List<String>,
    val lineHeight: Double = 0.25,
    val viewRange: Int = -1,
    val hasHologram: Boolean = true,
    val speechBubbles: Boolean? = null,
    val sendTextToChat: Boolean? = null,
)

internal data class NpcPresentationRecord(
    val npcId: Int,
    val presentation: NpcPresentation,
    val legacyBackup: String? = null,
)

internal interface NpcPresentationStore {
    fun load(): Map<UUID, NpcPresentationRecord>

    fun save(records: Map<UUID, NpcPresentationRecord>)
}

/**
 * Durable ARC-owned snapshot of Citizens presentation values.
 *
 * The complete catalog is replaced in one atomic commit. Callers should load
 * once during startup, then save one complete snapshot after each migration or
 * batch of changes; this keeps the native-state removal behind a durable
 * read-back boundary.
 */
internal class FileNpcPresentationStore(root: Path) : NpcPresentationStore {
    private val file =
        AtomicFileStore(
            root = root,
            relativePath = Path.of("data/npc-presentations.json"),
            maxBytes = MAX_FILE_BYTES,
            encode = { records -> encode(records) },
            decode = { bytes -> decode(bytes) },
            validate = ::validate,
        )

    override fun load(): Map<UUID, NpcPresentationRecord> =
        immutableSnapshot(file.loadOrDefault { emptyMap() })

    override fun save(records: Map<UUID, NpcPresentationRecord>) {
        file.write(immutableSnapshot(records))
    }

    private fun encode(records: Map<UUID, NpcPresentationRecord>): ByteArray {
        val wireRecords =
            records.entries
                .sortedBy { it.key.toString() }
                .associateTo(linkedMapOf()) { (uuid, record) ->
                    uuid.toString() to
                        PersistedRecord(
                            npcId = record.npcId,
                            presentation = record.presentation,
                            legacyBackup = record.legacyBackup,
                        )
                }
        return Common.prettyGson
            .toJson(PersistedCatalog(schemaVersion = SCHEMA_VERSION, records = wireRecords))
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Map<UUID, NpcPresentationRecord> {
        return try {
            parseCatalog(JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)))
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Cannot decode NPC presentation catalog: ${error.message}", error)
        }
    }

    private fun parseCatalog(root: JsonElement): Map<UUID, NpcPresentationRecord> {
        require(root.isJsonObject) { "NPC presentation catalog root must be an object" }
        val jsonObject = root.asJsonObject
        requireFields(jsonObject, ROOT_FIELDS, "catalog")
        require(jsonObject.requiredInt("schemaVersion", "catalog") == SCHEMA_VERSION) {
            "Unsupported NPC presentation catalog schemaVersion: ${jsonObject["schemaVersion"]}"
        }

        val recordsElement = jsonObject["records"]
        require(recordsElement.isJsonObject) { "catalog.records must be an object" }
        val records = LinkedHashMap<UUID, NpcPresentationRecord>()
        recordsElement.asJsonObject.entrySet().forEach { (rawUuid, value) ->
            val uuid = parseUuid(rawUuid, "catalog.records key")
            require(!records.containsKey(uuid)) { "Duplicate NPC presentation UUID: $uuid" }
            records[uuid] = parseRecord(value, "catalog.records[$rawUuid]")
        }
        val snapshot = immutableSnapshot(records)
        validate(snapshot)
        return snapshot
    }

    private fun parseRecord(element: JsonElement, path: String): NpcPresentationRecord {
        require(element.isJsonObject) { "$path must be an object" }
        val jsonObject = element.asJsonObject
        requireFields(jsonObject, RECORD_FIELDS, path)
        return NpcPresentationRecord(
            npcId = jsonObject.requiredInt("npcId", path),
            presentation = parsePresentation(jsonObject.requiredElement("presentation", path), "$path.presentation"),
            legacyBackup = jsonObject.optionalNullableString("legacyBackup", path),
        )
    }

    private fun parsePresentation(element: JsonElement, path: String): NpcPresentation {
        require(element.isJsonObject) { "$path must be an object" }
        val jsonObject = element.asJsonObject
        requireFields(jsonObject, PRESENTATION_FIELDS, path)
        return NpcPresentation(
            name = jsonObject.requiredString("name", path),
            nameVisible = jsonObject.requiredBoolean("nameVisible", path),
            lines = jsonObject.requiredStringList("lines", path),
            lineHeight = jsonObject.optionalFiniteDouble("lineHeight", path, DEFAULT_LINE_HEIGHT),
            viewRange = jsonObject.optionalInt("viewRange", path, DEFAULT_VIEW_RANGE),
            hasHologram = jsonObject.optionalBoolean("hasHologram", path, DEFAULT_HAS_HOLOGRAM),
            speechBubbles = jsonObject.optionalNullableBoolean("speechBubbles", path),
            sendTextToChat = jsonObject.optionalNullableBoolean("sendTextToChat", path),
        )
    }

    private fun validate(records: Map<UUID, NpcPresentationRecord>) {
        require(records.size <= MAX_RECORDS) {
            "NPC presentation catalog exceeds the $MAX_RECORDS-record limit"
        }
        records.forEach { (uuid, record) ->
            require(record.npcId >= 0) { "NPC $uuid has a negative Citizens id: ${record.npcId}" }

            val presentation = record.presentation
            require(presentation.name.length <= MAX_TEXT_LENGTH) {
                "NPC $uuid name exceeds $MAX_TEXT_LENGTH characters"
            }
            require(presentation.lines.size <= MAX_LINES) {
                "NPC $uuid body exceeds the $MAX_LINES-line limit"
            }
            presentation.lines.forEachIndexed { index, line ->
                require(line.length <= MAX_TEXT_LENGTH) {
                    "NPC $uuid line $index exceeds $MAX_TEXT_LENGTH characters"
                }
            }
            require(presentation.lineHeight.isFinite()) {
                "NPC $uuid has non-finite line height"
            }
            require(presentation.viewRange >= -1) {
                "NPC $uuid has an invalid view range: ${presentation.viewRange}"
            }
            record.legacyBackup?.let {
                require(it.length <= MAX_LEGACY_BACKUP_LENGTH) {
                    "NPC $uuid legacy backup exceeds $MAX_LEGACY_BACKUP_LENGTH characters"
                }
            }
        }
    }

    private fun immutableSnapshot(records: Map<UUID, NpcPresentationRecord>): Map<UUID, NpcPresentationRecord> {
        val copy = LinkedHashMap<UUID, NpcPresentationRecord>(records.size)
        records.forEach { (uuid, record) ->
            requireNotNull(uuid) { "NPC presentation catalog contains a null UUID key" }
            copy[uuid] =
                record.copy(
                    presentation =
                        record.presentation.copy(
                            lines = Collections.unmodifiableList(ArrayList(record.presentation.lines)),
                        ),
                )
        }
        return Collections.unmodifiableMap(copy)
    }

    private data class PersistedCatalog(
        val schemaVersion: Int,
        val records: Map<String, PersistedRecord>,
    )

    private data class PersistedRecord(
        val npcId: Int,
        val presentation: NpcPresentation,
        val legacyBackup: String? = null,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_FILE_BYTES = 32L * 1024L * 1024L
        const val MAX_RECORDS = 50_000
        const val MAX_LINES = 2_048
        const val MAX_TEXT_LENGTH = 8_192
        const val MAX_LEGACY_BACKUP_LENGTH = 1_048_576
        const val DEFAULT_LINE_HEIGHT = 0.25
        const val DEFAULT_VIEW_RANGE = -1
        const val DEFAULT_HAS_HOLOGRAM = true

        val ROOT_FIELDS = setOf("schemaVersion", "records")
        val RECORD_FIELDS = setOf("npcId", "presentation", "legacyBackup")
        val PRESENTATION_FIELDS =
            setOf(
                "name",
                "nameVisible",
                "lines",
                "lineHeight",
                "viewRange",
                "hasHologram",
                "speechBubbles",
                "sendTextToChat",
            )
    }
}

private fun JsonObject.requiredElement(field: String, path: String): JsonElement =
    get(field)?.takeUnless { it.isJsonNull } ?: throw IllegalArgumentException("$path.$field is required")

private fun JsonObject.requiredString(field: String, path: String): String {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path.$field must be a string" }
    return value.asString
}

private fun JsonObject.optionalNullableString(field: String, path: String): String? {
    val value = get(field) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path.$field must be a string or null" }
    return value.asString
}

private fun JsonObject.requiredBoolean(field: String, path: String): Boolean {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$path.$field must be a boolean" }
    return value.asBoolean
}

private fun JsonObject.optionalNullableBoolean(field: String, path: String): Boolean? {
    val value = get(field) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$path.$field must be a boolean or null" }
    return value.asBoolean
}

private fun JsonObject.optionalBoolean(field: String, path: String, default: Boolean): Boolean {
    val value = get(field) ?: return default
    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$path.$field must be a boolean" }
    return value.asBoolean
}

private fun JsonObject.requiredInt(field: String, path: String): Int {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path.$field must be an integer" }
    val raw = value.asString
    return raw.toLongOrNull()?.let { exact ->
        require(exact in Int.MIN_VALUE..Int.MAX_VALUE) { "$path.$field is outside the integer range" }
        exact.toInt()
    } ?: throw IllegalArgumentException("$path.$field must be an integer")
}

private fun JsonObject.optionalInt(field: String, path: String, default: Int): Int =
    if (has(field)) requiredInt(field, path) else default

private fun JsonObject.optionalFiniteDouble(field: String, path: String, default: Double): Double {
    val value = get(field) ?: return default
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path.$field must be a number" }
    val result = value.asDouble
    require(result.isFinite()) { "$path.$field must be finite" }
    return result
}

private fun JsonObject.requiredStringList(field: String, path: String): List<String> {
    val value = requiredElement(field, path)
    require(value.isJsonArray) { "$path.$field must be an array" }
    return value.asJsonArray.mapIndexed { index, element ->
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$path.$field[$index] must be a string"
        }
        element.asString
    }
}

private fun requireFields(jsonObject: JsonObject, allowed: Set<String>, path: String) {
    val unknown = jsonObject.keySet() - allowed
    require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString(", ")}" }
}

private fun parseUuid(raw: String, path: String): UUID {
    val uuid = try {
        UUID.fromString(raw)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$path must be a canonical UUID: $raw", error)
    }
    require(uuid.toString().equals(raw, ignoreCase = true)) { "$path must be a canonical UUID: $raw" }
    return uuid
}
