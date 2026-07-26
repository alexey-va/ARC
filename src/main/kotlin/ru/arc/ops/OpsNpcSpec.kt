package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot
import net.citizensnpcs.trait.CommandTrait
import org.bukkit.entity.EntityType

internal sealed interface NpcPatch<out T> {
    data object Absent : NpcPatch<Nothing>

    data object Clear : NpcPatch<Nothing>

    data class Set<T>(
        val value: T,
    ) : NpcPatch<T>
}

internal data class OpsNpcSpec(
    val name: NpcPatch<String>,
    val type: NpcPatch<EntityType>,
    val protectedState: NpcPatch<Boolean>,
    val location: NpcPatch<LocationSpec>,
    val useMinecraftAi: NpcPatch<Boolean>,
    val skin: NpcPatch<SkinSpec>,
    val lookClose: NpcPatch<LookCloseSpec>,
    val commands: NpcPatch<CommandsSpec>,
    val hologram: NpcPatch<HologramSpec>,
    val equipment: NpcPatch<EquipmentSpec>,
    val path: NpcPatch<PathSpec>,
    val text: NpcPatch<TextSpec>,
    val navigation: NpcPatch<NavigationSpec>,
) {
    val changedFields: List<String>
        get() =
            listOf(
                "name" to name,
                "type" to type,
                "protected" to protectedState,
                "location" to location,
                "useMinecraftAi" to useMinecraftAi,
                "skin" to skin,
                "lookClose" to lookClose,
                "commands" to commands,
                "hologram" to hologram,
                "equipment" to equipment,
                "path" to path,
                "text" to text,
                "navigation" to navigation,
            ).mapNotNull { (key, patch) -> key.takeUnless { patch === NpcPatch.Absent } }

    fun requireCreateFields() {
        require(name is NpcPatch.Set) { "name required when creating an NPC" }
        require(location is NpcPatch.Set) { "location required when creating an NPC" }
        location.value.requireCreateFields()
    }

    companion object {
        private val rootFields =
            setOf(
                "name",
                "type",
                "protected",
                "location",
                "useMinecraftAi",
                "skin",
                "lookClose",
                "commands",
                "hologram",
                "equipment",
                "path",
                "text",
                "navigation",
            )

        fun parse(body: JsonObject): OpsNpcSpec {
            requireKnownFields(body, rootFields, "npc")
            require(body.size() > 0) { "NpcSpec must contain at least one field" }
            return OpsNpcSpec(
                name = field(body, "name", clearable = false) { validatedString(it, "name", 64) },
                type = field(body, "type", clearable = false) { parseEntityType(string(it, "type")) },
                protectedState = field(body, "protected", clearable = false) { boolean(it, "protected") },
                location = field(body, "location", clearable = false, ::parseLocation),
                useMinecraftAi =
                    field(body, "useMinecraftAi", clearable = false) {
                        boolean(it, "useMinecraftAi")
                    },
                skin = field(body, "skin", clearable = true, ::parseSkin),
                lookClose = field(body, "lookClose", clearable = true, ::parseLookClose),
                commands = field(body, "commands", clearable = true, ::parseCommands),
                hologram = field(body, "hologram", clearable = true, ::parseHologram),
                equipment = field(body, "equipment", clearable = true, ::parseEquipment),
                path = field(body, "path", clearable = true, ::parsePath),
                text = field(body, "text", clearable = true, ::parseText),
                navigation = field(body, "navigation", clearable = false, ::parseNavigation),
            )
        }
    }
}

internal data class LocationSpec(
    val world: NpcPatch<String>,
    val x: NpcPatch<Double>,
    val y: NpcPatch<Double>,
    val z: NpcPatch<Double>,
    val yaw: NpcPatch<Float>,
    val pitch: NpcPatch<Float>,
) {
    fun requireCreateFields() {
        require(world is NpcPatch.Set) { "location.world required when creating an NPC" }
        require(x is NpcPatch.Set) { "location.x required when creating an NPC" }
        require(z is NpcPatch.Set) { "location.z required when creating an NPC" }
    }
}

internal data class SkinSpec(
    val name: NpcPatch<String>,
    val update: NpcPatch<Boolean>,
)

