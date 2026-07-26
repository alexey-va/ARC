package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.Common

/**
 * Native source of truth for named ItemSpec presets and bundles.
 *
 * Reads, writes, `/arc give`, CMI kit references, and MCP all resolve through
 * this object. MCP must never parse `item-presets.yml` independently.
 */
object ItemPresets {
    private const val MAX_AMOUNT = 64
    private const val CONFIG_FILE = "item-presets.yml"
    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val mutationLock = Any()

    private val config: Config
        get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), CONFIG_FILE)

    fun normalize(name: String): String {
        val normalized = name.trim().lowercase().replace('-', '_').replace(' ', '_')
        require(ID_PATTERN.matches(normalized)) {
            "Item preset ID must match ${ID_PATTERN.pattern}: $name"
        }
        return normalized
    }

    fun allNames(): List<String> =
        synchronized(mutationLock) {
            allDefinitionsLocked().map { it.id }
        }

    internal fun allDefinitions(): List<ItemPresetDefinition> =
        synchronized(mutationLock) {
            allDefinitionsLocked()
        }

    internal fun definition(name: String): ItemPresetDefinition? =
        synchronized(mutationLock) {
            definitionLocked(normalize(name))
        }

    fun describe(name: String): String? = definition(name)?.description

    fun resolveStacks(
        name: String,
        amount: Int,
    ): Result<List<ItemStack>> =
        resolveSpecs(name, amount).map { specs -> specs.map(::buildStack) }

    fun resolveSpecs(
        name: String,
        amount: Int,
    ): Result<List<JsonObject>> =
        runCatching {
            synchronized(mutationLock) {
                resolveSpecsLocked(normalize(name), amount.coerceIn(1, MAX_AMOUNT))
            }
        }

    internal fun validateDefinition(definition: ItemPresetDefinition) {
        synchronized(mutationLock) {
            validateDefinitionLocked(definition)
        }
    }

    internal fun upsert(definition: ItemPresetDefinition): ItemPresetDefinition =
        synchronized(mutationLock) {
            validateDefinitionLocked(definition)
            val id = normalize(definition.id)
            val targetConfig = config

            targetConfig.removeKey("presets.$id")
            targetConfig.removeKey("bundles.$id")
            when (definition) {
                is ItemDefinition ->
                    targetConfig.setStructured("presets.$id", definition.toConfigMap())
                is BundleDefinition ->
                    targetConfig.setStructured("bundles.$id", definition.toConfigMap())
            }

            try {
                targetConfig.saveStrict()
            } catch (failure: Exception) {
                targetConfig.reload()
                throw IllegalStateException("Failed to persist item preset '$id'", failure)
            }
            definitionLocked(id)
                ?: throw IllegalStateException("Saved item preset disappeared from runtime config: $id")
        }

    internal fun delete(name: String): String =
        synchronized(mutationLock) {
            val id = normalize(name)
            val existing =
                definitionLocked(id)
                    ?: throw NoSuchElementException("Item preset not found: $id")
            if (existing is ItemDefinition) {
                val dependants =
                    bundleDefinitionsLocked()
                        .filter { bundle ->
                            bundle.id != id && bundle.items.any { it.preset == id }
                        }.map { it.id }
                        .sorted()
                require(dependants.isEmpty()) {
                    "Item preset '$id' is referenced by bundles: ${dependants.joinToString()}"
                }
            }

            val targetConfig = config
            targetConfig.removeKey("presets.$id")
            targetConfig.removeKey("bundles.$id")
            try {
                targetConfig.saveStrict()
            } catch (failure: Exception) {
                targetConfig.reload()
                throw IllegalStateException("Failed to delete item preset '$id' from persistent config", failure)
            }
            id
        }

    private fun allDefinitionsLocked(): List<ItemPresetDefinition> {
        val ids = (presetKeys() + bundleKeys()).sorted()
        return ids.map { id ->
            definitionLocked(normalize(id))
                ?: throw IllegalStateException("Item preset disappeared while reading catalog: $id")
        }
    }

    private fun definitionLocked(id: String): ItemPresetDefinition? {
        val hasPreset = config.exists("presets.$id")
        val hasBundle = config.exists("bundles.$id")
        check(!(hasPreset && hasBundle)) {
            "Item preset ID '$id' exists as both preset and bundle"
        }
        return try {
            when {
                hasPreset -> loadItemDefinition(id)
                hasBundle -> loadBundleDefinition(id)
                else -> null
            }
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Stored item preset '$id' is invalid: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        }
    }

    private fun loadItemDefinition(id: String): ItemDefinition {
        val section = config.map<Any>("presets.$id").toMutableMap()
        val description = parseDescription(section.remove("description"), "presets.$id.description")
        require(section.isNotEmpty()) { "Preset '$id' has no ItemSpec fields" }
        val item = Common.gson.toJsonTree(section).asJsonObject
        return ItemDefinition(id, description, item)
    }

    private fun loadBundleDefinition(id: String): BundleDefinition {
        val section = config.map<Any>("bundles.$id")
        val unknown = section.keys - setOf("description", "items")
        require(unknown.isEmpty()) {
            "Bundle '$id' contains unknown fields: ${unknown.sorted().joinToString()}"
        }
        val description = parseDescription(section["description"], "bundles.$id.description")
        val rawEntries =
            section["items"] as? List<*>
                ?: throw IllegalArgumentException("Bundle '$id' items must be a list")
        require(rawEntries.isNotEmpty()) { "Bundle '$id' has no items" }
        val entries =
            rawEntries.mapIndexed { index, raw ->
                val entry =
                    raw as? Map<*, *>
                        ?: throw IllegalArgumentException("Bundle '$id' items[$index] must be an object")
                val unknownEntryFields = entry.keys.mapNotNull { it?.toString() }.toSet() - setOf("preset", "amount")
                require(unknownEntryFields.isEmpty()) {
                    "Bundle '$id' items[$index] contains unknown fields: ${unknownEntryFields.sorted().joinToString()}"
                }
                val preset =
                    entry["preset"]?.toString()?.let(::normalize)
                        ?: throw IllegalArgumentException("Bundle '$id' items[$index].preset is required")
                BundleEntry(preset, parseBundleAmount(entry["amount"], "bundles.$id.items[$index].amount"))
            }
        return BundleDefinition(id, description, entries)
    }

    private fun resolveSpecsLocked(
        id: String,
        scaledAmount: Int,
    ): List<JsonObject> =
        when (val definition = definitionLocked(id)) {
            null -> throw IllegalArgumentException("Unknown preset: $id")
            is ItemDefinition -> listOf(resolveSingle(definition, scaledAmount))
            is BundleDefinition ->
                definition.items.flatMap { entry ->
                    val amount = entry.amount ?: scaledAmount
                    val referenced =
                        definitionLocked(entry.preset) as? ItemDefinition
                            ?: throw IllegalArgumentException(
                                "Bundle '$id' references unknown item preset '${entry.preset}'",
                            )
                    listOf(resolveSingle(referenced, amount))
                }
        }

    private fun resolveSingle(
        definition: ItemDefinition,
        amount: Int,
    ): JsonObject {
        val json = JsonParser.parseString(definition.item.toString()).asJsonObject
        json.addProperty("amount", amount.coerceIn(1, MAX_AMOUNT))
        return json
    }

    private fun validateDefinitionLocked(definition: ItemPresetDefinition) {
        val id = normalize(definition.id)
        require(id == definition.id) { "Item preset ID must be normalized: $id" }
        when (definition) {
            is ItemDefinition -> require(definition.item.size() > 0) { "Preset '$id' must contain an ItemSpec" }
            is BundleDefinition -> {
                require(definition.items.isNotEmpty()) { "Bundle '$id' must contain at least one item" }
                val existingPresets = presetKeys().map(::normalize).toMutableSet()
                existingPresets.remove(id)
                definition.items.forEachIndexed { index, entry ->
                    require(entry.preset in existingPresets) {
                        "Bundle '$id' items[$index] references unknown item preset '${entry.preset}'"
                    }
                }

                if (config.exists("presets.$id")) {
                    val dependants =
                        bundleDefinitionsLocked()
                            .filter { bundle ->
                                bundle.id != id && bundle.items.any { it.preset == id }
                            }.map { it.id }
                            .sorted()
                    require(dependants.isEmpty()) {
                        "Cannot replace item preset '$id' with a bundle; referenced by: ${dependants.joinToString()}"
                    }
                }
            }
        }
    }

    private fun bundleDefinitionsLocked(): List<BundleDefinition> =
        bundleKeys().sorted().map { loadBundleDefinition(normalize(it)) }

    private fun parseDescription(
        raw: Any?,
        path: String,
    ): String? {
        if (raw == null) return null
        require(raw is String) { "$path must be a string" }
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseBundleAmount(
        raw: Any?,
        path: String,
    ): Int? =
        when (raw) {
            null -> 1
            is Number -> {
                val value = raw.toDouble()
                require(value.isFinite() && value % 1.0 == 0.0 && value in 1.0..MAX_AMOUNT.toDouble()) {
                    "$path must be an integer between 1 and $MAX_AMOUNT or 'scaled'"
                }
                value.toInt()
            }
            is String -> {
                require(raw.trim().lowercase() == "scaled") {
                    "$path must be an integer between 1 and $MAX_AMOUNT or 'scaled'"
                }
                null
            }
            else -> throw IllegalArgumentException(
                "$path must be an integer between 1 and $MAX_AMOUNT or 'scaled'",
            )
        }

    private fun presetKeys(): Set<String> = config.keys("presets")

    private fun bundleKeys(): Set<String> = config.keys("bundles")

    private fun buildStack(json: JsonObject): ItemStack = OpsItemSpec.build(json)
}

