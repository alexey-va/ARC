package ru.arc.graves

import com.google.gson.Gson
import ru.arc.persistence.AtomicFileStore
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import java.nio.file.Path
import java.util.UUID

/** Local scenery only: this record has no inventory, experience, death or recovery identity. */
internal data class DecorativeGraveRecord(
    val id: UUID,
    val createdBy: UUID,
    val worldId: UUID,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long,
    val revision: Long,
    val style: GraveStyle,
    val epitaph: String,
    val signature: String,
    val yaw: Float,
) {
    fun view(server: String) = GraveView(id, createdBy, server, world, x, y, z, createdAt, -1, 0, emptyList(),
        signature, GraveAppearanceChoice(style.modelId, style.engraved, epitaph), decorative = true, cosmeticYaw = yaw)

    fun validate() {
        requireNotNull(id); requireNotNull(createdBy); requireNotNull(worldId); requireNotNull(style)
        require(world.isNotBlank() && world.length <= 128 && world.none(Char::isISOControl))
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000 && y in -2048..2048)
        require(createdAt >= 0 && revision >= 0 && yaw in setOf(0f, 90f, 180f, 270f))
        require(epitaph.isNotBlank() && GravePreferences.validText(epitaph))
        require(signature.length <= MAX_SIGNATURE_LENGTH && GravePreferences.validText(signature))
    }

    companion object { const val MAX_SIGNATURE_LENGTH = 17 }
}

/** Construct, load and write exclusively on ARC's background executor. */
internal class DecorativeGraveStore(root: Path) {
    private data class Catalog(val version: Int, val records: List<DecorativeGraveRecord>)
    private val string = object : JsonRootContract {
        override fun validate(value: com.google.gson.JsonElement) {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Decorative grave field must be a string" }
        }
    }
    private fun integer(min: Long, max: Long) = object : JsonRootContract {
        override fun validate(value: com.google.gson.JsonElement) {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Decorative grave field must be a number" }
            require(value.asBigDecimal.longValueExact() in min..max) { "Decorative grave integer is outside its range" }
        }
    }
    private val recordContract = JsonObjectContract(setOf(
        "id", "createdBy", "worldId", "world", "x", "y", "z", "createdAt", "revision", "style", "epitaph", "signature", "yaw",
    ), fieldContracts = buildMap {
        listOf("id", "createdBy", "worldId", "world", "style", "epitaph", "signature").forEach { put(it, string) }
        listOf("x", "z").forEach { put(it, integer(-30_000_000, 30_000_000)) }
        put("y", integer(-2048, 2048)); put("createdAt", integer(0, Long.MAX_VALUE)); put("revision", integer(0, Long.MAX_VALUE))
        put("yaw", integer(0, 270))
    })
    private val codec = BoundedJsonCodec(Gson(), Catalog::class.java,
        JsonObjectContract(setOf("version", "records"), fieldContracts = mapOf("version" to integer(1, 1),
            "records" to JsonArrayContract(maxEntries = MAX_RECORDS, elementContract = recordContract))),
        JsonResourceBounds(4_194_304, maxDepth = 4, maxContainerEntries = MAX_RECORDS,
            maxTotalNodes = 65_536, maxStringCharacters = 128),
        { catalog ->
            require(catalog.version == 1 && catalog.records.size <= MAX_RECORDS)
            require(catalog.records.map { it.id }.distinct().size == catalog.records.size)
            catalog.records.forEach(DecorativeGraveRecord::validate)
        },
    )
    private val file = AtomicFileStore(root, Path.of("data/decorative-graves.json"), 4_194_304L,
        { value: Catalog -> codec.encode(value).toByteArray(Charsets.UTF_8) },
        { bytes -> codec.decode(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()) },
        { value -> value.records.forEach(DecorativeGraveRecord::validate) },
    )

    fun load(): List<DecorativeGraveRecord> = file.loadOrDefault { Catalog(1, emptyList()) }.records.toList()
    fun save(records: List<DecorativeGraveRecord>) {
        val snapshot = Catalog(1, records.toList())
        check(file.write(snapshot) == snapshot) { "Decorative grave readback differs" }
    }

    companion object { const val MAX_RECORDS = 4096 }
}