internal data class LookCloseSpec(
    val enabled: NpcPatch<Boolean>,
    val range: NpcPatch<Double>,
    val realisticLooking: NpcPatch<Boolean>,
    val randomLook: NpcPatch<Boolean>,
    val disableWhileNavigating: NpcPatch<Boolean>,
    val targetNpcs: NpcPatch<Boolean>,
    val perPlayer: NpcPatch<Boolean>,
)

internal enum class NpcCommandRunAs {
    SERVER,
    PLAYER,
    NPC,
}

internal data class NpcCommandSpec(
    val command: String,
    val hand: CommandTrait.Hand,
    val runAs: NpcCommandRunAs,
    val cooldownSeconds: Long?,
    val globalCooldownSeconds: Long?,
    val delayTicks: Long?,
    val maxUses: Int?,
    val permissions: List<String>,
)

internal data class CommandsSpec(
    val entries: NpcPatch<List<NpcCommandSpec>>,
    val mode: NpcPatch<CommandTrait.ExecutionMode>,
    val persistSequence: NpcPatch<Boolean>,
    val hideErrors: NpcPatch<Boolean>,
)

internal data class HologramSpec(
    val lines: NpcPatch<List<String>>,
    val lineHeight: NpcPatch<Double>,
    val viewRange: NpcPatch<Int>,
)

internal data class EquipmentSpec(
    val slots: Map<EquipmentSlot, JsonObject?>,
)