internal sealed interface ItemPresetDefinition {
    val id: String
    val description: String?
}

internal data class ItemDefinition(
    override val id: String,
    override val description: String?,
    val item: JsonObject,
) : ItemPresetDefinition {
    fun toConfigMap(): Map<String, Any> =
        linkedMapOf<String, Any>().apply {
            description?.let { put("description", it) }
            item.entrySet().forEach { (key, value) ->
                put(key, value.toConfigValue("item.$key"))
            }
        }
}

internal data class BundleDefinition(
    override val id: String,
    override val description: String?,
    val items: List<BundleEntry>,
) : ItemPresetDefinition {
    fun toConfigMap(): Map<String, Any> =
        linkedMapOf<String, Any>().apply {
            description?.let { put("description", it) }
            put(
                "items",
                items.map { entry ->
                    linkedMapOf<String, Any>(
                        "preset" to entry.preset,
                        "amount" to (entry.amount ?: "scaled"),
                    )
                },
            )
        }
}

internal data class BundleEntry(
    val preset: String,
    /** Null means use the amount supplied when the bundle is resolved. */
    val amount: Int?,
)

private fun JsonElement.toConfigValue(path: String): Any =
    when {
        isJsonObject ->
            asJsonObject.entrySet().associateTo(linkedMapOf()) { (key, value) ->
                require(key.isNotBlank()) { "$path contains an empty object key" }
                key to value.toConfigValue("$path.$key")
            }
        isJsonArray ->
            asJsonArray.mapIndexed { index, value ->
                require(!value.isJsonNull) { "$path[$index] must not be null" }
                value.toConfigValue("$path[$index]")
            }
        isJsonPrimitive && asJsonPrimitive.isBoolean -> asBoolean
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonPrimitive && asJsonPrimitive.isNumber -> {
            val decimal = asBigDecimal
            if (decimal.stripTrailingZeros().scale() <= 0) {
                runCatching { decimal.intValueExact() }.getOrElse {
                    runCatching { decimal.longValueExact() }.getOrElse {
                        throw IllegalArgumentException("$path integer is outside the supported range")
                    }
                }
            } else {
                val value = decimal.toDouble()
                require(value.isFinite()) { "$path must be finite" }
                value
            }
        }
        else -> throw IllegalArgumentException("$path must not be null")
    }
