package ru.arc.spy

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class SpyRelayType {
    CHAT,
    COMMAND,
}

data class SpyRelayMessage(
    val id: UUID,
    val type: SpyRelayType,
    val senderUuid: UUID,
    val senderName: String,
    val targetUuid: UUID?,
    val targetName: String?,
    val content: String,
    val createdAt: Long,
)

object SpyRelayCodec {
    const val VERSION = 1

    private val commonKeys = setOf("v", "id", "type", "senderUuid", "senderName", "content", "createdAt")
    private val chatKeys = commonKeys + setOf("targetUuid", "targetName")

    fun encode(message: SpyRelayMessage): String {
        val json = JsonObject()
        json.addProperty("v", VERSION)
        json.addProperty("id", message.id.toString())
        json.addProperty("type", message.type.name.lowercase())
        json.addProperty("senderUuid", message.senderUuid.toString())
        json.addProperty("senderName", message.senderName)
        if (message.type == SpyRelayType.CHAT) {
            if (message.targetUuid == null) json.add("targetUuid", JsonNull.INSTANCE) else json.addProperty("targetUuid", message.targetUuid.toString())
            json.addProperty("targetName", message.targetName)
        }
        json.addProperty("content", message.content)
        json.addProperty("createdAt", message.createdAt)
        return json.toString()
    }

    fun decode(raw: String, maxPayloadBytes: Int, maxContentLength: Int): SpyRelayMessage? {
        if (raw.toByteArray(StandardCharsets.UTF_8).size !in 2..maxPayloadBytes) return null
        if (!raw.startsWith('{') || !raw.endsWith('}')) return null

        val json =
            try {
                JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            } catch (_: RuntimeException) {
                return null
            } catch (_: StackOverflowError) {
                return null
            }

        val version = json.requiredInt("v") ?: return null
        if (version != VERSION) return null
        val type =
            when (json.requiredString("type")) {
                "chat" -> SpyRelayType.CHAT
                "command" -> SpyRelayType.COMMAND
                else -> return null
            }
        if (json.keySet() != if (type == SpyRelayType.CHAT) chatKeys else commonKeys) return null

        val id = json.requiredUuid("id") ?: return null
        val senderUuid = json.requiredUuid("senderUuid") ?: return null
        val senderName = json.requiredString("senderName")?.takeIf(::validPlayerName) ?: return null
        val content =
            json.requiredString("content")
                ?.takeIf { it.length in 1..maxContentLength && it.none(Char::isISOControl) }
                ?: return null
        val createdAt = json.requiredLong("createdAt") ?: return null

        val targetName: String?
        val targetUuid: UUID?
        if (type == SpyRelayType.CHAT) {
            val targetNameElement = json.get("targetName") ?: return null
            val targetUuidElement = json.get("targetUuid") ?: return null
            if (targetNameElement.isJsonNull) {
                if (!targetUuidElement.isJsonNull) return null
                targetName = null
                targetUuid = null
            } else {
                targetName =
                    targetNameElement
                        .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?.takeIf(::validPlayerName)
                        ?: return null
                targetUuid =
                    when {
                        targetUuidElement.isJsonNull -> null
                        targetUuidElement.isJsonPrimitive && targetUuidElement.asJsonPrimitive.isString ->
                            parseCanonicalUuid(targetUuidElement.asString) ?: return null
                        else -> return null
                    }
            }
        } else {
            targetName = null
            targetUuid = null
        }

        return SpyRelayMessage(
            id = id,
            type = type,
            senderUuid = senderUuid,
            senderName = senderName,
            targetUuid = targetUuid,
            targetName = targetName,
            content = content,
            createdAt = createdAt,
        )
    }

    fun sanitizeContent(raw: String, maxLength: Int): String =
        raw
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .take(maxLength)

    private fun validPlayerName(value: String): Boolean = PLAYER_NAME.matches(value)

    private fun JsonObject.requiredString(key: String): String? {
        val value = get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun JsonObject.requiredInt(key: String): Int? {
        val value = get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonObject.requiredLong(key: String): Long? {
        val value = get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { value.asLong }.getOrNull()
    }

    private fun JsonObject.requiredUuid(key: String): UUID? =
        requiredString(key)?.let(::parseCanonicalUuid)

    private fun parseCanonicalUuid(raw: String): UUID? =
        runCatching { UUID.fromString(raw) }
            .getOrNull()
            ?.takeIf { it.toString() == raw }

    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
}

class SpyReplayGuard(
    private val capacity: Int = 2048,
) {
    private val seen = LinkedHashMap<UUID, Long>()

    @Synchronized
    fun accept(id: UUID, createdAt: Long, oldestAllowedAt: Long): Boolean {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < oldestAllowedAt) iterator.remove()
        }
        if (seen.containsKey(id)) return false
        seen[id] = createdAt
        while (seen.size > capacity) {
            val eldest = seen.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return true
    }
}

data class AcceptedSpyRelay(
    val origin: String,
    val message: SpyRelayMessage,
)

class SpyRelayIngress(
    private val localServer: String,
    private val settings: CrossServerSpySettings,
    private val replayGuard: SpyReplayGuard = SpyReplayGuard(),
) {
    fun accept(originServer: String, raw: String, receivedAt: Long): AcceptedSpyRelay? {
        val origin = CrossServerSpyConfig.normalizeToken(originServer)
        if (origin == localServer || origin !in settings.allowedServers) return null
        val message = SpyRelayCodec.decode(raw, settings.maxPayloadBytes, settings.maxContentLength) ?: return null
        val age = receivedAt - message.createdAt
        if (age > settings.maxMessageAgeMillis || age < -settings.maxFutureSkewMillis) return null
        if (!replayGuard.accept(message.id, message.createdAt, receivedAt - settings.maxMessageAgeMillis)) return null
        return AcceptedSpyRelay(origin, message)
    }
}