internal data class PathPointSpec(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

internal data class PathSpec(
    val provider: NpcPatch<String>,
    val points: NpcPatch<List<PathPointSpec>>,
    val cycle: NpcPatch<Boolean>,
    val cachePaths: NpcPatch<Boolean>,
)

internal data class TextSpec(
    val lines: NpcPatch<List<String>>,
    val talkClose: NpcPatch<Boolean>,
    val randomTalker: NpcPatch<Boolean>,
    val realisticLooking: NpcPatch<Boolean>,
    val speechBubbles: NpcPatch<Boolean>,
    val delayTicks: NpcPatch<Int>,
    val range: NpcPatch<Double>,
)

internal data class NavigationSpec(
    val speedModifier: NpcPatch<Double>,
    val range: NpcPatch<Double>,
    val avoidWater: NpcPatch<Boolean>,
    val distanceMargin: NpcPatch<Double>,
    val pathDistanceMargin: NpcPatch<Double>,
)

private fun parseLocation(element: JsonElement): LocationSpec {
    val body = objectValue(element, "location")
    requireKnownFields(body, setOf("world", "x", "y", "z", "yaw", "pitch"), "location")
    require(body.size() > 0) { "location must contain at least one field" }
    return LocationSpec(
        world =
            field(body, "world", clearable = false) {
                validatedString(it, "location.world", 80)
            },
        x = field(body, "x", clearable = false) { finiteDouble(it, "location.x") },
        y = field(body, "y", clearable = false) { finiteDouble(it, "location.y") },
        z = field(body, "z", clearable = false) { finiteDouble(it, "location.z") },
        yaw =
            field(body, "yaw", clearable = false) {
                finiteDouble(it, "location.yaw").toFloat()
            },
        pitch =
            field(body, "pitch", clearable = false) {
                finiteDouble(it, "location.pitch").toFloat()
            },
    )
}

private fun parseSkin(element: JsonElement): SkinSpec {
    val body = objectValue(element, "skin")
    requireKnownFields(body, setOf("name", "update"), "skin")
    return SkinSpec(
        name = field(body, "name", clearable = false) { validatedString(it, "skin.name", 64) },
        update = field(body, "update", clearable = false) { boolean(it, "skin.update") },
    )
}

private fun parseLookClose(element: JsonElement): LookCloseSpec {
    val body = objectValue(element, "lookClose")
    requireKnownFields(
        body,
        setOf(
            "enabled",
            "range",
            "realisticLooking",
            "randomLook",
            "disableWhileNavigating",
            "targetNpcs",
            "perPlayer",
        ),
        "lookClose",
    )
    return LookCloseSpec(
        enabled = field(body, "enabled", clearable = false) { boolean(it, "lookClose.enabled") },
        range =
            field(body, "range", clearable = false) {
                rangedDouble(it, "lookClose.range", 1.0, 128.0)
            },
        realisticLooking =
            field(body, "realisticLooking", clearable = false) {
                boolean(it, "lookClose.realisticLooking")
            },
        randomLook =
            field(body, "randomLook", clearable = false) {
                boolean(it, "lookClose.randomLook")
            },
        disableWhileNavigating =
            field(body, "disableWhileNavigating", clearable = false) {
                boolean(it, "lookClose.disableWhileNavigating")
            },
        targetNpcs =
            field(body, "targetNpcs", clearable = false) {
                boolean(it, "lookClose.targetNpcs")
            },
        perPlayer =
            field(body, "perPlayer", clearable = false) {
                boolean(it, "lookClose.perPlayer")
            },
    )
}

private fun parseCommands(element: JsonElement): CommandsSpec {
    val body = objectValue(element, "commands")
    requireKnownFields(body, setOf("entries", "mode", "persistSequence", "hideErrors"), "commands")
    return CommandsSpec(
        entries =
            field(body, "entries", clearable = false) { value ->
                val array = arrayValue(value, "commands.entries")
                require(array.size() <= 64) { "commands.entries must contain at most 64 commands" }
                array.mapIndexed { index, entry -> parseCommand(entry, index) }
            },
        mode =
            field(body, "mode", clearable = false) {
                enumValue<CommandTrait.ExecutionMode>(string(it, "commands.mode"), "commands.mode")
            },
        persistSequence =
            field(body, "persistSequence", clearable = false) {
                boolean(it, "commands.persistSequence")
            },
        hideErrors =
            field(body, "hideErrors", clearable = false) {
                boolean(it, "commands.hideErrors")
            },
    )
}

private fun parseCommand(
    element: JsonElement,
    index: Int,
): NpcCommandSpec {
    val path = "commands.entries[$index]"
    val body = objectValue(element, path)
    requireKnownFields(
        body,
        setOf(
            "command",
            "hand",
            "runAs",
            "cooldownSeconds",
            "globalCooldownSeconds",
            "delayTicks",
            "maxUses",
            "permissions",
        ),
        path,
    )
    val command = validatedString(required(body, "command"), "$path.command", 512)
    require(!command.startsWith('/')) { "$path.command must not start with '/'" }
    return NpcCommandSpec(
        command = command,
        hand =
            body.get("hand")?.let {
                enumValue<CommandTrait.Hand>(string(it, "$path.hand"), "$path.hand")
            } ?: CommandTrait.Hand.BOTH,
        runAs =
            body.get("runAs")?.let {
                enumValue<NpcCommandRunAs>(string(it, "$path.runAs"), "$path.runAs")
            } ?: NpcCommandRunAs.SERVER,
        cooldownSeconds =
            body.get("cooldownSeconds")?.let {
                nonNegativeLong(it, "$path.cooldownSeconds").also { value ->
                    require(value <= Int.MAX_VALUE) {
                        "$path.cooldownSeconds must be at most ${Int.MAX_VALUE}"
                    }
                }
            },
        globalCooldownSeconds =
            body.get("globalCooldownSeconds")?.let {
                nonNegativeLong(it, "$path.globalCooldownSeconds").also { value ->
                    require(value <= Int.MAX_VALUE) {
                        "$path.globalCooldownSeconds must be at most ${Int.MAX_VALUE}"
                    }
                }
            },
        delayTicks =
            body.get("delayTicks")?.let {
                nonNegativeLong(it, "$path.delayTicks").also { value ->
                    require(value <= 72_000) { "$path.delayTicks must be within 0..72000" }
                }
            },
        maxUses =
            body.get("maxUses")?.let {
                integer(it, "$path.maxUses").also { value ->
                    require(value > 0) { "$path.maxUses must be positive" }
                }
            },
        permissions = body.get("permissions")?.let { stringList(it, "$path.permissions", 32, 128) }.orEmpty(),
    )
}

private fun parseHologram(element: JsonElement): HologramSpec {
    val body = objectValue(element, "hologram")
    requireKnownFields(body, setOf("lines", "lineHeight", "viewRange"), "hologram")
    return HologramSpec(
        lines =
            field(body, "lines", clearable = false) {
                stringList(it, "hologram.lines", 32, 512)
            },
        lineHeight =
            field(body, "lineHeight", clearable = false) {
                rangedDouble(it, "hologram.lineHeight", 0.05, 2.0)
            },
        viewRange =
            field(body, "viewRange", clearable = false) {
                integer(it, "hologram.viewRange").also { value ->
                    require(value in 1..512) { "hologram.viewRange must be within 1..512" }
                }
            },
    )
}

private fun parseEquipment(element: JsonElement): EquipmentSpec {
    val body = objectValue(element, "equipment")
    val slots =
        body.entrySet().associate { (rawSlot, value) ->
            val slot = enumValue<EquipmentSlot>(rawSlot, "equipment slot")
            slot to
                if (value.isJsonNull) {
                    null
                } else {
                    objectValue(value, "equipment.$rawSlot").deepCopy()
                }
        }
    return EquipmentSpec(slots)
}

private fun parsePath(element: JsonElement): PathSpec {
    val body = objectValue(element, "path")
    requireKnownFields(body, setOf("provider", "points", "cycle", "cachePaths"), "path")
    return PathSpec(
        provider =
            field(body, "provider", clearable = false) {
                string(it, "path.provider").lowercase().also { provider ->
                    require(provider == "linear") { "Only the linear path provider is supported by NpcSpec" }
                }
            },
        points =
            field(body, "points", clearable = false) { value ->
                val array = arrayValue(value, "path.points")
                require(array.size() <= 256) { "path.points must contain at most 256 points" }
                array.mapIndexed { index, point -> parsePathPoint(point, index) }
            },
        cycle = field(body, "cycle", clearable = false) { boolean(it, "path.cycle") },
        cachePaths = field(body, "cachePaths", clearable = false) { boolean(it, "path.cachePaths") },
    )
}

private fun parsePathPoint(
    element: JsonElement,
    index: Int,
): PathPointSpec {
    val path = "path.points[$index]"
    val body = objectValue(element, path)
    requireKnownFields(body, setOf("world", "x", "y", "z", "yaw", "pitch"), path)
    return PathPointSpec(
        world = validatedString(required(body, "world"), "$path.world", 80),
        x = finiteDouble(required(body, "x"), "$path.x"),
        y = finiteDouble(required(body, "y"), "$path.y"),
        z = finiteDouble(required(body, "z"), "$path.z"),
        yaw = body.get("yaw")?.let { finiteDouble(it, "$path.yaw").toFloat() } ?: 0f,
        pitch = body.get("pitch")?.let { finiteDouble(it, "$path.pitch").toFloat() } ?: 0f,
    )
}

private fun parseText(element: JsonElement): TextSpec {
    val body = objectValue(element, "text")
    requireKnownFields(
        body,
        setOf("lines", "talkClose", "randomTalker", "realisticLooking", "speechBubbles", "delayTicks", "range"),
        "text",
    )
    return TextSpec(
        lines = field(body, "lines", clearable = false) { stringList(it, "text.lines", 64, 512) },
        talkClose = field(body, "talkClose", clearable = false) { boolean(it, "text.talkClose") },
        randomTalker = field(body, "randomTalker", clearable = false) { boolean(it, "text.randomTalker") },
        realisticLooking =
            field(body, "realisticLooking", clearable = false) {
                boolean(it, "text.realisticLooking")
            },
        speechBubbles =
            field(body, "speechBubbles", clearable = false) {
                boolean(it, "text.speechBubbles")
            },
        delayTicks =
            field(body, "delayTicks", clearable = false) {
                integer(it, "text.delayTicks").also { value ->
                    require(value in 1..72_000) { "text.delayTicks must be within 1..72000" }
                }
            },
        range =
            field(body, "range", clearable = false) {
                rangedDouble(it, "text.range", 1.0, 128.0)
            },
    )
}

private fun parseNavigation(element: JsonElement): NavigationSpec {
    val body = objectValue(element, "navigation")
    requireKnownFields(
        body,
        setOf("speedModifier", "range", "avoidWater", "distanceMargin", "pathDistanceMargin"),
        "navigation",
    )
    return NavigationSpec(
        speedModifier =
            field(body, "speedModifier", clearable = false) {
                rangedDouble(it, "navigation.speedModifier", 0.05, 5.0)
            },
        range =
            field(body, "range", clearable = false) {
                rangedDouble(it, "navigation.range", 1.0, 512.0)
            },
        avoidWater = field(body, "avoidWater", clearable = false) { boolean(it, "navigation.avoidWater") },
        distanceMargin =
            field(body, "distanceMargin", clearable = false) {
                rangedDouble(it, "navigation.distanceMargin", 0.0, 16.0)
            },
        pathDistanceMargin =
            field(body, "pathDistanceMargin", clearable = false) {
                rangedDouble(it, "navigation.pathDistanceMargin", 0.0, 16.0)
            },
    )
}

private inline fun <T> field(
    body: JsonObject,
    key: String,
    clearable: Boolean,
    parse: (JsonElement) -> T,
): NpcPatch<T> {
    if (!body.has(key)) return NpcPatch.Absent
    val element = body.get(key)
    if (element.isJsonNull) {
        require(clearable) { "$key cannot be null" }
        return NpcPatch.Clear
    }
    return NpcPatch.Set(parse(element))
}

private fun requireKnownFields(
    body: JsonObject,
    allowed: Set<String>,
    path: String,
) {
    val unknown = body.keySet() - allowed
    require(unknown.isEmpty()) { "$path contains unknown field(s): ${unknown.sorted().joinToString(", ")}" }
}

private fun required(
    body: JsonObject,
    key: String,
): JsonElement = body.get(key) ?: throw IllegalArgumentException("$key required")

private fun objectValue(
    element: JsonElement,
    path: String,
): JsonObject {
    require(element.isJsonObject) { "$path must be a JSON object" }
    return element.asJsonObject
}

private fun arrayValue(
    element: JsonElement,
    path: String,
) = element.also { require(it.isJsonArray) { "$path must be an array" } }.asJsonArray

private fun string(
    element: JsonElement,
    path: String,
): String {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$path must be a string" }
    return element.asString
}

private fun validatedString(
    element: JsonElement,
    path: String,
    maxLength: Int,
): String {
    val value = string(element, path).trim()
    require(value.isNotEmpty()) { "$path must not be blank" }
    require(value.length <= maxLength) { "$path must be at most $maxLength characters" }
    require(value.none(Char::isISOControl)) { "$path contains control characters" }
    return value
}

private fun boolean(
    element: JsonElement,
    path: String,
): Boolean {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { "$path must be a boolean" }
    return element.asBoolean
}

private fun finiteDouble(
    element: JsonElement,
    path: String,
): Double {
    require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path must be a number" }
    return element.asDouble.also { require(it.isFinite()) { "$path must be finite" } }
}

private fun rangedDouble(
    element: JsonElement,
    path: String,
    min: Double,
    max: Double,
): Double =
    finiteDouble(element, path).also {
        require(it in min..max) { "$path must be within $min..$max" }
    }

private fun integer(
    element: JsonElement,
    path: String,
): Int {
    val number = finiteDouble(element, path)
    require(number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
        "$path must be an integer"
    }
    return number.toInt()
}

private fun nonNegativeLong(
    element: JsonElement,
    path: String,
): Long {
    val number = finiteDouble(element, path)
    require(number % 1.0 == 0.0 && number >= 0.0 && number <= Long.MAX_VALUE.toDouble()) {
        "$path must be a non-negative integer"
    }
    return number.toLong()
}

private inline fun <reified T : Enum<T>> enumValue(
    raw: String,
    path: String,
): T =
    runCatching { enumValueOf<T>(raw.trim().uppercase()) }
        .getOrElse {
            throw IllegalArgumentException(
                "$path must be one of ${enumValues<T>().joinToString(", ") { value -> value.name.lowercase() }}",
            )
        }

private fun parseEntityType(raw: String): EntityType {
    val type = enumValue<EntityType>(raw, "type")
    require(type != EntityType.UNKNOWN && (type == EntityType.PLAYER || type.isAlive)) {
        "type must be PLAYER or a living entity"
    }
    return type
}

private fun stringList(
    element: JsonElement,
    path: String,
    maxSize: Int,
    maxLength: Int,
): List<String> {
    val array = arrayValue(element, path)
    require(array.size() <= maxSize) { "$path must contain at most $maxSize entries" }
    return array.mapIndexed { index, value ->
        validatedString(value, "$path[$index]", maxLength)
    }
}
