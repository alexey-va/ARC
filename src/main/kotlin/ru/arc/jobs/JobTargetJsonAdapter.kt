package ru.arc.jobs

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Stable Redis wire format for [JobTarget].
 *
 * New values are stored as a string (`all` or a job name). Object-shaped
 * values remain readable for compatibility with records written before the
 * adapter was introduced.
 */
object JobTargetJsonAdapter : JsonSerializer<JobTarget>, JsonDeserializer<JobTarget> {
    override fun serialize(
        source: JobTarget,
        typeOfSource: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonPrimitive(source.displayName())

    override fun deserialize(
        json: JsonElement,
        typeOfTarget: Type,
        context: JsonDeserializationContext,
    ): JobTarget {
        if (json.isJsonNull) return JobTarget.All

        if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
            return JobTarget.parse(json.asString)
        }

        if (json.isJsonObject) {
            val legacyName = json.asJsonObject.get("name") ?: return JobTarget.All
            if (legacyName.isJsonNull) return JobTarget.All
            if (legacyName.isJsonPrimitive && legacyName.asJsonPrimitive.isString) {
                return JobTarget.parse(legacyName.asString)
            }
        }

        throw JsonParseException("Unsupported JobTarget JSON: $json")
    }
}
