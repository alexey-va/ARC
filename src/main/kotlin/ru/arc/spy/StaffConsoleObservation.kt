package ru.arc.spy

import com.google.gson.Gson
import com.google.gson.JsonObject
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.UUID

/** An observation only. This topic never accepts a command for execution. Origin is the Redis envelope. */
data class StaffConsoleObservation(
    val id: UUID,
    val command: String,
    val actor: String,
    val createdAt: Long,
)

object StaffConsoleObservationCodec {
    const val CHANNEL = "arc.staff-console.v1"
    private val codec = BoundedJsonCodec(
        Gson(), JsonObject::class.java,
        JsonObjectContract(setOf("v", "id", "command", "actor", "createdAt")),
        JsonResourceBounds(4096, 2, 5, 6, 512),
    ) { json ->
        require(json.get("v")?.toString() == "1")
        require(canonicalUuid(string(json, "id")) != null)
        val command = string(json, "command")
        require(command.length in 1..512 && command.isNotBlank() && command.none(::unsafeLineCharacter))
        val actor = string(json, "actor")
        require(actor == "CONSOLE" || canonicalUuid(actor) != null)
        val timestamp = json.get("createdAt")
        require(timestamp?.isJsonPrimitive == true && timestamp.asJsonPrimitive.isNumber)
        require(timestamp.toString().toLongOrNull()?.let { it >= 0L } == true)
    }

    fun encode(message: StaffConsoleObservation): String = codec.encode(JsonObject().apply {
        addProperty("v", 1)
        addProperty("id", message.id.toString())
        addProperty("command", message.command)
        addProperty("actor", message.actor)
        addProperty("createdAt", message.createdAt)
    })

    fun decode(raw: String): StaffConsoleObservation = codec.decode(raw).let { json ->
        StaffConsoleObservation(
            UUID.fromString(string(json, "id")), string(json, "command"),
            string(json, "actor"), json.get("createdAt").asLong,
        )
    }

    private fun string(json: JsonObject, key: String): String {
        val value = json.get(key)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString)
        return value.asString
    }

    internal fun canonicalUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value }

    internal fun unsafeLineCharacter(character: Char): Boolean =
        character.isISOControl() || character == '\u2028' || character == '\u2029'
}
