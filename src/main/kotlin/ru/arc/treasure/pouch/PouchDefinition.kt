package ru.arc.treasure.pouch

import com.google.gson.JsonObject
import ru.arc.util.Common

private val POUCH_ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

data class PouchRolls(
    val min: Int,
    val max: Int,
) {
    init {
        require(min >= 1) { "rolls minimum must be at least 1" }
        require(max >= min) { "rolls maximum must be at least minimum" }
        require(max <= 64) { "rolls maximum must not exceed 64" }
    }
}

data class PouchRewardSource(
    val poolId: String,
    val rolls: PouchRolls,
    val chance: Double = 1.0,
) {
    init {
        require(poolId.isNotBlank()) { "pool is required" }
        require(chance > 0.0 && chance <= 1.0) { "chance must be greater than 0 and at most 1" }
    }
}

data class PouchOpenPresentation(
    val message: String = "<green>Вы открыли <white>%pouch%<green> и получили наград: <white>%rewards%",
    val sound: String = "ui.loom.take_result",
    val volume: Float = 1f,
    val pitch: Float = 1f,
)

data class PouchDefinition(
    val id: String,
    val description: String?,
    val item: JsonObject,
    val rewards: List<PouchRewardSource>,
    val open: PouchOpenPresentation,
) {
    init {
        require(POUCH_ID_PATTERN.matches(id)) { "pouch ID must match ${POUCH_ID_PATTERN.pattern}: $id" }
        require(item.size() > 0) { "pouch '$id' item must not be empty" }
        require(rewards.isNotEmpty()) { "pouch '$id' must contain rewards" }
        require(rewards.any { it.chance == 1.0 }) { "pouch '$id' must contain at least one guaranteed reward source" }
        require(rewards.sumOf { it.rolls.max } <= 64) { "pouch '$id' must not exceed 64 total rolls" }
    }

    fun itemSpec(amount: Int = 1): JsonObject =
        item.deepCopy().apply {
            addProperty("amount", amount.coerceIn(1, 64))
            val data = getAsJsonObject("customData") ?: JsonObject().also { add("customData", it) }
            data.addProperty("arc:pouch_id", id)
        }
}

object PouchDefinitionParser {
    private val definitionFields = setOf("description", "item", "rewards", "open")
    private val rewardFields = setOf("pool", "rolls", "chance")
    private val openFields = setOf("message", "sound", "volume", "pitch")

    fun normalize(raw: String): String {
        val normalized = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        require(POUCH_ID_PATTERN.matches(normalized)) { "pouch ID must match ${POUCH_ID_PATTERN.pattern}: $raw" }
        return normalized
    }

    fun parse(
        rawId: String,
        raw: Map<String, Any?>,
    ): PouchDefinition {
        val id = normalize(rawId)
        rejectUnknown("pouches.$id", raw.keys, definitionFields)

        val itemMap = raw["item"] as? Map<*, *>
            ?: throw IllegalArgumentException("pouches.$id.item must be an object")
        require(itemMap.isNotEmpty()) { "pouches.$id.item must not be empty" }
        val item = Common.gson.toJsonTree(stringKeyMap(itemMap, "pouches.$id.item")).asJsonObject
        require(!item.has("nbt")) {
            "pouches.$id.item.nbt is not allowed; use scalar customData so arc:pouch_id cannot be overwritten"
        }
        item.get("customData")?.takeIf { !it.isJsonNull }?.let { customData ->
            require(customData.isJsonObject) { "pouches.$id.item.customData must be an object" }
            require(!customData.asJsonObject.has("arc:pouch_id")) {
                "pouches.$id.item.customData.arc:pouch_id is reserved"
            }
        }

        val rewardList = raw["rewards"] as? List<*>
            ?: throw IllegalArgumentException("pouches.$id.rewards must be a list")
        val rewards = rewardList.mapIndexed { index, entry ->
            val entryMap = entry as? Map<*, *>
                ?: throw IllegalArgumentException("pouches.$id.rewards[$index] must be an object")
            val values = stringKeyMap(entryMap, "pouches.$id.rewards[$index]")
            rejectUnknown("pouches.$id.rewards[$index]", values.keys, rewardFields)
            val pool = values["pool"]?.toString()?.trim().orEmpty()
            val rolls = parseRolls(values["rolls"], "pouches.$id.rewards[$index].rolls")
            val chance = parseChance(values["chance"], "pouches.$id.rewards[$index].chance")
            PouchRewardSource(pool, rolls, chance)
        }

        val open = parseOpen(id, raw["open"])
        return PouchDefinition(id, raw["description"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }, item, rewards, open)
    }

    private fun parseRolls(raw: Any?, path: String): PouchRolls =
        when (raw) {
            null -> PouchRolls(1, 1)
            is Number -> PouchRolls(raw.toInt(), raw.toInt())
            is String -> {
                val parts = raw.trim().split('-', limit = 2)
                val min = parts[0].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("$path must be an integer or min-max range")
                val max = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: min
                PouchRolls(min, max)
            }
            else -> throw IllegalArgumentException("$path must be an integer or min-max range")
        }

    private fun parseChance(raw: Any?, path: String): Double {
        if (raw == null) return 1.0
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().removeSuffix("%").toDoubleOrNull()?.let {
                if (raw.trim().endsWith('%')) it / 100.0 else it
            }
            else -> null
        } ?: throw IllegalArgumentException("$path must be a decimal from 0 to 1 or a percent")
        require(value > 0.0 && value <= 1.0) { "$path must be greater than 0 and at most 1" }
        return value
    }

    private fun parseOpen(id: String, raw: Any?): PouchOpenPresentation {
        if (raw == null) return PouchOpenPresentation()
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("pouches.$id.open must be an object")
        val values = stringKeyMap(map, "pouches.$id.open")
        rejectUnknown("pouches.$id.open", values.keys, openFields)
        return PouchOpenPresentation(
            message = values["message"]?.toString() ?: PouchOpenPresentation().message,
            sound = values["sound"]?.toString() ?: PouchOpenPresentation().sound,
            volume = (values["volume"] as? Number)?.toFloat() ?: 1f,
            pitch = (values["pitch"] as? Number)?.toFloat() ?: 1f,
        )
    }

    private fun stringKeyMap(raw: Map<*, *>, path: String): Map<String, Any?> =
        raw.entries.associate { (key, value) ->
            val stringKey = key?.toString() ?: throw IllegalArgumentException("$path contains a null key")
            stringKey to value
        }

    private fun rejectUnknown(path: String, actual: Set<String>, allowed: Set<String>) {
        val unknown = actual - allowed
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}" }
    }
}
