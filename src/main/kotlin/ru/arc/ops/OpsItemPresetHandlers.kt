package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * Structured content-management API for ARC ItemSpec presets and bundles.
 *
 * The public schema is deliberately independent of the flattened YAML shape.
 * Persistence and runtime reads both go through [ItemPresets].
 */
object OpsItemPresetHandlers {
    fun list(id: String? = null): Map<String, Any?> {
        val normalizedId = id?.takeIf { it.isNotBlank() }?.let(ItemPresets::normalize)
        val definitions =
            if (normalizedId == null) {
                ItemPresets.allDefinitions()
            } else {
                listOfNotNull(ItemPresets.definition(normalizedId))
            }
        if (normalizedId != null && definitions.isEmpty()) {
            throw NoSuchElementException("Item preset not found: $normalizedId")
        }
        return mapOf(
            "source" to "arc-item-presets",
            "count" to definitions.size,
            "presets" to definitions.map(::definitionToMap),
        )
    }

    fun preview(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val definition = parseDefinition(id, body)
        return OpsBukkitSync.call {
            val stacks = validateAndBuild(definition)
            mapOf(
                "source" to "arc-item-presets",
                "preview" to true,
                "persisted" to false,
                "exists" to (ItemPresets.definition(definition.id) != null),
                "preset" to definitionToMap(definition),
                "resolvedItems" to stacks.map(OpsItemSpec::toMap),
            )
        }
    }

    fun upsert(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val definition = parseDefinition(id, body)
        return OpsBukkitSync.call {
            validateAndBuild(definition)
            val existed = ItemPresets.definition(definition.id) != null
            val saved = ItemPresets.upsert(definition)
            mapOf(
                "source" to "arc-item-presets",
                "created" to !existed,
                "saved" to true,
                "preset" to definitionToMap(saved),
            )
        }
    }

    fun delete(id: String): Map<String, Any?> {
        val deleted = ItemPresets.delete(id)
        return mapOf(
            "source" to "arc-item-presets",
            "deleted" to true,
            "id" to deleted,
        )
    }

    fun give(
        playerName: String,
        body: JsonObject,
    ): Map<String, Any?> {
        rejectUnknownFields(body, GIVE_FIELDS)
        val preset = requiredString(body, "preset", "body", MAX_ID_LENGTH).let(ItemPresets::normalize)
        val amount = optionalInteger(body, "amount", "body", 1, 1, MAX_AMOUNT)
        val dropOverflow = optionalBoolean(body, "dropOverflow", "body", true)

        return OpsBukkitSync.call {
            val player =
                Bukkit.getPlayerExact(playerName)
                    ?: throw IllegalArgumentException("Player not online: $playerName")
            val stacks =
                ItemPresets.resolveStacks(preset, amount).getOrElse {
                    throw IllegalArgumentException(it.message ?: "Unknown preset: $preset")
                }
            var inserted = 0
            var overflow = 0
            for (stack in stacks) {
                val leftover = player.inventory.addItem(stack.clone())
                val overflowForStack = leftover.values.sumOf { it.amount }
                inserted += stack.amount - overflowForStack
                overflow += overflowForStack
                if (dropOverflow && overflowForStack > 0) {
                    leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                }
            }
            mapOf(
                "source" to "arc-item-presets",
                "player" to player.name,
                "preset" to preset,
                "requestedAmount" to amount,
                "resolvedStacks" to stacks.size,
                "requestedItems" to stacks.sumOf { it.amount },
                "insertedItems" to inserted,
                "overflowItems" to overflow,
                "overflowDropped" to (dropOverflow && overflow > 0),
                "items" to stacks.map(OpsItemSpec::toMap),
            )
        }
    }

    internal fun parseDefinition(
        id: String,
        body: JsonObject,
    ): ItemPresetDefinition {
        val normalizedId = ItemPresets.normalize(id)
        rejectUnknownFields(body, TOP_LEVEL_FIELDS)
        body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let { idElement ->
            require(idElement.isJsonPrimitive && idElement.asJsonPrimitive.isString) {
                "id must be a string"
            }
            require(ItemPresets.normalize(idElement.asString) == normalizedId) {
                "body id must match route id"
            }
        }

        val type = requiredString(body, "type", "body", 16).lowercase()
        val description =
            body.get("description")?.takeUnless(JsonElement::isJsonNull)?.let { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    "description must be a string"
                }
                element.asString.trim().also {
                    require(it.length <= MAX_DESCRIPTION_LENGTH) {
                        "description must be at most $MAX_DESCRIPTION_LENGTH characters"
                    }
                }.takeIf(String::isNotEmpty)
            }

        return when (type) {
            "preset" -> {
                require(!body.has("items")) { "preset must use item, not items" }
                val itemElement =
                    body.get("item")?.takeUnless(JsonElement::isJsonNull)
                        ?: throw IllegalArgumentException("item is required for type=preset")
                require(itemElement.isJsonObject) { "item must be an object" }
                val item = itemElement.asJsonObject.deepCopy()
                validateItemSpec(item)
                ItemDefinition(normalizedId, description, item)
            }
            "bundle" -> {
                require(!body.has("item")) { "bundle must use items, not item" }
                val itemsElement =
                    body.get("items")?.takeUnless(JsonElement::isJsonNull)
                        ?: throw IllegalArgumentException("items is required for type=bundle")
                require(itemsElement.isJsonArray) { "items must be an array" }
                require(itemsElement.asJsonArray.size() in 1..MAX_BUNDLE_ITEMS) {
                    "items must contain 1-$MAX_BUNDLE_ITEMS entries"
                }
                val entries =
                    itemsElement.asJsonArray.mapIndexed { index, element ->
                        require(element.isJsonObject) { "items[$index] must be an object" }
                        parseBundleEntry(index, element.asJsonObject)
                    }
                BundleDefinition(normalizedId, description, entries)
            }
            else -> throw IllegalArgumentException("type must be 'preset' or 'bundle'")
        }
    }

    private fun parseBundleEntry(
        index: Int,
        body: JsonObject,
    ): BundleEntry {
        val path = "items[$index]"
        rejectUnknownFields(body, BUNDLE_ENTRY_FIELDS, path)
        val preset = requiredString(body, "preset", path, MAX_ID_LENGTH).let(ItemPresets::normalize)
        val rawAmount =
            body.get("amount")?.takeUnless(JsonElement::isJsonNull)
                ?: return BundleEntry(preset, 1)
        val amount =
            when {
                rawAmount.isJsonPrimitive && rawAmount.asJsonPrimitive.isString -> {
                    require(rawAmount.asString.trim().lowercase() == "scaled") {
                        "$path.amount must be an integer between 1 and $MAX_AMOUNT or 'scaled'"
                    }
                    null
                }
                rawAmount.isJsonPrimitive && rawAmount.asJsonPrimitive.isNumber ->
                    parseInteger(rawAmount, "$path.amount", 1, MAX_AMOUNT)
                else -> throw IllegalArgumentException(
                    "$path.amount must be an integer between 1 and $MAX_AMOUNT or 'scaled'",
                )
            }
        return BundleEntry(preset, amount)
    }

    private fun validateAndBuild(definition: ItemPresetDefinition): List<ItemStack> {
        ItemPresets.validateDefinition(definition)
        return when (definition) {
            is ItemDefinition -> listOf(OpsItemSpec.build(definition.item))
            is BundleDefinition ->
                definition.items.flatMap { entry ->
                    ItemPresets.resolveStacks(entry.preset, entry.amount ?: 1).getOrElse {
                        throw IllegalArgumentException(it.message ?: "Invalid bundle entry: ${entry.preset}")
                    }
                }
        }
    }

    private fun definitionToMap(definition: ItemPresetDefinition): Map<String, Any?> =
        when (definition) {
            is ItemDefinition ->
                linkedMapOf(
                    "id" to definition.id,
                    "type" to "preset",
                    "description" to definition.description,
                    "item" to definition.item,
                )
            is BundleDefinition ->
                linkedMapOf(
                    "id" to definition.id,
                    "type" to "bundle",
                    "description" to definition.description,
                    "items" to
                        definition.items.map { entry ->
                            mapOf(
                                "preset" to entry.preset,
                                "amount" to (entry.amount ?: "scaled"),
                            )
                        },
                )
        }

    internal fun validateItemSpec(item: JsonObject) {
        rejectUnknownFields(item, ITEM_FIELDS, "item")
        require(item.has("material") || item.has("itemsadder")) {
            "item requires material or itemsadder"
        }
        optionalString(item, "material", "item", 128)
        optionalString(item, "itemsadder", "item", 128)
        optionalString(item, "display", "item", MAX_TEXT_LENGTH)
        optionalString(item, "nbt", "item", MAX_NBT_LENGTH)
        item.get("amount")?.takeUnless(JsonElement::isJsonNull)?.let {
            parseInteger(it, "item.amount", 1, MAX_AMOUNT)
        }
        item.get("customModelData")?.takeUnless(JsonElement::isJsonNull)?.let {
            parseInteger(it, "item.customModelData", 0, Int.MAX_VALUE)
        }
        item.get("unbreakable")?.takeUnless(JsonElement::isJsonNull)?.let {
            requireBoolean(it, "item.unbreakable")
        }
        item.get("glowing")?.takeUnless(JsonElement::isJsonNull)?.let {
            requireBoolean(it, "item.glowing")
        }
        item.get("lore")?.takeUnless(JsonElement::isJsonNull)?.let { lore ->
            require(lore.isJsonArray) { "item.lore must be an array of strings" }
            require(lore.asJsonArray.size() <= MAX_LORE_LINES) {
                "item.lore must contain at most $MAX_LORE_LINES lines"
            }
            lore.asJsonArray.forEachIndexed { index, line ->
                require(line.isJsonPrimitive && line.asJsonPrimitive.isString) {
                    "item.lore[$index] must be a string"
                }
                require(line.asString.length <= MAX_TEXT_LENGTH) {
                    "item.lore[$index] must be at most $MAX_TEXT_LENGTH characters"
                }
            }
        }
        item.get("itemFlags")?.takeUnless(JsonElement::isJsonNull)?.let { flags ->
            require(flags.isJsonArray) { "item.itemFlags must be an array of strings" }
            flags.asJsonArray.forEachIndexed { index, flag ->
                require(flag.isJsonPrimitive && flag.asJsonPrimitive.isString) {
                    "item.itemFlags[$index] must be a string"
                }
            }
        }
        item.get("enchants")?.takeUnless(JsonElement::isJsonNull)?.let { enchants ->
            require(enchants.isJsonObject) { "item.enchants must be an object" }
            enchants.asJsonObject.entrySet().forEach { (key, level) ->
                require(key.isNotBlank()) { "item.enchants contains an empty key" }
                parseInteger(level, "item.enchants.$key", 1, 255)
            }
        }
        item.get("customData")?.takeUnless(JsonElement::isJsonNull)?.let { customData ->
            require(customData.isJsonObject) { "item.customData must be an object" }
            customData.asJsonObject.entrySet().forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 128) {
                    "item.customData keys must contain 1-128 characters"
                }
                require(
                    value.isJsonPrimitive &&
                        (value.asJsonPrimitive.isString ||
                            value.asJsonPrimitive.isBoolean ||
                            value.asJsonPrimitive.isNumber),
                ) {
                    "item.customData.$key must be a scalar value"
                }
            }
        }
    }

    private fun requiredString(
        body: JsonObject,
        field: String,
        path: String,
        maxLength: Int,
    ): String {
        val element =
            body.get(field)?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("$path.$field is required")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$path.$field must be a string"
        }
        return element.asString.trim().also {
            require(it.isNotEmpty()) { "$path.$field must not be empty" }
            require(it.length <= maxLength) { "$path.$field must be at most $maxLength characters" }
        }
    }

    private fun optionalString(
        body: JsonObject,
        field: String,
        path: String,
        maxLength: Int,
    ) {
        body.get(field)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "$path.$field must be a string"
            }
            require(element.asString.length <= maxLength) {
                "$path.$field must be at most $maxLength characters"
            }
        }
    }

    private fun optionalInteger(
        body: JsonObject,
        field: String,
        path: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return default
        return parseInteger(element, "$path.$field", min, max)
    }

    private fun parseInteger(
        element: JsonElement,
        path: String,
        min: Int,
        max: Int,
    ): Int {
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$path must be an integer"
        }
        val decimal = element.asBigDecimal
        val value =
            runCatching { decimal.intValueExact() }.getOrElse {
                throw IllegalArgumentException("$path must be an integer")
            }
        require(value in min..max) { "$path must be between $min and $max" }
        return value
    }

    private fun optionalBoolean(
        body: JsonObject,
        field: String,
        path: String,
        default: Boolean,
    ): Boolean {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return default
        requireBoolean(element, "$path.$field")
        return element.asBoolean
    }

    private fun requireBoolean(
        element: JsonElement,
        path: String,
    ) {
        require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
            "$path must be a boolean"
        }
    }

    private fun rejectUnknownFields(
        body: JsonObject,
        allowed: Set<String>,
        path: String = "body",
    ) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private val TOP_LEVEL_FIELDS = setOf("id", "type", "description", "item", "items")
    private val BUNDLE_ENTRY_FIELDS = setOf("preset", "amount")
    private val GIVE_FIELDS = setOf("preset", "amount", "dropOverflow")
    private val ITEM_FIELDS =
        setOf(
            "material",
            "amount",
            "display",
            "lore",
            "customModelData",
            "itemsadder",
            "enchants",
            "unbreakable",
            "itemFlags",
            "glowing",
            "nbt",
            "customData",
        )
    private const val MAX_AMOUNT = 64
    private const val MAX_BUNDLE_ITEMS = 100
    private const val MAX_DESCRIPTION_LENGTH = 500
    private const val MAX_TEXT_LENGTH = 4_096
    private const val MAX_LORE_LINES = 100
    private const val MAX_NBT_LENGTH = 65_536
    private const val MAX_ID_LENGTH = 64
}
